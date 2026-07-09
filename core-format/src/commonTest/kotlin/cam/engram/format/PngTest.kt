package cam.engram.format

import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngTest {
    @Test
    fun carryFramesPreserveUnknownKindRecords() {
        val out =
            PngEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.png1x1(),
                listOf(EngramRecord(RecordKind.Note, 1, "n".encodeToByteArray())),
                null,
                listOf(SyntheticMedia.unknownKindFrame()),
            )
        val decoded = PngCodec.engramRecords(PngCodec.parse(out))
        assertEquals(2, decoded.size)
        assertTrue(decoded.any { it.crcOk && it.record == null }, "unknown kind preserved as an egRm chunk")
    }

    @Test
    fun engramFramesReturnsRawCrcOkFrames() {
        val out =
            PngEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.png1x1(),
                listOf(EngramRecord(RecordKind.Note, 1, "n".encodeToByteArray())),
                null,
            )
        val frames = PngCodec.engramFrames(PngCodec.parse(out))
        assertEquals(1, frames.size)
        assertTrue(EngramRecord.decodeAt(frames.single(), 0)?.crcOk == true)
    }

    @Test
    fun parseRoundTrip() {
        val src = SyntheticMedia.png1x1()
        assertContentEquals(src, PngCodec.serialize(PngCodec.parse(src)))
    }

    @Test
    fun embedAddsXmpAndRecords() {
        val out =
            PngEmbedder(FakeXmpEngine()).embed(
                SyntheticMedia.png1x1(),
                listOf(EngramRecord(RecordKind.Note, 3, "screen".encodeToByteArray())),
                "screen",
            )
        val file = PngCodec.parse(out)
        assertTrue(file.chunks.all { it.crcOk })
        assertEquals(listOf("IHDR", "iTXt", "IDAT", "egRm", "IEND"), file.chunks.map { it.type })
        val packet = file.chunks.firstNotNullOf { PngCodec.xmpPacket(it) }
        assertEquals("screen", FakeXmpEngine().read(packet).description)
        val rec = PngCodec.engramRecords(file).single()
        assertTrue(rec.crcOk)
        assertEquals("screen", rec.record!!.payload.decodeToString())
    }

    @Test
    fun secondEmbedAccumulates() {
        val embedder = PngEmbedder(FakeXmpEngine())
        val once =
            embedder.embed(
                SyntheticMedia.png1x1(),
                listOf(EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray())),
                "a",
            )
        val twice =
            embedder.embed(
                once,
                listOf(EngramRecord(RecordKind.Note, 2, "b".encodeToByteArray())),
                "b",
            )
        val file = PngCodec.parse(twice)
        assertEquals(2, PngCodec.engramRecords(file).count { it.crcOk })
        val summary = FakeXmpEngine().read(file.chunks.firstNotNullOf { PngCodec.xmpPacket(it) })
        assertEquals(2, summary.recordCount)
        assertEquals("b", summary.description)
    }
}
