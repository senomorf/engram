package cam.engram.format.mp4

import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp4ChannelsTest {
    @Test
    fun readRawFramesReturnsCrcOkFrames() {
        val out =
            Mp4Codec.embed(
                SyntheticMedia.mp4MoovLast(),
                listOf(EngramRecord(RecordKind.Note, 1, "a".encodeToByteArray())),
                listOf(SyntheticMedia.unknownKindFrame()),
            )
        val path = Files.createTempFile("engram", ".mp4")
        path.writeBytes(out)
        Files.newByteChannel(path).use { ch ->
            val frames = Mp4Channels.readRawFrames(ch)
            assertEquals(2, frames.size)
            assertTrue(frames.any { EngramRecord.decodeAt(it, 0)?.record == null }, "unknown-kind frame preserved")
        }
    }

    @Test
    fun readsRecordsAndMoovThroughChannel() {
        val withCaption = Mp4Caption.tryWrite(SyntheticMedia.mp4MoovLast(), "channel caption")!!
        val out = Mp4Codec.embed(withCaption, listOf(EngramRecord(RecordKind.Note, 7, "ch".encodeToByteArray())))
        val path = Files.createTempFile("engram", ".mp4")
        path.writeBytes(out)
        Files.newByteChannel(path).use { ch ->
            assertEquals(listOf("ftyp", "mdat", "moov", "uuid"), Mp4Channels.topLevel(ch).map { it.type })
            val hits = Mp4Channels.readRecords(ch)
            assertEquals(1, hits.size)
            assertEquals(
                "ch",
                hits
                    .single()
                    .decoded.record!!
                    .payload
                    .decodeToString(),
            )
            val moov = assertNotNull(Mp4Channels.readMoovBox(ch))
            assertEquals("channel caption", Mp4Caption.readCaptionFromMoovBox(moov))
        }
    }
}
