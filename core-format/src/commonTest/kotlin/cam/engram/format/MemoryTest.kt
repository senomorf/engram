package cam.engram.format

import cam.engram.format.read.Memory
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.DecodedRecord
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.EnrichmentPayload
import cam.engram.format.records.RecordHit
import cam.engram.format.records.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryTest {
    @Test
    fun latestNoteWinsAndHistoryKept() {
        val memory =
            Memory.fromRecords(
                listOf(
                    EngramRecord(RecordKind.Note, 10, "first".encodeToByteArray()),
                    EngramRecord(RecordKind.Note, 30, "third".encodeToByteArray()),
                    EngramRecord(RecordKind.Note, 20, "second".encodeToByteArray()),
                    EngramRecord(RecordKind.Audio, 15, AudioPayload.encode("audio/ogg", ByteArray(8))),
                    EngramRecord(RecordKind.Transcript, 16, "transcript words".encodeToByteArray()),
                ),
            )
        assertEquals("third", memory.currentNote!!.text)
        assertEquals(listOf("third", "second", "first"), memory.noteHistory.map { it.text })
        assertEquals(1, memory.audio.size)
        assertEquals("audio/ogg", memory.audio.single().mime)
        assertEquals("third\nsecond\nfirst\ntranscript words", memory.searchableText())
    }

    @Test
    fun emptyLogHasNoCurrentNote() {
        val memory = Memory.fromRecords(emptyList())
        assertEquals(null, memory.currentNote)
        assertEquals("", memory.searchableText())
    }

    @Test
    fun equalTimestampNotesResolveDeterministicallyByIdHex() {
        val a = EngramRecord(RecordKind.Note, 100, "alpha".encodeToByteArray(), ByteArray(16) { 0x0A })
        val b = EngramRecord(RecordKind.Note, 100, "bravo".encodeToByteArray(), ByteArray(16) { 0x0B })
        val oneWay = Memory.fromRecords(listOf(a, b))
        val otherWay = Memory.fromRecords(listOf(b, a))
        assertEquals("bravo", oneWay.currentNote!!.text, "the higher idHex wins a same-millisecond tie")
        assertEquals(oneWay.currentNote!!.idHex, otherWay.currentNote!!.idHex)
        assertEquals(oneWay.noteHistory.map { it.idHex }, otherWay.noteHistory.map { it.idHex })
    }

    @Test
    fun fromHitsKeepsLatestEnrichment() {
        val records =
            listOf(
                EngramRecord(RecordKind.Enrichment, 5, EnrichmentPayload(mapOf("place" to "old town")).encode()),
                EngramRecord(RecordKind.Enrichment, 9, EnrichmentPayload(mapOf("place" to "new town")).encode()),
                EngramRecord(RecordKind.Note, 1, "hello".encodeToByteArray()),
            )
        val hits = records.map { RecordHit(0, DecodedRecord(it, it.kind.code, 0, crcOk = true, idHex = it.idHex)) }
        val memory = Memory.from(hits)
        assertEquals("new town", memory.enrichment["place"]) // higher-ts enrichment wins
        assertEquals("hello", memory.currentNote?.text)
    }
}
