package cam.engram.format

import cam.engram.format.jpeg.EXTENDED_XMP_APP1_HEADER
import cam.engram.format.jpeg.ExtendedXmp
import cam.engram.format.jpeg.Iptc
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.jpeg.JpegFormatException
import cam.engram.format.jpeg.Segment
import cam.engram.format.jpeg.isMpfApp2
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JpegGuardsTest {
    private val record = listOf(EngramRecord(RecordKind.Note, 1, "n".encodeToByteArray()))

    @Test
    fun motionPhotoWriteRefused() {
        val src = SyntheticMedia.jpegWithXmp("camera=1\nGCamera MotionPhoto present")
        val e = assertFailsWith<JpegFormatException> { JpegEmbedder(FakeXmpEngine()).embed(src, record, "x") }
        assertTrue(e.message!!.contains("motion photo"), e.message)
    }

    @Test
    fun legacyMicroVideoAlsoRefused() {
        val src = SyntheticMedia.jpegWithXmp("MicroVideoOffset=1234")
        assertFailsWith<JpegFormatException> { JpegEmbedder(FakeXmpEngine()).embed(src, record, "x") }
    }

    @Test
    fun xmpAfterMpfRefused() {
        val src = SyntheticMedia.jpegWithMpfSecondary(xmpAfterMpf = "camera=1")
        val e = assertFailsWith<JpegFormatException> { JpegEmbedder(FakeXmpEngine()).embed(src, record, "x") }
        assertTrue(e.message!!.contains("after MPF"), e.message)
    }

    @Test
    fun extendedXmpGuidMismatchRefused() {
        // two segments carrying different guids must not reassemble into one packet (fail closed)
        val a = ExtendedXmp.buildSegments("A".repeat(100)).first()
        val b = ExtendedXmp.buildSegments("B".repeat(100)).first()
        val e = assertFailsWith<JpegFormatException> { ExtendedXmp.collect(listOf(a, b)) }
        assertTrue(e.message!!.contains("guid mismatch"), e.message)
    }

    @Test
    fun extendedXmpTruncatedSegmentRefused() {
        val tiny = Segment.of(JpegCodec.APP1, EXTENDED_XMP_APP1_HEADER + byteArrayOf(1, 2, 3))
        assertFailsWith<JpegFormatException> { ExtendedXmp.collect(listOf(tiny)) }
    }

    @Test
    fun extendedXmpLengthMismatchRefused() {
        val segs = ExtendedXmp.buildSegments("Y".repeat(70000)) // two segments, same guid
        val bad = segs[1].payload.copyOf()
        bad[EXTENDED_XMP_APP1_HEADER.size + 32] = 0x7F // alter the declared full length
        val e =
            assertFailsWith<JpegFormatException> {
                ExtendedXmp.collect(
                    listOf(segs[0], Segment.of(JpegCodec.APP1, bad)),
                )
            }
        assertTrue(e.message!!.contains("length mismatch"), e.message)
    }

    @Test
    fun extendedXmpChunkOverrunRefused() {
        val seg = ExtendedXmp.buildSegments("Z".repeat(100)).first()
        val bad = seg.payload.copyOf()
        // push the chunk offset so offset + chunk size exceeds the declared full length
        bad[EXTENDED_XMP_APP1_HEADER.size + 32 + 4 + 3] = 90
        val e = assertFailsWith<JpegFormatException> { ExtendedXmp.collect(listOf(Segment.of(JpegCodec.APP1, bad))) }
        assertTrue(e.message!!.contains("overruns"), e.message)
    }

    @Test
    fun extendedXmpIncompleteRefused() {
        // a packet that spans multiple segments must fail if any chunk is missing
        val segs = ExtendedXmp.buildSegments("X".repeat(70000))
        assertTrue(segs.size > 1, "fixture must span multiple segments")
        val e = assertFailsWith<JpegFormatException> { ExtendedXmp.collect(listOf(segs.first())) }
        assertTrue(e.message!!.contains("incomplete"), e.message)
    }

    @Test
    fun oversizeStandardXmpRefused() {
        // an engine that returns a packet larger than one APP1 segment must be rejected, not truncated
        val hugeEngine =
            object : cam.engram.format.xmp.XmpEngine {
                override fun apply(
                    existingStandard: String?,
                    existingExtended: String?,
                    update: cam.engram.format.xmp.XmpUpdate,
                    standardLimitBytes: Int,
                ) = cam.engram.format.xmp
                    .XmpApplyResult("A".repeat(70000), null)

                override fun read(packet: String) =
                    cam.engram.format.xmp
                        .XmpSummary(false, null, null, null, null, null)
            }
        assertFailsWith<JpegFormatException> {
            JpegEmbedder(hugeEngine).embed(SyntheticMedia.jpegPlain(), record, "x")
        }
    }

    @Test
    fun iptcAfterMpfRefused() {
        // a metadata segment after MPF would shift the MPF-referenced images, so writing must refuse
        val parts = JpegCodec.parse(SyntheticMedia.jpegWithMpfSecondary()).toMutableList()
        val mpfIdx = parts.indexOfFirst { it is Segment && it.isMpfApp2() }
        parts.add(mpfIdx + 1, Segment.of(Iptc.APP13_MARKER, Iptc.upsertCaption(null, "late")))
        val jpeg = JpegCodec.serialize(parts)
        val e = assertFailsWith<JpegFormatException> { JpegEmbedder(FakeXmpEngine()).embed(jpeg, record, null) }
        assertTrue(e.message!!.contains("after MPF"), e.message)
    }

    @Test
    fun progressiveDnlAndFillBytesRoundTrip() {
        for (fixture in listOf(
            SyntheticMedia.jpegProgressive(),
            SyntheticMedia.jpegWithDnl(),
            SyntheticMedia.jpegWithFillBytes(),
        )) {
            assertContentEquals(fixture, JpegCodec.serialize(JpegCodec.parse(fixture)))
        }
    }

    @Test
    fun embedSurvivesProgressiveAndFillFixtures() {
        for (fixture in listOf(SyntheticMedia.jpegProgressive(), SyntheticMedia.jpegWithFillBytes())) {
            val out = JpegEmbedder(FakeXmpEngine()).embed(fixture, record, "note")
            assertEquals(
                1,
                cam.engram.format.records.RecordStream
                    .scan(out)
                    .count { it.decoded.crcOk },
            )
        }
    }

    @Test
    fun iptcCaptionWrittenAndUpdated() {
        val once = JpegEmbedder(FakeXmpEngine()).embed(SyntheticMedia.jpegPlain(), record, "first caption")
        val seg1 = JpegCodec.parse(once).filterIsInstance<Segment>().first { Iptc.isIptcApp13(it) }
        assertEquals("first caption", Iptc.readCaption(seg1.payload))
        val twice =
            JpegEmbedder(FakeXmpEngine()).embed(
                once,
                listOf(EngramRecord(RecordKind.Note, 2, "m".encodeToByteArray())),
                "second caption",
            )
        val parsed = JpegCodec.parse(twice).filterIsInstance<Segment>().filter { Iptc.isIptcApp13(it) }
        assertEquals(1, parsed.size, "caption update must replace, not duplicate, APP13")
        assertEquals("second caption", Iptc.readCaption(parsed.single().payload))
    }
}
