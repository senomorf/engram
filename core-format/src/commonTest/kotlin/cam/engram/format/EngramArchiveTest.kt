package cam.engram.format

import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.EnrichmentPayload
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
    fun rendersMp4AudioEnrichmentAndNoNote() {
        val item =
            EngramArchive.Item(
                contentHashHex = "deadbeef",
                originalName = "clip.mp4",
                records =
                    listOf(
                        EngramRecord(RecordKind.Audio, 5, AudioPayload.encode("audio/mp4", ByteArray(8))),
                        EngramRecord(
                            RecordKind.Enrichment,
                            6,
                            EnrichmentPayload(mapOf("place" to "Bay\tside")).encode(),
                        ),
                        EngramRecord(RecordKind.Transcript, 7, "line1\nline2".encodeToByteArray()),
                    ),
            )
        val rendered = EngramArchive.render(item)
        assertEquals("deadbeef_0.m4a", rendered.audio.single().fileName) // mp4 mime -> m4a extension
        assertTrue(rendered.json.contains("\"currentNote\":null"), rendered.json) // no note record
        assertTrue(rendered.json.contains("\"enrichment\":{\"place\":\"Bay\\tside\"}"), rendered.json) // tab escaped
        assertTrue(rendered.json.contains("line1\\nline2"), rendered.json) // newline escaped
    }

    @Test
    fun quoteEscapesCarriageReturnAndLowControlChars() {
        // carriage return and a sub-0x20 control char take the \r and \uXXXX branches of quote()
        val transcript = "a" + 13.toChar() + "b" + 1.toChar() + "c"
        val item =
            EngramArchive.Item(
                contentHashHex = "c0ffee",
                originalName = "x.jpg",
                records = listOf(EngramRecord(RecordKind.Transcript, 1, transcript.encodeToByteArray())),
            )
        val json = EngramArchive.render(item).json
        val bs = 92.toChar() // backslash, built from its code to keep no backslash literals in the fixture
        assertTrue(json.contains("a${bs}rb${bs}u0001c"), json)
    }

    @Test
    fun manifestIsValidJson() {
        assertEquals(
            "{\"archive\":\"engram\",\"manifestVersion\":1,\"itemCount\":3}",
            EngramArchive.manifest(3),
        )
    }

    @Test
    fun contentHashNameDistinguishesSameSizeSamePrefixFiles() {
        // the old size+8-byte scheme collided here; a real digest must not (review F13)
        val prefix = ByteArray(8) { 1 }
        val a = prefix + ByteArray(24) { 0 }
        val b = prefix + ByteArray(24) { 2 }
        assertEquals(a.size, b.size)
        assertTrue(EngramArchive.contentHashName(a) != EngramArchive.contentHashName(b))
    }
}
