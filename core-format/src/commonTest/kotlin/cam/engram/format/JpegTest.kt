package cam.engram.format

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.jpeg.MpfInspector
import cam.engram.format.jpeg.Segment
import cam.engram.format.jpeg.TrailerData
import cam.engram.format.jpeg.isMpfApp2
import cam.engram.format.jpeg.isXmpApp1
import cam.engram.format.jpeg.xmpPacket
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegTest {
    @Test
    fun carryFramesPreserveUnknownKindRecords() {
        val out =
            JpegEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.jpegPlain(),
                listOf(EngramRecord(RecordKind.Note, 1, "note".encodeToByteArray())),
                "note",
                listOf(SyntheticMedia.unknownKindFrame()),
            )
        val hits = RecordStream.scan(out).filter { it.decoded.crcOk }
        assertEquals(2, hits.size, "the note and the carried unknown frame both survive")
        assertTrue(hits.any { it.decoded.record == null }, "the unknown-kind frame is preserved verbatim")
    }

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
    fun embedUpdatesMpfPrimarySize() {
        val src = SyntheticMedia.jpegWithMpfSecondary()
        val srcPrimary = MpfInspector.inspect(src).images[0].sizeBytes
        val out =
            JpegEmbedder(FakeXmpEngine()).embed(
                src,
                listOf(EngramRecord(RecordKind.Note, 1, "n".encodeToByteArray())),
                "mirror",
            )
        val report = MpfInspector.inspect(out)
        // inserting XMP + IPTC before the MPF grows the primary; its MP entry size must follow,
        // else inspect() flags the stale size and the write is not Ultra HDR safe (finding 3)
        assertTrue(report.images[0].sizeBytes > srcPrimary, "primary size must grow with the metadata")
        assertTrue(report.valid, "patched primary size keeps the MPF valid: ${report.problems}")
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
        val packet =
            JpegCodec
                .parse(second)
                .filterIsInstance<Segment>()
                .first { it.isXmpApp1() }
                .xmpPacket()
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
