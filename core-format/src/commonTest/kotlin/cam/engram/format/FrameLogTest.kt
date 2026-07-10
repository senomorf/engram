package cam.engram.format

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.FrameLog
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrameLogTest {
    private fun note(
        ts: Long,
        text: String,
        idByte: Int,
    ) = EngramRecord(RecordKind.Note, ts, text.encodeToByteArray(), ByteArray(16) { idByte.toByte() })

    @Test
    fun mergeAppendsOnlyMissingFramesInCacheOrder() {
        val a = note(1, "a", 1)
        val b = note(2, "b", 2)
        val c = note(3, "c", 3)
        val (merged, count) =
            FrameLog.mergeSuperset(
                RecordStream.encode(listOf(a, c)),
                2,
                RecordStream.encode(listOf(a, b)),
            )
        assertEquals(3, count)
        assertEquals(
            listOf(a.idHex, c.idHex, b.idHex),
            RecordStream.decodeSequence(merged).map { it.decoded.record!!.idHex },
        )
    }

    @Test
    fun identicalLogsMergeUnchanged() {
        val blob = RecordStream.encode(listOf(note(1, "a", 1), note(2, "b", 2)))
        val (merged, count) = FrameLog.mergeSuperset(blob, 2, blob)
        assertEquals(2, count)
        assertContentEquals(blob, merged)
    }

    @Test
    fun crcOkFramesSkipsCorruptFrames() {
        val a = note(1, "a", 1)
        val b = note(2, "b", 2)
        val blob = RecordStream.encode(listOf(a, b))
        blob[EngramRecord.HEADER_LEN + 2] = 0x7A // corrupt A's payload; its crc no longer holds
        val frames = FrameLog.crcOkFrames(blob)
        assertEquals(1, frames.size)
        assertEquals(b.idHex, EngramRecord.decodeAt(frames.single(), 0)!!.record!!.idHex)
    }

    @Test
    fun opaqueFramesParticipateWithoutDecoding() {
        val scanned = RecordStream.encode(listOf(note(1, "a", 1)))
        val cached = SyntheticMedia.unknownKindFrame() + SyntheticMedia.unknownVersionFrame()
        val (merged, count) = FrameLog.mergeSuperset(scanned, 1, cached)
        assertEquals(3, count)
        // both opaque frames ride along byte-exact at the end of the merged log
        assertContentEquals(scanned + cached, merged)
    }
}
