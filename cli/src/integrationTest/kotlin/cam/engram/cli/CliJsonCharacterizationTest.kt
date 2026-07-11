package cam.engram.cli

import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Codec
import cam.engram.format.png.PngEmbedder
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import cam.engram.format.xmp.XmpCoreEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization pins: the verify --json schema is append-only (spec sec 8),
 * so these fix the exact serialization for deterministic files BEFORE the
 * extraction internals move to core-format. A rewiring that changes one byte of
 * this output fails here instead of silently breaking downstream parsers.
 */
class CliJsonCharacterizationTest {
    private val dir = createTempDirectory("engram-json-pin").toFile()

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

    private fun note() = EngramRecord(RecordKind.Note, 42, "kept safe".encodeToByteArray(), ByteArray(16) { 1 }, "w")

    @Test
    fun jpegVerifyJsonIsPinned() {
        val rec = note()
        val bytes = JpegEmbedder(XmpCoreEngine()).embed(SyntheticMedia.jpegWithMpfSecondary(), listOf(rec), "kept safe")
        val f = File(dir, "pin.jpg").apply { writeBytes(bytes) }
        val (code, out) = run("verify", "--in", f.path, "--json")
        assertEquals(0, code, out)
        val expected =
            """{"file":"${f.path}","container":"jpeg","integrity":"intact","verdict":"unverified",""" +
                """"records":[{"kind":"Note","id":"${rec.idHex}","writer":"w","ts":42,"crcOk":true}],""" +
                """"checks":[],""" +
                """"xmp":{"present":true,"engram":true,"description":"kept safe",""" +
                """"payloadLength":${rec.encode().size},"recordCount":1,"extendedGuid":null},""" +
                """"mpf":{"present":true,"valid":true,"problems":[]},""" +
                """"extendedXmp":"absent","motionMarkers":false,"iptcCaption":"kept safe","mp4Caption":null}"""
        assertEquals(expected, out.trim())
    }

    @Test
    fun pngVerifyJsonIsPinned() {
        val rec = note()
        val bytes = PngEmbedder(XmpCoreEngine()).embed(SyntheticMedia.png1x1(), listOf(rec), "kept safe")
        val f = File(dir, "pin.png").apply { writeBytes(bytes) }
        val (code, out) = run("verify", "--in", f.path, "--json")
        assertEquals(0, code, out)
        val expected =
            """{"file":"${f.path}","container":"png","integrity":"intact","verdict":"unverified",""" +
                """"records":[{"kind":"Note","id":"${rec.idHex}","writer":"w","ts":42,"crcOk":true}],""" +
                """"checks":[],""" +
                """"xmp":{"present":true,"engram":true,"description":"kept safe",""" +
                """"payloadLength":${rec.encode().size},"recordCount":1,"extendedGuid":null},""" +
                """"mpf":{"present":false},""" +
                """"extendedXmp":"absent","motionMarkers":false,"iptcCaption":null,"mp4Caption":null}"""
        assertEquals(expected, out.trim())
    }

    @Test
    fun mp4VerifyJsonIsPinned() {
        val rec = note()
        val bytes = Mp4Codec.embed(SyntheticMedia.mp4MoovLast(), listOf(rec))
        val f = File(dir, "pin.mp4").apply { writeBytes(bytes) }
        val (code, out) = run("verify", "--in", f.path, "--json")
        assertEquals(0, code, out)
        val expected =
            """{"file":"${f.path}","container":"mp4","integrity":"intact","verdict":"unverified",""" +
                """"records":[{"kind":"Note","id":"${rec.idHex}","writer":"w","ts":42,"crcOk":true}],""" +
                """"checks":[],""" +
                """"xmp":{"present":false},""" +
                """"mpf":{"present":false},""" +
                """"extendedXmp":"absent","motionMarkers":false,"iptcCaption":null,"mp4Caption":null}"""
        assertEquals(expected, out.trim())
    }
}
