package photos.engram.format

import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
import photos.engram.format.records.RecordStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordsTest {

    private fun sample() = listOf(
        EngramRecord(RecordKind.Note, 1720000000000, "hello".encodeToByteArray()),
        EngramRecord(RecordKind.Audio, 1720000000001, AudioPayload.encode("audio/ogg", ByteArray(32) { it.toByte() })),
    )

    @Test
    fun roundTrip() {
        val bytes = RecordStream.encode(sample())
        val hits = RecordStream.decodeSequence(bytes)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.decoded.crcOk })
        assertEquals("hello", hits[0].decoded.record!!.payload.decodeToString())
        assertEquals(1720000000000, hits[0].decoded.record!!.tsMillis)
        val audio = AudioPayload.decode(hits[1].decoded.record!!.payload)!!
        assertEquals("audio/ogg", audio.first)
        assertEquals(32, audio.second.size)
    }

    @Test
    fun corruptionDetected() {
        val bytes = RecordStream.encode(sample())
        bytes[EngramRecord.HEADER_LEN + 2] = 0x7A
        val hits = RecordStream.decodeSequence(bytes)
        assertEquals(2, hits.size)
        assertFalse(hits[0].decoded.crcOk)
        assertTrue(hits[1].decoded.crcOk)
    }

    @Test
    fun carveSkipsForeignBytes() {
        val junk = ByteArray(7) { 0x55 }
        val hits = RecordStream.scan(junk + RecordStream.encode(sample()) + junk)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.decoded.crcOk })
    }
}
