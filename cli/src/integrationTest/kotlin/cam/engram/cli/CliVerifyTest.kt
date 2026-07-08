package cam.engram.cli

import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** verify's human-readable output and the unverified/degraded verdicts (the json path is covered elsewhere). */
class CliVerifyTest {
    private val dir = createTempDirectory("engram-verify-human").toFile()

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
    fun humanOutputForIntactFile() {
        val src = File(dir, "s.jpg").apply { writeBytes(SyntheticMedia.jpegWithMpfSecondary()) }
        val audio = File(dir, "a.ogg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val out = File(dir, "o.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "kept safe", "--audio", audio.path)
        val (code, output) = run("verify", "--in", out.path)
        assertEquals(0, code, output)
        assertTrue(output.contains("verdict: intact"), output)
    }

    @Test
    fun unverifiedWithoutSidecar() {
        val plain = File(dir, "plain.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val (code, output) = run("verify", "--in", plain.path)
        assertEquals(0, code, output)
        assertTrue(output.contains("verdict: unverified"), output)
    }

    @Test
    fun jsonEscapesControlCharactersInDescription() {
        val src = File(dir, "j.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "j-out.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "line1\nline2\ttabbed")
        val (code, output) = run("verify", "--in", out.path, "--json")
        assertEquals(0, code, output)
        // the mirrored description carries the control chars; js() must escape them
        assertTrue(output.contains("line1\\nline2\\ttabbed"), output)
    }

    @Test
    fun degradedWhenOnlyCaptionSurvives() {
        val src = File(dir, "s2.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "o2.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "survivor")
        // strip the trailer records but keep the header caption a cloud pipeline would preserve
        val bytes = out.readBytes()
        val cut = RecordStream.scan(bytes).first().offset
        val stripped = File(dir, "stripped.jpg").apply { writeBytes(bytes.copyOfRange(0, cut)) }
        val (code, output) = run("verify", "--in", stripped.path, "--expect", out.path + Expectation.SUFFIX)
        assertEquals(EXIT_DEGRADED, code, output)
        assertTrue(output.contains("verdict: degraded"), output)
    }
}
