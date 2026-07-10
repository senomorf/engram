package cam.engram.format

import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.EnrichmentPayload
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngramArchiveTest {
    @Test
    fun recordLogCarriesEveryFrameByteExact() {
        val note = EngramRecord(RecordKind.Note, 10, "kept".encodeToByteArray())
        val frames = listOf(note.encode(), SyntheticMedia.unknownKindFrame(), SyntheticMedia.unknownVersionFrame())
        val rendered = EngramArchive.render(EngramArchive.Item("abc123", "IMG_1.jpg", listOf(note), frames))
        val log = rendered.recordLog!!
        assertEquals("abc123.records", rendered.recordLogName)
        // the sidecar is the concatenated wire frames, byte for byte
        assertContentEquals(frames[0] + frames[1] + frames[2], log)
        val hits = RecordStream.decodeSequence(log)
        assertEquals(3, hits.size, "the log round-trips through the ordinary decoder")
        assertEquals(2, hits.count { it.decoded.record == null }, "opaque frames survive")
        assertTrue(rendered.json.contains("\"recordLog\":\"abc123.records\""), rendered.json)
        assertTrue(rendered.json.contains("\"frameCount\":3"), rendered.json)
    }

    @Test
    fun manifestListsTheInventoryWithHashes() {
        val manifest =
            EngramArchive.manifest(
                1,
                listOf(
                    EngramArchive.ManifestFile("a.json", "11aa"),
                    EngramArchive.ManifestFile("a.records", "22bb"),
                ),
            )
        assertTrue(manifest.contains("\"manifestVersion\":2"), manifest)
        assertTrue(manifest.contains("""{"name":"a.json","md5":"11aa"}"""), manifest)
        assertTrue(manifest.contains("""{"name":"a.records","md5":"22bb"}"""), manifest)
    }

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
            "{\"archive\":\"engram\",\"manifestVersion\":2,\"itemCount\":3,\"files\":[]}",
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
