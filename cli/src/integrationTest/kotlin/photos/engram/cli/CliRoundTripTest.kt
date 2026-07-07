package photos.engram.cli

import photos.engram.format.jpeg.JpegCodec
import photos.engram.format.jpeg.MpfInspector
import photos.engram.format.jpeg.Segment
import photos.engram.format.jpeg.isXmpApp1
import photos.engram.format.jpeg.xmpPacket
import photos.engram.format.mp4.Mp4Files
import photos.engram.format.png.PngCodec
import photos.engram.format.records.RecordStream
import photos.engram.format.testing.SyntheticMedia
import photos.engram.format.xmp.XmpCoreEngine
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliRoundTripTest {
    private val dir = createTempDirectory("engram-it").toFile()

    @Test
    fun jpegGenerateThenInspect() {
        val src = File(dir, "src.jpg").apply { writeBytes(SyntheticMedia.jpegWithMpfSecondary()) }
        val audio = File(dir, "note.ogg").apply { writeBytes(ByteArray(256) { it.toByte() }) }
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
                    "integration note",
                    "--audio",
                    audio.path,
                ),
            ),
        )
        val bytes = out.readBytes()
        assertEquals(2, RecordStream.scan(bytes).count { it.decoded.crcOk })
        assertTrue(MpfInspector.inspect(bytes).valid)
        val packet =
            JpegCodec
                .parse(bytes)
                .filterIsInstance<Segment>()
                .first { it.isXmpApp1() }
                .xmpPacket()
        assertEquals("integration note", XmpCoreEngine().read(packet).description)
        assertEquals(0, cliMain(arrayOf("inspect", "--in", out.path)))
    }

    @Test
    fun pngAndMp4GenerateThenRead() {
        val png = File(dir, "s.png").apply { writeBytes(SyntheticMedia.png1x1()) }
        val pngOut = File(dir, "s-out.png")
        assertEquals(0, cliMain(arrayOf("generate", "--in", png.path, "--out", pngOut.path, "--note", "png note")))
        assertEquals(1, PngCodec.engramRecords(PngCodec.parse(pngOut.readBytes())).count { it.crcOk })

        val mp4 = File(dir, "v.mp4").apply { writeBytes(SyntheticMedia.mp4Minimal()) }
        val mp4Out = File(dir, "v-out.mp4")
        assertEquals(0, cliMain(arrayOf("generate", "--in", mp4.path, "--out", mp4Out.path, "--note", "clip note")))
        assertEquals(1, Mp4Files.readRecords(mp4Out).count { it.decoded.crcOk })
    }

    @Test
    fun selftestPasses() {
        assertEquals(0, cliMain(arrayOf("selftest")))
    }

    @Test
    fun badInputFailsWithoutCrashing() {
        val junk = File(dir, "junk.bin").apply { writeBytes(ByteArray(10) { 7 }) }
        assertEquals(1, cliMain(arrayOf("inspect", "--in", junk.path)))
        assertEquals(2, cliMain(arrayOf("wat")))
    }
}
