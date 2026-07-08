package cam.engram.cli

import cam.engram.format.testing.SyntheticMedia
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** inspect over every container, exercising the png and mp4 inspection paths and the metadata-absent case. */
class CliInspectTest {
    private val dir = createTempDirectory("engram-inspect").toFile()

    @Test
    fun inspectsPngWithRecords() {
        val png = File(dir, "s.png").apply { writeBytes(SyntheticMedia.png1x1()) }
        val out = File(dir, "s-out.png")
        assertEquals(0, cliMain(arrayOf("generate", "--in", png.path, "--out", out.path, "--note", "png memory")))
        assertEquals(0, cliMain(arrayOf("inspect", "--in", out.path)))
    }

    @Test
    fun inspectsMp4WithRecords() {
        val mp4 = File(dir, "v.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "v-out.mp4")
        assertEquals(0, cliMain(arrayOf("generate", "--in", mp4.path, "--out", out.path, "--note", "clip memory")))
        assertEquals(0, cliMain(arrayOf("inspect", "--in", out.path)))
    }

    @Test
    fun inspectsPlainJpegWithoutMetadata() {
        val plain = File(dir, "p.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        assertEquals(0, cliMain(arrayOf("inspect", "--in", plain.path)))
    }
}
