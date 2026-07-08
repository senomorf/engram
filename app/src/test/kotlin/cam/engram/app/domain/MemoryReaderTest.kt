package cam.engram.app.domain

import cam.engram.app.FakeContentAccess
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.AudioPayload
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads the current record log back from a media file and interprets it as a Memory. */
class MemoryReaderTest {
    private val access = FakeContentAccess()
    private val reader = MemoryReader(access, Dispatchers.Unconfined)

    private fun records() =
        listOf(
            EngramRecord(RecordKind.Note, 1, "sunrise".encodeToByteArray()),
            EngramRecord(RecordKind.Audio, 2, AudioPayload.encode("audio/ogg", ByteArray(24) { 3 })),
        )

    private fun item(
        id: Long,
        isVideo: Boolean,
        mime: String,
    ) = MediaItemEntity(
        mediaId = id,
        uri = "content://media/$id",
        isVideo = isVideo,
        mime = mime,
        relativePath = "DCIM/Camera/",
        takenAtMillis = id,
        sizeBytes = 0,
        dateModified = id,
        recordCount = 0,
        payloadLength = 0,
        lastScanMillis = 0,
    )

    @Test
    fun readsNoteAndAudioFromJpeg() =
        runBlocking {
            access.files["content://media/1"] =
                JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegPlain(), records(), "sunrise")
            val memory = reader.read(item(1, isVideo = false, mime = "image/jpeg"))
            assertEquals("sunrise", memory.currentNote?.text)
            assertEquals(1, memory.audio.size)
            assertEquals("audio/ogg", memory.audio.single().mime)
        }

    @Test
    fun readsNoteFromPng() =
        runBlocking {
            access.files["content://media/2"] =
                PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), records(), "sunrise")
            val memory = reader.read(item(2, isVideo = false, mime = "image/png"))
            assertEquals("sunrise", memory.currentNote?.text)
            assertEquals(1, memory.audio.size)
        }

    @Test
    fun readsNoteFromVideo() =
        runBlocking {
            access.files["content://media/3"] =
                Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), records())
            val memory = reader.read(item(3, isVideo = true, mime = "video/mp4"))
            assertEquals("sunrise", memory.currentNote?.text)
            assertEquals(1, memory.audio.size)
        }

    @Test
    fun emptyMemoryWhenFileMissing() =
        runBlocking {
            val memory = reader.read(item(9, isVideo = false, mime = "image/jpeg"))
            assertNull(memory.currentNote)
            assertEquals(0, memory.audio.size)
        }
}
