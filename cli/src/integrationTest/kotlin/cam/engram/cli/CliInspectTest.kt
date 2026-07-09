package cam.engram.cli

import cam.engram.format.jpeg.MpfInspector
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
    fun inspectsJpegWithStandaloneMarker() {
        // a standalone TEM marker (0xFF01) drives markerName's unknown-marker fallthrough
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val f = File(dir, "tem.jpg").apply { writeBytes(jpeg) }
        assertEquals(0, cliMain(arrayOf("inspect", "--in", f.path)))
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

    @Test
    fun inspectsJpegWithBrokenMpf() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        // break the secondary image's SOI so MPF validation reports it as broken
        val secondaryAt =
            MpfInspector
                .inspect(bytes)
                .images[1]
                .absoluteOffset!!
                .toInt()
        bytes[secondaryAt] = 0x00
        val f = File(dir, "brokenmpf.jpg").apply { writeBytes(bytes) }
        assertEquals(0, cliMain(arrayOf("inspect", "--in", f.path)))
    }

    @Test
    fun inspectsJpegWithMotionMarkersAndForeignXmp() {
        // a foreign XMP packet with no engram props but a MotionPhoto marker
        val packet =
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
                "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\" " +
                "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                "GCamera:MotionPhoto=\"1\"/></rdf:RDF></x:xmpmeta>"
        val f = File(dir, "motion.jpg").apply { writeBytes(SyntheticMedia.jpegWithXmp(packet)) }
        assertEquals(0, cliMain(arrayOf("inspect", "--in", f.path)))
    }
}
