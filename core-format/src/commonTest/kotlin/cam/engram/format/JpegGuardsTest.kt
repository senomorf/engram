package cam.engram.format

import cam.engram.format.jpeg.Iptc
import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.jpeg.JpegFormatException
import cam.engram.format.jpeg.Segment
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
