package cam.engram.format.read

import cam.engram.format.FakeXmpEngine
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExtractionFilesTest {
    private val dir = createTempDirectory("extraction-files").toFile()
    private val xmp = FakeXmpEngine()

    private fun note() = EngramRecord(RecordKind.Note, 7, "clip".encodeToByteArray(), ByteArray(16) { 2 })

    @Test
    fun photoFileMatchesByteInspection() {
        val bytes = JpegEmbedder(xmp).embed(SyntheticMedia.jpegPlain(), listOf(note()), "kept")
        val file = File(dir, "p.jpg").apply { writeBytes(bytes) }
        val fromFile = ExtractionFiles.inspect(file, xmp)!!
        val fromBytes = ContainerExtraction.inspect(bytes, xmp)!!
        assertEquals(fromBytes.container, fromFile.container)
        assertEquals(fromBytes.records.size, fromFile.records.size)
        assertEquals(fromBytes.xmpSummary?.description, fromFile.xmpSummary?.description)
    }

    @Test
    fun mp4FileStreamsRecordsAndCaption() {
        val src = File(dir, "in.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "out.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "подпись")
        val x = ExtractionFiles.inspect(out, xmp)!!
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(1, x.records.size)
        assertEquals("подпись", x.mp4Caption)
    }

    @Test
    fun moovAboveTheCapSkipsTheCaptionButKeepsRecords() {
        val src = File(dir, "in2.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "out2.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "подпись")
        val x = ExtractionFiles.inspectMp4(out, moovCap = 8)
        assertNull(x.mp4Caption, "an implausibly large moov is skipped, not loaded")
        assertEquals(1, x.records.size)
    }

    @Test
    fun unknownFileYieldsNull() {
        val file = File(dir, "x.bin").apply { writeBytes(ByteArray(32) { 0x24 }) }
        assertNull(ExtractionFiles.inspect(file, xmp))
    }

    private fun <T> onChannel(
        bytes: ByteArray,
        block: (java.nio.channels.SeekableByteChannel) -> T,
    ): T {
        val f = File(dir, "ch-${bytes.size}-${bytes.hashCode()}.mp4").apply { writeBytes(bytes) }
        return java.io
            .FileInputStream(f)
            .channel
            .use(block)
    }

    @Test
    fun channelInspectionStreamsRecordsAndCaption() {
        val src = File(dir, "cin.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "cout.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "по каналу")
        val x =
            java.io
                .FileInputStream(out)
                .channel
                .use { ExtractionFiles.inspectMp4(it) }
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(1, x.records.size)
        assertEquals("по каналу", x.mp4Caption)
    }

    @Test
    fun channelInspectionFlagsAnUndecodableBoxTail() {
        val bytes =
            SyntheticMedia.mp4MoovLast() +
                cam.engram.format.mp4.Mp4Codec
                    .buildEngramBox(note().encode() + byteArrayOf(9, 9, 9))
        val x = onChannel(bytes) { ExtractionFiles.inspectMp4(it) }
        assertEquals(1, x.records.size)
        assertIs<CarrierIntegrity.CarrierDamaged>(x.integrity)
    }

    @Test
    fun channelInspectionReportsUnparseableMp4() {
        val bytes = SyntheticMedia.mp4Minimal().copyOf().also { it[3] = 99 } // ftyp claims a bogus size
        val x = onChannel(bytes) { ExtractionFiles.inspectMp4(it) }
        assertIs<CarrierIntegrity.Unreadable>(x.integrity)
    }

    @Test
    fun channelInspectionSkipsAnOversizedMoov() {
        val src = File(dir, "cin2.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "cout2.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "скрыто")
        val x =
            java.io
                .FileInputStream(out)
                .channel
                .use { ExtractionFiles.inspectMp4(it, moovCap = 8) }
        assertNull(x.mp4Caption, "an implausibly large moov is skipped, not loaded")
        assertEquals(1, x.records.size)
    }

    @Test
    fun channelInspectionWithoutAnEngramBoxIsReadableAndEmpty() {
        val x = onChannel(SyntheticMedia.mp4MoovLast()) { ExtractionFiles.inspectMp4(it) }
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(0, x.records.size)
    }
}
