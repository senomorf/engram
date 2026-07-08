package photos.engram.format

import photos.engram.format.read.Memory
import photos.engram.format.records.AudioPayload
import photos.engram.format.records.EngramRecord
import photos.engram.format.records.RecordKind
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
}
