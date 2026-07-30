package cam.engram.format

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.records.ScanBudget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun hostilePayloadLengthRejectedNotCrashed() {
        // a payloadLen near Int.MAX_VALUE used to wrap payloadEnd negative and index
        // out of bounds; a hostile header must yield null, never throw
        val frame = EngramRecord(RecordKind.Note, 1, "x".encodeToByteArray()).encode()
        val payloadLenAt = EngramRecord.HEADER_LEN - 4
        frame[payloadLenAt] = 0x7F
        frame[payloadLenAt + 1] = 0xFF.toByte()
        frame[payloadLenAt + 2] = 0xFF.toByte()
        frame[payloadLenAt + 3] = 0xF0.toByte()
        assertNull(EngramRecord.decodeAt(frame, 0))
    }

    // review N8: a region dense in `EGRM` bytes makes the byte-wise carve validate a full
    // claimed span per candidate, so CRC work grows quadratically. A budgeted carve stops
    // once the work cap is spent and reports it, instead of burning background CPU.
    @Test
    fun hostileMagicFloodExhaustsBudgetInsteadOfQuadraticWork() {
        // back-to-back headers, each claiming a payload that spans to the region's end: every
        // candidate costs a full-region CRC, so the unbudgeted carve is quadratic
        val size = 200_000
        val hostile = ByteArray(size)
        var at = 0
        while (at + EngramRecord.HEADER_LEN + 4 <= size) {
            EngramRecord.MAGIC.copyInto(hostile, at)
            hostile[at + 4] = EngramRecord.WIRE_VERSION.toByte()
            hostile[at + 5] = RecordKind.Note.code.toByte()
            // writerLen (at + 32) stays 0, so payloadLen sits at at + 33
            val claimed = size - at - EngramRecord.HEADER_LEN - 4
            hostile[at + 33] = (claimed ushr 24).toByte()
            hostile[at + 34] = (claimed ushr 16).toByte()
            hostile[at + 35] = (claimed ushr 8).toByte()
            hostile[at + 36] = claimed.toByte()
            at += EngramRecord.HEADER_LEN
        }
        val cap = 4L * size // one budgeted pass is a small multiple of the region, not size^2
        val budget = ScanBudget(cap)

        val hits = RecordStream.scan(hostile, budget = budget)

        assertTrue(budget.exhausted, "a magic flood must exhaust the carve budget")
        assertTrue(budget.spentBytes <= cap + size, "work stays bounded by the cap: ${budget.spentBytes}")
        assertTrue(hits.none { it.decoded.crcOk }, "no real record exists in the flood")
    }

    // the budget must never change what an honest carve finds
    @Test
    fun budgetedCarveStillFindsEveryRealRecord() {
        val a = EngramRecord(RecordKind.Note, 1, "one".encodeToByteArray())
        val b = EngramRecord(RecordKind.Note, 2, "two".encodeToByteArray())
        val bytes = ByteArray(9) { 0x2A } + a.encode() + ByteArray(5) { 0x2A } + b.encode()

        val budgeted = RecordStream.scan(bytes, budget = ScanBudget(1L * 1024 * 1024))
        val unbudgeted = RecordStream.scan(bytes)

        assertEquals(unbudgeted.map { it.offset }, budgeted.map { it.offset })
        assertEquals(2, budgeted.count { it.decoded.crcOk })
    }

    @Test
    fun unsignedPayloadLengthAboveIntMaxRejected() {
        val frame = EngramRecord(RecordKind.Note, 1, "x".encodeToByteArray()).encode()
        val payloadLenAt = EngramRecord.HEADER_LEN - 4
        frame[payloadLenAt] = 0xFF.toByte()
        frame[payloadLenAt + 1] = 0xFF.toByte()
        frame[payloadLenAt + 2] = 0xFF.toByte()
        frame[payloadLenAt + 3] = 0xFE.toByte()
        assertNull(EngramRecord.decodeAt(frame, 0))
    }
}
