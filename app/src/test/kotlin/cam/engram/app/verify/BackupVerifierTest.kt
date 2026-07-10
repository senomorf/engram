package cam.engram.app.verify

import cam.engram.app.FakeContentAccess
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.read.Survival
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-app survivability check (design D14): given a file that round-tripped a
 * cloud or messenger, report what survived. Exercises each container and each
 * Survival verdict, on the JVM through the ContentAccess seam (no resolver shadow).
 */
class BackupVerifierTest {
    private val access = FakeContentAccess()
    private val verifier = BackupVerifier(access)

    private fun records() =
        listOf(
            EngramRecord(RecordKind.Note, 1, "at the lake".encodeToByteArray()),
            EngramRecord(RecordKind.Audio, 2, AudioPayload.encode("audio/ogg", ByteArray(20) { 4 })),
        )

    private fun oneNote() = listOf(EngramRecord(RecordKind.Note, 1, "at the lake".encodeToByteArray()))

    // corrupt the first record's payload in place: keeps the frame length so the record
    // still decodes, but its CRC no longer matches (a bad-CRC record, not a missing one)
    private fun corruptFirstRecordPayload(bytes: ByteArray): ByteArray {
        val magic = EngramRecord.MAGIC
        val at = (0..bytes.size - magic.size).first { i -> magic.indices.all { bytes[i + it] == magic[it] } }
        return bytes.copyOf().also {
            it[at + EngramRecord.HEADER_LEN] = (it[at + EngramRecord.HEADER_LEN] + 1).toByte()
        }
    }

    private fun verify(
        uri: String,
        bytes: ByteArray,
    ) = runBlocking {
        access.files[uri] = bytes
        verifier.verify(uri)
    }

    @Test
    fun jpegWithRecordsReportsFull() {
        val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records(), "at the lake")
        val report = verify("content://x/1", bytes)
        assertEquals(Survival.FULL, report.summary)
        assertTrue(report.hasNote)
        assertEquals(1, report.audioClips)
        assertTrue(report.captionVisible)
        assertEquals(true, report.mpfIntact ?: true)
    }

    @Test
    fun jpegWithCaptionButNoRecordsReportsCaptionOnly() {
        val embedded = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records(), "at the lake")
        // simulate a pipeline that dropped the trailer records but kept header metadata
        val captionOnly = embedded.copyOfRange(0, RecordStream.scan(embedded).first().offset)
        val report = verify("content://x/2", captionOnly)
        assertEquals(Survival.CAPTION_ONLY, report.summary)
        assertFalse(report.hasNote)
        assertTrue(report.captionVisible)
    }

    @Test
    fun plainJpegReportsGone() {
        val report = verify("content://x/3", SyntheticMedia.jpegPlain())
        assertEquals(Survival.GONE, report.summary)
        assertEquals(0, report.recordCount)
        assertFalse(report.captionVisible)
    }

    @Test
    fun garbageReportsUnreadable() {
        val report = verify("content://x/4", ByteArray(32) { 0x7F })
        assertEquals(Survival.UNREADABLE, report.summary)
    }

    @Test
    fun pngWithRecordsReportsFull() {
        val bytes = PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), records(), "at the lake")
        val report = verify("content://x/5", bytes)
        assertEquals(Survival.FULL, report.summary)
        assertTrue(report.hasNote)
    }

    @Test
    fun unreadableWhenStreamMissing() {
        // a uri the resolver cannot open -> UNREADABLE
        val report = runBlocking { verifier.verify("content://x/missing") }
        assertEquals(Survival.UNREADABLE, report.summary)
    }

    @Test
    fun mp4WithRecordsReportsFull() {
        val bytes = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), records())
        val report = verify("content://x/6", bytes)
        assertEquals(Survival.FULL, report.summary)
        assertTrue(report.hasNote)
        assertEquals(1, report.audioClips)
    }

    @Test
    fun pngWithCorruptRecordReportsDamagedNotFull() {
        val bytes = PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), oneNote(), null)
        val report = verify("content://x/7", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
        assertFalse(report.hasNote)
    }

    @Test
    fun mp4WithCorruptRecordReportsDamagedNotFull() {
        val bytes = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), oneNote())
        val report = verify("content://x/8", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
        assertFalse(report.hasNote)
    }

    @Test
    fun jpegWithCorruptRecordReportsDamaged() {
        val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), oneNote(), null)
        val report = verify("content://x/9", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
    }

    @Test
    fun jpegWithMixedCorruptionReportsDamagedNotFull() {
        val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records(), "at the lake")
        // corrupting the first of two records leaves one valid and one corrupt (a mix): the
        // verdict must not be FULL, or the user trusts a partial backup (finding 8)
        val report = verify("content://x/10", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
        assertEquals(1, report.recordCount, "the surviving valid record is still counted")
        assertEquals(1, report.corruptCount, "the corrupt record is surfaced")
    }

    @Test
    fun malformedMp4ReportsUnreadableNotGone() {
        // an mp4 whose boxes do not parse is unreadable media, not a healthy file with
        // no memories: GONE would tell the user their records were stripped
        val bytes = SyntheticMedia.mp4Minimal().copyOf().also { it[3] = 99 } // ftyp claims a bogus size
        val report = verify("content://x/13", bytes)
        assertEquals(Survival.UNREADABLE, report.summary)
    }

    @Test
    fun malformedPngChunkReportsDamagedNotFull() {
        val out = PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), oneNote(), null)
        // append an egRm chunk with a trailing byte: that frame no longer decodes, so it
        // was lost even though the first record still reads fine
        val iendAt = out.size - 12
        val broken =
            cam.engram.format.png
                .PngChunk("egRm", oneNote().single().encode() + byteArrayOf(0))
                .encode()
        val bytes = out.copyOfRange(0, iendAt) + broken + out.copyOfRange(iendAt, out.size)
        val report = verify("content://x/14", bytes)
        assertEquals(Survival.DAMAGED, report.summary)
        assertEquals(1, report.recordCount)
        assertEquals(1, report.corruptCount, "the lost chunk is surfaced as damage")
    }

    @Test
    fun frameInsideMetadataSegmentDoesNotCountAsSurvival() {
        // magic bytes inside a metadata segment are not records: the whole-file carve
        // used to report this malformed-but-parseable file FULL
        val parts =
            cam.engram.format.jpeg.JpegCodec
                .parse(SyntheticMedia.jpegPlain())
                .toMutableList()
        parts.add(
            1,
            cam.engram.format.jpeg.Segment
                .of(0xE7, oneNote().single().encode()),
        )
        val report =
            verify(
                "content://x/15",
                cam.engram.format.jpeg.JpegCodec
                    .serialize(parts),
            )
        assertEquals(Survival.GONE, report.summary)
    }

    // the MIXED verdict (some valid, some corrupt -> DAMAGED, not FULL) runs through the shared
    // report(), so it must hold on every container, not only JPEG (finding 8, codec uniformity)
    @Test
    fun pngWithMixedCorruptionReportsDamagedNotFull() {
        val bytes = PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), records(), "at the lake")
        val report = verify("content://x/11", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
        assertEquals(1, report.recordCount, "the surviving valid record is still counted")
        assertEquals(1, report.corruptCount, "the corrupt record is surfaced")
    }

    @Test
    fun mp4WithMixedCorruptionReportsDamagedNotFull() {
        val bytes = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), records())
        val report = verify("content://x/12", corruptFirstRecordPayload(bytes))
        assertEquals(Survival.DAMAGED, report.summary)
        assertEquals(1, report.recordCount, "the surviving valid record is still counted")
        assertEquals(1, report.corruptCount, "the corrupt record is surfaced")
    }
}
