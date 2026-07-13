package cam.engram.cli

import cam.engram.format.archive.EngramArchive
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.read.ContainerExtraction
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveCliTest {
    private val dir = createTempDirectory("engram-archive").toFile()

    @Test
    fun archiveWritesJsonAndAudio() {
        val src = File(dir, "src.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val audio = File(dir, "note.ogg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val out = File(dir, "out.jpg")
        assertEquals(
            0,
            cliMain(
                arrayOf(
                    "generate",
                    "--in",
                    src.path,
                    "--out",
                    out.path,
                    "--note",
                    "archived memory",
                    "--audio",
                    audio.path,
                ),
            ),
        )
        val archiveDir = File(dir, "archive")
        assertEquals(0, cliMain(arrayOf("archive", "--in", out.path, "--out", archiveDir.path)))
        assertTrue(File(archiveDir, "manifest.json").isFile)
        val json = archiveDir.listFiles { f -> f.name.endsWith(".json") && f.name != "manifest.json" }!!.single()
        assertTrue(json.readText().contains("archived memory"))
        assertTrue(archiveDir.listFiles { f -> f.name.endsWith(".ogg") }!!.isNotEmpty())
        // the byte-exact record log rides beside the JSON view and round-trips
        val log = archiveDir.listFiles { f -> f.name.endsWith(".records") }!!.single()
        assertEquals(
            2,
            cam.engram.format.records.RecordStream
                .decodeSequence(log.readBytes())
                .size,
            "note + audio frames decode from the sidecar",
        )
        val manifest = File(archiveDir, "manifest.json").readText()
        assertTrue(manifest.contains("\"manifestVersion\":3"), manifest)
        assertTrue(manifest.contains(log.name), "manifest inventories the record log: $manifest")

        // export-import identity: the validator proves the archive complete, and the
        // sidecar decodes to exactly the frames the source file carries
        assertEquals(0, cliMain(arrayOf("archive", "validate", "--in", archiveDir.path)))
        val sourceFrames =
            cam.engram.format.read.ContainerExtraction
                .rawFrames(out.readBytes())
        assertEquals(
            sourceFrames.fold(ByteArray(0)) { acc, f -> acc + f }.toList(),
            log.readBytes().toList(),
            "the record log is byte-identical to the source file's frames",
        )
    }

    @Test
    fun archiveStreamsMp4WithoutLoadingItWhole() {
        // an mp4 is archived by streaming the channel (hash + frame carve), never a whole-file
        // read; the streamed output must be byte-identical to the in-memory path (finding F5)
        val note = EngramRecord(RecordKind.Note, 1, "clip memory".encodeToByteArray())
        val mp4 = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), listOf(note))
        val out = File(dir, "clip.mp4").apply { writeBytes(mp4) }
        val archiveDir = File(dir, "mp4-archive")
        assertEquals(0, cliMain(arrayOf("archive", "--in", out.path, "--out", archiveDir.path)))
        assertEquals(0, cliMain(arrayOf("archive", "validate", "--in", archiveDir.path)))
        // the streamed record log is byte-identical to the frames the source file carries
        val log = archiveDir.listFiles { f -> f.name.endsWith(".records") }!!.single()
        val sourceFrames = ContainerExtraction.rawFrames(out.readBytes())
        assertEquals(
            sourceFrames.fold(ByteArray(0)) { acc, f -> acc + f }.toList(),
            log.readBytes().toList(),
            "the streamed mp4 record log matches the source frames byte for byte",
        )
        // the archive is named by the streaming hash, which equals the whole-file hash
        val json = archiveDir.listFiles { f -> f.name.endsWith(".json") && f.name != "manifest.json" }!!.single()
        assertEquals("${EngramArchive.contentHashName(out.readBytes())}.json", json.name)
    }

    @Test
    fun validateFlagsTamperingAndOmissions() {
        val src = File(dir, "v.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "v-out.jpg")
        assertEquals(0, cliMain(arrayOf("generate", "--in", src.path, "--out", out.path, "--note", "tamper me")))
        val archiveDir = File(dir, "v-archive")
        assertEquals(0, cliMain(arrayOf("archive", "--in", out.path, "--out", archiveDir.path)))
        assertEquals(0, cliMain(arrayOf("archive", "validate", "--in", archiveDir.path)))

        // flip one byte inside the record log: hash mismatch AND corrupt frame
        val log = archiveDir.listFiles { f -> f.name.endsWith(".records") }!!.single()
        val bytes = log.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5] + 1).toByte()
        log.writeBytes(bytes)
        assertEquals(1, cliMain(arrayOf("archive", "validate", "--in", archiveDir.path)))

        // restore, then drop a manifest-listed file entirely
        assertEquals(0, cliMain(arrayOf("archive", "--in", out.path, "--out", archiveDir.path)))
        log.delete()
        assertEquals(1, cliMain(arrayOf("archive", "validate", "--in", archiveDir.path)))
    }
}
