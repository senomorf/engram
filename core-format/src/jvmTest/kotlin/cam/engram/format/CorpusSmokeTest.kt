package cam.engram.format

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.MpfInspector
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.png.PngCodec
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Runs only when private corpus files exist in lab/corpus (never committed).
 * First contact between the parsers and real camera output.
 */
class CorpusSmokeTest {
    private val corpus = File("../lab/corpus")

    private fun files(vararg ext: String): List<File> =
        corpus
            .listFiles { f -> f.isFile && f.extension.lowercase() in ext }
            ?.toList()
            .orEmpty()

    @Test
    fun jpegCorpusRoundTripsByteIdentical() {
        val jpegs = files("jpg", "jpeg")
        assumeTrue("no jpeg corpus files, skipping", jpegs.isNotEmpty())
        for (f in jpegs) {
            val bytes = f.readBytes()
            assertContentEquals(bytes, JpegCodec.serialize(JpegCodec.parse(bytes)), "round trip broke ${f.name}")
            MpfInspector.inspect(bytes) // must not throw; validity recorded by inspect runs
        }
    }

    @Test
    fun pngCorpusRoundTripsByteIdentical() {
        val pngs = files("png")
        assumeTrue("no png corpus files, skipping", pngs.isNotEmpty())
        for (f in pngs) {
            val bytes = f.readBytes()
            assertContentEquals(bytes, PngCodec.serialize(PngCodec.parse(bytes)), "round trip broke ${f.name}")
        }
    }

    @Test
    fun mp4CorpusTopLevelWalks() {
        val vids = files("mp4", "mov")
        assumeTrue("no video corpus files, skipping", vids.isNotEmpty())
        for (f in vids) {
            Mp4Files.topLevel(f) // must walk to EOF without throwing
        }
    }
}
