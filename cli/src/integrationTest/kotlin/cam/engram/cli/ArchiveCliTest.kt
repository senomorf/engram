package cam.engram.cli

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
    }
}
