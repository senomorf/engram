package cam.engram.format

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.jpeg.Segment
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngChunk
import cam.engram.format.png.PngEmbedder
import cam.engram.format.read.CarrierIntegrity
import cam.engram.format.read.ContainerExtraction
import cam.engram.format.read.ContainerType
import cam.engram.format.read.Survival
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerExtractionTest {
    private val xmp = FakeXmpEngine()

    private fun note(text: String = "kept") =
        EngramRecord(
            RecordKind.Note,
            42,
            text.encodeToByteArray(),
            ByteArray(16) {
                1
            },
        )

    private fun inspect(bytes: ByteArray) = ContainerExtraction.inspect(bytes, xmp)

    @Test
    fun detectRecognizesTheThreeContainersAndNothingElse() {
        assertEquals(ContainerType.JPEG, ContainerExtraction.detect(SyntheticMedia.jpegPlain()))
        assertEquals(ContainerType.PNG, ContainerExtraction.detect(SyntheticMedia.png1x1()))
        assertEquals(ContainerType.MP4, ContainerExtraction.detect(SyntheticMedia.mp4Minimal()))
        assertNull(ContainerExtraction.detect(ByteArray(16) { 0x42 }))
        assertNull(ContainerExtraction.detect(ByteArray(0)))
    }

    @Test
    fun jpegWithRecordsClassifiesFull() {
        val out = JpegEmbedder(xmp).embed(SyntheticMedia.jpegPlain(), listOf(note()), "kept")
        val x = inspect(out)!!
        assertEquals(ContainerType.JPEG, x.container)
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(1, x.records.size)
        assertTrue(x.records.single().crcOk)
        assertEquals("kept", x.xmpSummary?.description)
        assertEquals("kept", x.iptcCaption, "the IPTC mirror is surfaced as its own caption source")
        assertEquals(Survival.FULL, ContainerExtraction.classify(x, captionVisible = true))
    }

    @Test
    fun corruptRecordClassifiesDamaged() {
        val out = JpegEmbedder(xmp).embed(SyntheticMedia.jpegPlain(), listOf(note()), "kept")
        val hit = RecordStream.scan(out).single()
        out[hit.offset + EngramRecord.HEADER_LEN] = 0x7A // flip a payload byte, crc no longer holds
        val x = inspect(out)!!
        assertEquals(1, x.records.size)
        assertTrue(x.records.none { it.crcOk })
        assertEquals(Survival.DAMAGED, ContainerExtraction.classify(x, captionVisible = true))
    }

    @Test
    fun captionOnlyAndGoneDependOnTheCaption() {
        val captioned = inspect(SyntheticMedia.jpegWithXmp("desc=at the lake"))!!
        assertEquals("at the lake", captioned.xmpSummary?.description)
        assertEquals(Survival.CAPTION_ONLY, ContainerExtraction.classify(captioned, captionVisible = true))
        val plain = inspect(SyntheticMedia.jpegPlain())!!
        assertEquals(Survival.GONE, ContainerExtraction.classify(plain, captionVisible = false))
    }

    @Test
    fun unrecognizedBytesClassifyUnreadable() {
        assertNull(inspect(ByteArray(64) { 0x42 }))
        assertEquals(Survival.UNREADABLE, ContainerExtraction.classify(null, captionVisible = false))
    }

    @Test
    fun unparseableJpegIsUnreadableNotCarved() {
        // SOI followed by junk: the old whole-file carve would still hunt for frames here
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x33 }
        val x = inspect(bytes)!!
        assertIs<CarrierIntegrity.Unreadable>(x.integrity)
        assertEquals(Survival.UNREADABLE, ContainerExtraction.classify(x, captionVisible = false))
    }

    @Test
    fun frameHiddenInsideAMetadataSegmentIsNotARecord() {
        // a valid frame smuggled into an APP7 segment must not classify the file FULL:
        // records live in the post-EOI trailer, not anywhere magic bytes appear
        val parts = JpegCodec.parse(SyntheticMedia.jpegPlain()).toMutableList()
        parts.add(1, Segment.of(0xE7, note().encode()))
        val x = inspect(JpegCodec.serialize(parts))!!
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(0, x.records.size)
        assertEquals(Survival.GONE, ContainerExtraction.classify(x, captionVisible = false))
    }

    @Test
    fun malformedPngChunkIsCarrierDamage() {
        val out = PngEmbedder(xmp).embed(SyntheticMedia.png1x1(), listOf(note()), "kept")
        // append an egRm chunk whose frame has a trailing byte: engramRecords drops it,
        // so without the integrity signal the loss would be invisible
        val iendAt = out.size - 12
        val broken = PngChunk("egRm", note("lost").encode() + byteArrayOf(0)).encode()
        val bytes = out.copyOfRange(0, iendAt) + broken + out.copyOfRange(iendAt, out.size)
        val x = inspect(bytes)!!
        assertEquals(1, x.records.size, "the intact frame still reads")
        assertEquals(2, x.pngEngramChunks)
        assertIs<CarrierIntegrity.CarrierDamaged>(x.integrity)
        assertEquals(Survival.DAMAGED, ContainerExtraction.classify(x, captionVisible = true))
    }

    @Test
    fun undecodableTailInTheEngramBoxIsCarrierDamage() {
        val bytes =
            SyntheticMedia.mp4MoovLast() +
                Mp4Codec.buildEngramBox(note().encode() + byteArrayOf(9, 9, 9))
        val x = inspect(bytes)!!
        assertEquals(1, x.records.size)
        assertIs<CarrierIntegrity.CarrierDamaged>(x.integrity)
        assertEquals(Survival.DAMAGED, ContainerExtraction.classify(x, captionVisible = false))
    }

    @Test
    fun mp4WithRecordsClassifiesFull() {
        val bytes = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), listOf(note()))
        val x = inspect(bytes)!!
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(1, x.records.size)
        assertEquals(Survival.FULL, ContainerExtraction.classify(x, captionVisible = false))
    }

    @Test
    fun unparseableMp4IsUnreadable() {
        val b = ByteArrayBuilder()
        b.appendU32be(20).append("ftyp".encodeToByteArray())
        b.append("isom".encodeToByteArray()).appendU32be(0).append("isom".encodeToByteArray())
        b.appendU32be(9999).append("mdat".encodeToByteArray()) // claims far past EOF
        val x = inspect(b.toByteArray())!!
        assertIs<CarrierIntegrity.Unreadable>(x.integrity)
        assertEquals(Survival.UNREADABLE, ContainerExtraction.classify(x, captionVisible = false))
    }

    @Test
    fun rawFramesReturnsTheByteExactLogPerContainer() {
        val opaque = SyntheticMedia.unknownVersionFrame()
        val jpeg = JpegEmbedder(xmp).embed(SyntheticMedia.jpegPlain(), listOf(note()), null, listOf(opaque))
        val jpegFrames = ContainerExtraction.rawFrames(jpeg)
        assertEquals(2, jpegFrames.size)
        assertTrue(jpegFrames.any { it.contentEquals(opaque) }, "opaque frames ride byte-exact")
        assertEquals(
            1,
            ContainerExtraction.rawFrames(PngEmbedder(xmp).embed(SyntheticMedia.png1x1(), listOf(note()), null)).size,
        )
        assertEquals(
            1,
            ContainerExtraction.rawFrames(Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), listOf(note()))).size,
        )
        assertEquals(0, ContainerExtraction.rawFrames(ByteArray(32) { 0x42 }).size)
    }

    @Test
    fun corruptLengthFrameStillClassifiesDamagedAndKeepsSurvivor() {
        val real = note("survivor").encode()
        val bad = SyntheticMedia.frameWithInflatedLength(spanBeyond = real.size)
        // jpeg trailer, carve path
        val jpeg = inspect(SyntheticMedia.jpegPlain() + bad + real)!!
        assertEquals(Survival.DAMAGED, ContainerExtraction.classify(jpeg, captionVisible = false))
        assertTrue(
            jpeg.records.any { it.crcOk && it.record?.payload?.decodeToString() == "survivor" },
            "the intact record behind the corrupt length claim must still read",
        )
        // mp4 engram box, strict decode path
        val mp4 = inspect(SyntheticMedia.mp4MoovLast() + Mp4Codec.buildEngramBox(bad + real))!!
        assertEquals(Survival.DAMAGED, ContainerExtraction.classify(mp4, captionVisible = false))
        assertTrue(mp4.records.any { it.crcOk && it.record?.payload?.decodeToString() == "survivor" })
    }

    @Test
    fun opaqueFramesCountTowardFull() {
        val out =
            JpegEmbedder(xmp).embed(
                SyntheticMedia.jpegPlain(),
                listOf(note()),
                null,
                listOf(SyntheticMedia.unknownVersionFrame()),
            )
        val x = inspect(out)!!
        assertEquals(2, x.records.size)
        assertTrue(x.records.any { it.record == null && it.crcOk })
        assertEquals(Survival.FULL, ContainerExtraction.classify(x, captionVisible = false))
    }
}
