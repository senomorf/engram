package photos.engram.cli

import photos.engram.format.mp4.Mp4Caption
import photos.engram.format.testing.SyntheticMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyRoundTripTest {
    private val dir = createTempDirectory("engram-verify").toFile()

    private fun run(vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        val code =
            try {
                cliMain(arrayOf(*args))
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    @Test
    fun intactFileVerifiesWithJson() {
        val src = File(dir, "src.jpg").apply { writeBytes(SyntheticMedia.jpegWithMpfSecondary()) }
        val audio = File(dir, "a.ogg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val out = File(dir, "out.jpg")
        val (genCode, _) =
            run("generate", "--in", src.path, "--out", out.path, "--note", "intact note", "--audio", audio.path)
        assertEquals(0, genCode)
        assertTrue(File(out.path + Expectation.SUFFIX).isFile, "expectation sidecar missing")
        val (code, output) = run("verify", "--in", out.path, "--json")
        assertEquals(0, code, output)
        assertTrue(output.contains("\"verdict\":\"intact\""), output)
        assertTrue(output.contains("\"status\":\"exact\""), output)
    }

    @Test
    fun strippedFileIsDamaged() {
        val src = File(dir, "src2.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "out2.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "will vanish")
        // simulate a stripping pipeline: the original bytes with our expectation
        val stripped = File(dir, "stripped.jpg").apply { writeBytes(src.readBytes()) }
        val (code, output) = run("verify", "--in", stripped.path, "--expect", out.path + Expectation.SUFFIX, "--json")
        assertEquals(EXIT_DAMAGED, code, output)
        assertTrue(output.contains("\"verdict\":\"damaged\""), output)
        assertTrue(output.contains("\"status\":\"gone\""), output)
    }

    @Test
    fun mp4CaptionMirroredAndVerified() {
        val src = File(dir, "v.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "v-out.mp4")
        val (genCode, genOut) = run("generate", "--in", src.path, "--out", out.path, "--note", "clip caption")
        assertEquals(0, genCode, genOut)
        assertTrue(genOut.contains("caption mirrored"), genOut)
        assertEquals("clip caption", Mp4Caption.readCaption(out.readBytes()))
        val (code, output) = run("verify", "--in", out.path, "--json")
        assertEquals(0, code, output)
        assertTrue(output.contains("\"verdict\":\"intact\""), output)
    }
}
