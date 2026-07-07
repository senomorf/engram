package photos.engram.format

import photos.engram.format.png.PngCodec
import photos.engram.format.png.PngEmbedder
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngTest {

    @Test
    fun parseRoundTrip() {
        val src = SyntheticMedia.png1x1()
        assertContentEquals(src, PngCodec.serialize(PngCodec.parse(src)))
    }

    @Test
    fun embedAddsXmpAndRecords() {
        val out = PngEmbedder(FakeXmpEngine()).embed(
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
        val once = embedder.embed(
            SyntheticMedia.png1x1(),
            listOf(EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray())),
            "a",
        )
        val twice = embedder.embed(
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
