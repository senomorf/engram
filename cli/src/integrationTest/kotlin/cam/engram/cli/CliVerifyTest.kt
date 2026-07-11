package cam.engram.cli

import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.records.RecordStream
import cam.engram.format.testing.SyntheticMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Base64
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
    fun corruptedAudioRecordIsDamaged() {
        val src = File(dir, "ac.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val audio = File(dir, "ac.ogg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val out = File(dir, "ac-out.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "n", "--audio", audio.path)
        val bytes = out.readBytes()
        // flip a byte inside the audio record's payload so its crc fails
        val audioHit = RecordStream.scan(bytes).first { it.decoded.record?.kind == RecordKind.Audio }
        // land inside the payload (past the ~47-byte header) so the record still decodes but its crc fails
        val payloadByte = audioHit.offset + 60
        bytes[payloadByte] = (bytes[payloadByte] + 1).toByte()
        val corrupt = File(dir, "ac-corrupt.jpg").apply { writeBytes(bytes) }
        val (code, output) = run("verify", "--in", corrupt.path, "--expect", out.path + Expectation.SUFFIX)
        assertEquals(EXIT_DAMAGED, code, output)
        assertTrue(output.contains("corrupted"), output)
    }

    @Test
    fun untrackedRecordStripIsDamaged() {
        // the sidecar of the second generate plants only the audio, but the file also
        // carries the earlier note; losing that historical record must not verify intact
        val src = File(dir, "u.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val mid = File(dir, "u-note.jpg")
        run("generate", "--in", src.path, "--out", mid.path, "--note", "history")
        val audio = File(dir, "u.ogg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val out = File(dir, "u-audio.jpg")
        run("generate", "--in", mid.path, "--out", out.path, "--audio", audio.path)
        val bytes = out.readBytes()
        val noteHit = RecordStream.scan(bytes).first { it.decoded.record?.kind == RecordKind.Note }
        val stripped =
            bytes.copyOfRange(0, noteHit.offset) +
                bytes.copyOfRange(noteHit.offset + noteHit.decoded.byteLength, bytes.size)
        val lossy = File(dir, "u-lossy.jpg").apply { writeBytes(stripped) }
        val (code, output) = run("verify", "--in", lossy.path, "--expect", out.path + Expectation.SUFFIX)
        assertEquals(EXIT_DAMAGED, code, output)
    }

    @Test
    fun carrierDamageDegradesVerdict() {
        // undecodable bytes inside the engram box: the planted note is exact, but the
        // carrier lost data it claimed to hold, so the verdict must not be intact
        val id = ByteArray(16) { 1 }
        val note = EngramRecord(RecordKind.Note, 7, "kept".encodeToByteArray(), id)
        val box = Mp4Codec.buildEngramBox(note.encode() + ByteArray(24) { 0x5A })
        val media = File(dir, "c.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast() + box) }
        val noteB64 = Base64.getEncoder().encodeToString("kept".encodeToByteArray())
        File(media.path + Expectation.SUFFIX).writeText(
            "engram-expect=1\ncontainer=mp4\nrecords=1\nnote.b64=$noteB64\n" +
                "note.id=${note.idHex}\nids=${note.idHex}\nmpf=absent\nextended=false\n",
        )
        val (code, output) = run("verify", "--in", media.path)
        assertEquals(EXIT_DEGRADED, code, output)
        assertTrue(output.contains("carrier"), output)
    }

    @Test
    fun strayCorruptFrameDegradesVerdict() {
        // an unexpected crc-bad record fragment beside an exact planted note is damage
        // the old verdict ignored entirely
        val src = File(dir, "y.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "y-out.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "fine")
        val stray = EngramRecord(RecordKind.Note, 5, "junk!!".encodeToByteArray()).encode()
        stray[stray.size - 6] = 0x00 // corrupt the payload, leave the crc stale
        val damaged = File(dir, "y-stray.jpg").apply { writeBytes(out.readBytes() + stray) }
        val (code, output) = run("verify", "--in", damaged.path, "--expect", out.path + Expectation.SUFFIX)
        assertEquals(EXIT_DEGRADED, code, output)
        assertTrue(output.contains("recordIntegrity"), output)
    }

    @Test
    fun appendAfterExpectationStaysIntact() {
        // records are append-only: a legitimate later save must not fail an older sidecar
        val src = File(dir, "p.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val first = File(dir, "p-1.jpg")
        run("generate", "--in", src.path, "--out", first.path, "--note", "original")
        val second = File(dir, "p-2.jpg")
        run("generate", "--in", first.path, "--out", second.path, "--note", "appended later")
        val (code, output) = run("verify", "--in", second.path, "--expect", first.path + Expectation.SUFFIX)
        assertEquals(0, code, output)
        assertTrue(output.contains("verdict: intact"), output)
    }

    @Test
    fun legacySidecarWithoutIdsStillVerifies() {
        val src = File(dir, "l.jpg").apply { writeBytes(SyntheticMedia.jpegPlain()) }
        val out = File(dir, "l-out.jpg")
        run("generate", "--in", src.path, "--out", out.path, "--note", "old tool")
        val sidecar = File(out.path + Expectation.SUFFIX)
        sidecar.writeText(sidecar.readLines().filterNot { it.startsWith("ids=") }.joinToString("\n") + "\n")
        val (code, output) = run("verify", "--in", out.path, "--expect", sidecar.path)
        assertEquals(0, code, output)
        assertTrue(output.contains("verdict: intact"), output)
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
