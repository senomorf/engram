package cam.engram.format.read

import cam.engram.format.FakeXmpEngine
import cam.engram.format.jpeg.JpegEmbedder
import cam.engram.format.mp4.Mp4Files
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExtractionFilesTest {
    private val dir = createTempDirectory("extraction-files").toFile()
    private val xmp = FakeXmpEngine()

    private fun note() = EngramRecord(RecordKind.Note, 7, "clip".encodeToByteArray(), ByteArray(16) { 2 })

    @Test
    fun photoFileMatchesByteInspection() {
        val bytes = JpegEmbedder(xmp).embed(SyntheticMedia.jpegPlain(), listOf(note()), "kept")
        val file = File(dir, "p.jpg").apply { writeBytes(bytes) }
        val fromFile = ExtractionFiles.inspect(file, xmp)!!
        val fromBytes = ContainerExtraction.inspect(bytes, xmp)!!
        assertEquals(fromBytes.container, fromFile.container)
        assertEquals(fromBytes.records.size, fromFile.records.size)
        assertEquals(fromBytes.xmpSummary?.description, fromFile.xmpSummary?.description)
    }

    @Test
    fun mp4FileStreamsRecordsAndCaption() {
        val src = File(dir, "in.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "out.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "подпись")
        val x = ExtractionFiles.inspect(out, xmp)!!
        assertIs<CarrierIntegrity.Readable>(x.integrity)
        assertEquals(1, x.records.size)
        assertEquals("подпись", x.mp4Caption)
    }

    @Test
    fun moovAboveTheCapSkipsTheCaptionButKeepsRecords() {
        val src = File(dir, "in2.mp4").apply { writeBytes(SyntheticMedia.mp4MoovLast()) }
        val out = File(dir, "out2.mp4")
        Mp4Files.appendRecords(src, out, listOf(note()), "подпись")
        val x = ExtractionFiles.inspectMp4(out, moovCap = 8)
        assertNull(x.mp4Caption, "an implausibly large moov is skipped, not loaded")
        assertEquals(1, x.records.size)
    }

    @Test
    fun unknownFileYieldsNull() {
        val file = File(dir, "x.bin").apply { writeBytes(ByteArray(32) { 0x24 }) }
        assertNull(ExtractionFiles.inspect(file, xmp))
    }
}
