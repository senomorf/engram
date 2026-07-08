package cam.engram.format

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordsFrameTest {
    @Test
    fun idAndWriterRoundTrip() {
        val id = ByteArray(EngramRecord.ID_LENGTH) { (it + 1).toByte() }
        val rec = EngramRecord(RecordKind.Note, 42, "text".encodeToByteArray(), id, "pixel-9/anna")
        val decoded = EngramRecord.decodeAt(rec.encode(), 0)!!
        assertTrue(decoded.crcOk)
        val r = decoded.record!!
        assertContentEquals(id, r.id)
        assertEquals("pixel-9/anna", r.writer)
        assertEquals(42, r.tsMillis)
        assertEquals("text", r.payload.decodeToString())
        assertEquals(r.idHex, id.toHex())
    }

    @Test
    fun decodeRespectsLimit() {
        val r1 = EngramRecord(RecordKind.Note, 1, "one".encodeToByteArray())
        val r2 = EngramRecord(RecordKind.Note, 2, "two".encodeToByteArray())
        val bytes = RecordStream.encode(listOf(r1, r2))
        val r1len = r1.encode().size
        // a limit cutting into the second record must not leak it through
        val hits = RecordStream.decodeSequence(bytes, 0, r1len + 10)
        assertEquals(1, hits.size)
        assertEquals(
            "one",
            hits
                .single()
                .decoded.record!!
                .payload
                .decodeToString(),
        )
        assertEquals(2, RecordStream.decodeSequence(bytes).size)
    }

    @Test
    fun emptyWriterHeaderLengthMatchesConstant() {
        val rec = EngramRecord(RecordKind.Note, 0, ByteArray(0))
        assertEquals(EngramRecord.HEADER_LEN + 4, rec.encode().size)
    }
}
