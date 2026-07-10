package cam.engram.format

import cam.engram.format.archive.ArchiveReader
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The reader must parse exactly what EngramArchive emits, escapes included. */
class ArchiveReaderTest {
    @Test
    fun manifestRoundTrips() {
        val json =
            EngramArchive.manifest(
                2,
                listOf(
                    EngramArchive.ManifestFile("a.json", "11aa"),
                    EngramArchive.ManifestFile("a.records", "22bb"),
                ),
            )
        val m = ArchiveReader.parseManifest(json)
        assertEquals(2, m.manifestVersion)
        assertEquals(2, m.itemCount)
        assertEquals(listOf("a.json", "a.records"), m.files.map { it.name })
        assertEquals(listOf("11aa", "22bb"), m.files.map { it.md5 })
    }

    @Test
    fun emptyInventoryParses() {
        val m = ArchiveReader.parseManifest(EngramArchive.manifest(0))
        assertEquals(0, m.itemCount)
        assertEquals(0, m.files.size)
    }

    @Test
    fun itemViewRoundTripsWithEscapesAndAudio() {
        val note = EngramRecord(RecordKind.Note, 10, "line1\nline2 \"quoted\"\t".encodeToByteArray())
        val audio = EngramRecord(RecordKind.Audio, 11, AudioPayload.encode("audio/ogg", ByteArray(8)))
        val rendered =
            EngramArchive.render(
                EngramArchive.Item(
                    "c0ffee",
                    "IMG \"1\".jpg",
                    listOf(note, audio),
                    listOf(note.encode(), audio.encode()),
                ),
            )
        val item = ArchiveReader.parseItem(rendered.json)
        assertEquals("c0ffee", item.contentHash)
        assertEquals("IMG \"1\".jpg", item.originalName)
        assertEquals("c0ffee.records", item.recordLog)
        assertEquals(2, item.frameCount)
        assertEquals(listOf("c0ffee_0.ogg"), item.audio)
    }

    @Test
    fun itemWithoutFramesHasNullLog() {
        val rendered = EngramArchive.render(EngramArchive.Item("dead", "x.jpg", emptyList()))
        val item = ArchiveReader.parseItem(rendered.json)
        assertNull(item.recordLog)
        assertEquals(0, item.frameCount)
    }

    @Test
    fun malformedDocumentsFailLoudly() {
        assertFailsWith<IllegalStateException> { ArchiveReader.parseManifest("{}") }
        assertFailsWith<IllegalStateException> { ArchiveReader.parseItem("{\"originalName\":\"x\"}") }
        assertFailsWith<IllegalStateException> { ArchiveReader.parseManifest("{\"manifestVersion\":2,") }
        assertFailsWith<IllegalArgumentException> { ArchiveReader.parseManifest("[]") }
    }
}
