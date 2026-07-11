package cam.engram.format

import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsTest {
    private fun sample() =
        listOf(
            EngramRecord(RecordKind.Note, 1720000000000, "hello".encodeToByteArray()),
            EngramRecord(
                RecordKind.Audio,
                1720000000001,
                AudioPayload.encode("audio/ogg", ByteArray(32) { it.toByte() }),
            ),
        )

    @Test
    fun roundTrip() {
        val bytes = RecordStream.encode(sample())
        val hits = RecordStream.decodeSequence(bytes)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.decoded.crcOk })
        assertEquals(
            "hello",
            hits[0]
                .decoded.record!!
                .payload
                .decodeToString(),
        )
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

    @Test
    fun unknownVersionFrameSurfacesOpaque() {
        val frame = SyntheticMedia.unknownVersionFrame()
        val d = EngramRecord.decodeAt(frame, 0)
        assertNotNull(d, "a future wire version must decode as an opaque frame, not vanish")
        assertNull(d.record)
        assertTrue(d.crcOk)
        assertEquals(frame.size, d.byteLength)
        assertEquals(2, d.version)
    }

    @Test
    fun decodeSequenceCarriesUnknownVersionFramesMidStream() {
        val bytes =
            EngramRecord(RecordKind.Note, 1, "before".encodeToByteArray()).encode() +
                SyntheticMedia.unknownVersionFrame() +
                EngramRecord(RecordKind.Note, 2, "after".encodeToByteArray()).encode()
        val hits = RecordStream.decodeSequence(bytes)
        assertEquals(3, hits.size, "an unknown-version frame must not truncate the sequence")
        assertEquals(
            "after",
            hits[2]
                .decoded.record!!
                .payload
                .decodeToString(),
        )
    }

    @Test
    fun carveCarriesCrcValidUnknownVersionFrames() {
        val junk = ByteArray(7) { 0x55 }
        val bytes = junk + SyntheticMedia.unknownVersionFrame() + junk + RecordStream.encode(sample())
        val hits = RecordStream.scan(bytes)
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.decoded.crcOk })
        assertEquals(2, hits.first().decoded.version)
    }

    @Test
    fun carveDoesNotTrustCrcBadUnknownVersionSpans() {
        // a structurally plausible future-version candidate whose payload length claims
        // past the real frame behind it; its crc cannot hold, so the carver must not
        // skip the claimed span (that would swallow the real record)
        val real = EngramRecord(RecordKind.Note, 9, "survivor".encodeToByteArray()).encode()
        val bad = SyntheticMedia.unknownVersionFrame()
        val payloadLenAt = EngramRecord.HEADER_LEN - 4 // empty writer: payloadLen right after the fixed header
        val claimed = bad.size + real.size - payloadLenAt - 8 // claimed payload end + crc land at the buffer end
        bad[payloadLenAt] = (claimed ushr 24 and 0xFF).toByte()
        bad[payloadLenAt + 1] = (claimed ushr 16 and 0xFF).toByte()
        bad[payloadLenAt + 2] = (claimed ushr 8 and 0xFF).toByte()
        bad[payloadLenAt + 3] = (claimed and 0xFF).toByte()
        val hits = RecordStream.scan(bad + real)
        assertEquals(
            "survivor",
            hits
                .mapNotNull { it.decoded.record }
                .single()
                .payload
                .decodeToString(),
            "the real record inside the bogus claimed span must still be found",
        )
    }

    @Test
    fun carveDoesNotTrustCrcBadCurrentVersionSpans() {
        // same trap as the unknown-version case above, but the frame is ours (version 1):
        // the crc covers the length field, so once the crc fails the claimed span has no
        // authority and the intact frame behind it must still be found
        val real = EngramRecord(RecordKind.Note, 9, "survivor".encodeToByteArray()).encode()
        val bad = SyntheticMedia.frameWithInflatedLength(spanBeyond = real.size)
        val hits = RecordStream.scan(bad + real)
        val head = hits.first()
        assertEquals(0, head.offset)
        assertFalse(head.decoded.crcOk, "the damaged head must stay visible for classify/verify")
        assertEquals(1, head.decoded.version)
        assertEquals(
            "survivor",
            hits
                .filter { it.decoded.crcOk }
                .single()
                .decoded.record!!
                .payload
                .decodeToString(),
            "the real record inside the bogus claimed span must still be found",
        )
    }

    @Test
    fun decodeSequenceRecoversFramesAfterCrcBadLength() {
        val real = EngramRecord(RecordKind.Note, 9, "survivor".encodeToByteArray()).encode()
        val bad = SyntheticMedia.frameWithInflatedLength(spanBeyond = real.size)
        val hits = RecordStream.decodeSequence(bad + real)
        assertEquals(2, hits.size, "a crc-bad length claim must not hide the frame behind it")
        assertFalse(hits[0].decoded.crcOk)
        assertTrue(hits[1].decoded.crcOk)
        assertEquals(
            "survivor",
            hits[1]
                .decoded.record!!
                .payload
                .decodeToString(),
        )
    }
}
