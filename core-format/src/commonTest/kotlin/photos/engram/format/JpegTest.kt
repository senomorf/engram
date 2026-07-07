package photos.engram.format

import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.JpegEmbedder
import photos.engram.format.jpeg.MpfInspector
import photos.engram.format.jpeg.Segment
import photos.engram.format.jpeg.TrailerData
import photos.engram.format.jpeg.isMpfApp2
import photos.engram.format.jpeg.isXmpApp1
import photos.engram.format.jpeg.xmpPacket
import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.records.RecordStream
import photos.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegTest {
    @Test
    fun parseRoundTripIsByteIdentical() {
        for (fixture in listOf(SyntheticMedia.jpegPlain(), SyntheticMedia.jpegWithMpfSecondary())) {
            assertContentEquals(fixture, JpegCodec.serialize(JpegCodec.parse(fixture)))
        }
    }

    @Test
    fun mpfFixtureIsValid() {
        val report = MpfInspector.inspect(SyntheticMedia.jpegWithMpfSecondary())
        assertTrue(report.present)
        assertTrue(report.valid, report.problems.toString())
        assertEquals(2, report.images.size)
    }

    @Test
    fun embedPreservesMpfAndAddsRecords() {
        val src = SyntheticMedia.jpegWithMpfSecondary()
        val records =
            listOf(
                EngramRecord(RecordKind.Note, 5, "sunrise".encodeToByteArray()),
                EngramRecord(RecordKind.Audio, 6, AudioPayload.encode("audio/ogg", ByteArray(128) { 1 })),
            )
        val out = JpegEmbedder(FakeXmpEngine()).embed(src, records, "sunrise")
        assertTrue(MpfInspector.inspect(out).valid, MpfInspector.inspect(out).problems.toString())
        val parts = JpegCodec.parse(out)
        val xmpIdx = parts.indexOfFirst { it is Segment && it.isXmpApp1() }
        val mpfIdx = parts.indexOfFirst { it is Segment && it.isMpfApp2() }
        assertTrue(xmpIdx in 1 until mpfIdx, "xmp at $xmpIdx must precede mpf at $mpfIdx")
        val hits = parts.filterIsInstance<TrailerData>().flatMap { RecordStream.scan(it.raw) }
        assertEquals(2, hits.count { it.decoded.crcOk })
        val summary =
            FakeXmpEngine().read(
                parts.filterIsInstance<Segment>().first { it.isXmpApp1() }.xmpPacket(),
            )
        assertEquals("sunrise", summary.description)
        assertEquals(2, summary.recordCount)
    }

    @Test
    fun secondEmbedAccumulates() {
        val first =
            JpegEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.jpegPlain(),
                listOf(EngramRecord(RecordKind.Note, 1, "one".encodeToByteArray())),
                "one",
            )
        val second =
            JpegEmbedder(FakeXmpEngine()).embed(
                first,
                listOf(EngramRecord(RecordKind.Note, 2, "two".encodeToByteArray())),
                "two",
            )
        val hits = RecordStream.scan(second).filter { it.decoded.crcOk }
        assertEquals(2, hits.size)
        val packet = JpegCodec.parse(second).filterIsInstance<Segment>().first { it.isXmpApp1() }.xmpPacket()
        val s = FakeXmpEngine().read(packet)
        assertEquals(2, s.recordCount)
        assertEquals("two", s.description)
        assertEquals(hits.sumOf { it.decoded.byteLength.toLong() }, s.payloadLength)
    }

    @Test
    fun recordsLandAfterMpfSecondaryImage() {
        val out =
            JpegEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.jpegWithMpfSecondary(),
                listOf(EngramRecord(RecordKind.Note, 1, "x".encodeToByteArray())),
                null,
            )
        val secondaryAt = MpfInspector.inspect(out).images[1].absoluteOffset!!
        val hit = RecordStream.scan(out).single()
        assertTrue(hit.offset > secondaryAt.toInt(), "records must not displace the gain map")
    }
}
