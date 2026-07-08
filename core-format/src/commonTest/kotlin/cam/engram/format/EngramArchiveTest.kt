package cam.engram.format

import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngramArchiveTest {
    @Test
    fun rendersNotesHistoryAndAudioBlobs() {
        val item =
            EngramArchive.Item(
                contentHashHex = "abc123",
                originalName = "IMG_1.jpg",
                records =
                    listOf(
                        EngramRecord(RecordKind.Note, 10, "first".encodeToByteArray()),
                        EngramRecord(RecordKind.Note, 20, "final \"quoted\"".encodeToByteArray()),
                        EngramRecord(RecordKind.Audio, 15, AudioPayload.encode("audio/ogg", ByteArray(16))),
                    ),
            )
        val rendered = EngramArchive.render(item)
        assertTrue(rendered.json.contains("\"contentHash\":\"abc123\""))
        assertTrue(rendered.json.contains("\"currentNote\":\"final \\\"quoted\\\"\""), rendered.json)
        assertTrue(rendered.json.contains("\"originalName\":\"IMG_1.jpg\""))
        assertEquals(1, rendered.audio.size)
        assertEquals("abc123_0.ogg", rendered.audio.single().fileName)
        assertTrue(rendered.json.contains("abc123_0.ogg"))
    }

    @Test
    fun manifestIsValidJson() {
        assertEquals(
            "{\"archive\":\"engram\",\"manifestVersion\":1,\"itemCount\":3}",
            EngramArchive.manifest(3),
        )
    }
}
