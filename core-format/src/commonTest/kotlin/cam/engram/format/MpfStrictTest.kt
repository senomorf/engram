package cam.engram.format

import cam.engram.format.jpeg.MpfInspector
import cam.engram.format.testing.SyntheticMedia
import kotlin.test.Test
import kotlin.test.assertTrue

class MpfStrictTest {
    private fun tiffBase(bytes: ByteArray): Int {
        val marker = "MPF".encodeToByteArray() + byteArrayOf(0)
        for (i in 0 until bytes.size - marker.size) {
            if (bytes.startsWith(marker, i)) return i + 4
        }
        error("no MPF header in fixture")
    }

    // review N9: regression comparison happens on stable kinds; a new kind is a worsening,
    // the same kinds with different numbers (spans shift when the primary grows) are not
    @Test
    fun worsenedFlagsOnlyNewProblemKinds() {
        val before =
            cam.engram.format.jpeg
                .MpfReport(
                    true,
                    emptyList(),
                    listOf("NumberOfImages has wrong type"),
                    problemKinds = listOf("count-type"),
                )
        val sameKindNewNumbers =
            cam.engram.format.jpeg
                .MpfReport(
                    true,
                    emptyList(),
                    listOf("NumberOfImages has wrong type"),
                    problemKinds = listOf("count-type"),
                )
        val newKind =
            cam.engram.format.jpeg.MpfReport(
                true,
                emptyList(),
                listOf("NumberOfImages has wrong type", "image 1 does not point at SOI (file offset 9)"),
                problemKinds = listOf("count-type", "image-1-soi"),
            )
        assertTrue(!MpfInspector.worsened(before, sameKindNewNumbers), "same kinds must not read as a regression")
        assertTrue(MpfInspector.worsened(before, newKind), "a new kind is a regression")
        assertTrue(
            MpfInspector.worsened(
                cam.engram.format.jpeg
                    .MpfReport(true, emptyList(), emptyList()),
                newKind,
            ),
            "any problem on a previously valid MPF is a regression",
        )
    }

    // review N9: a file whose MPF is only cosmetically invalid stays annotatable, and the
    // write adds no new problem kind (growth before the MPF APP2 shifts the trailing images
    // and their tiff base together, so the stored offsets keep meaning)
    @Test
    fun cosmeticallyInvalidMpfStaysWritableAndNoWorse() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        // B001 is the second IFD entry; its type field sits at tiff-relative 8 + 2 + 12 + 2
        // (little-endian fixture): retype LONG -> SHORT, a cosmetic problem, offsets intact
        val at = tiffBase(bytes) + 8 + 2 + 12 + 2
        bytes[at] = 3
        val before = MpfInspector.inspect(bytes)
        assertTrue(!before.valid && before.problemKinds == listOf("count-type"), before.problems.toString())

        val out =
            cam.engram.format.jpeg
                .JpegEmbedder(FakeXmpEngine())
                .embed(
                    bytes,
                    listOf(
                        cam.engram.format.records
                            .EngramRecord(cam.engram.format.records.RecordKind.Note, 1, "n".encodeToByteArray()),
                    ),
                    "note",
                )
        val after = MpfInspector.inspect(out)
        assertTrue(!MpfInspector.worsened(before, after), "the write must add no new problem kind: ${after.problems}")
        assertTrue(after.problemKinds == listOf("count-type"), after.problems.toString())
    }

    @Test
    fun numberOfImagesMismatchIsFlagged() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        // B001 is the second IFD entry; its value sits at tiff-relative 8 + 2 + 12 + 8
        val at = tiffBase(bytes) + 8 + 2 + 12 + 8
        bytes[at] = 5
        val report = MpfInspector.inspect(bytes)
        assertTrue(report.problems.any { it.contains("disagrees") }, report.problems.toString())
    }

    @Test
    fun entryCountNotMultipleOf16IsFlagged() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        // B002 is the third IFD entry; its count sits at tiff-relative 8 + 2 + 24 + 4
        val at = tiffBase(bytes) + 8 + 2 + 24 + 4
        bytes[at] = 33
        val report = MpfInspector.inspect(bytes)
        assertTrue(report.problems.any { it.contains("multiple") }, report.problems.toString())
    }

    @Test
    fun badTiffEndianMarkerIsFlagged() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        bytes[tiffBase(bytes)] = 0x00 // neither II nor MM
        assertTrue(MpfInspector.inspect(bytes).problems.any { it.contains("endian") })
    }

    @Test
    fun badTiffMagicIsFlagged() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        val base = tiffBase(bytes)
        bytes[base + 2] = 0x00
        bytes[base + 3] = 0x00 // magic no longer 42
        assertTrue(MpfInspector.inspect(bytes).problems.any { it.contains("magic") })
    }

    @Test
    fun secondaryNotPointingAtSoiIsFlagged() {
        val bytes = SyntheticMedia.jpegWithMpfSecondary()
        val secondaryAt =
            MpfInspector
                .inspect(bytes)
                .images[1]
                .absoluteOffset!!
                .toInt()
        bytes[secondaryAt] = 0x00 // break the secondary image's SOI marker
        assertTrue(MpfInspector.inspect(bytes).problems.any { it.contains("SOI") })
    }

    @Test
    fun validFixtureStaysValidUnderStrictChecks() {
        assertTrue(MpfInspector.inspect(SyntheticMedia.jpegWithMpfSecondary()).valid)
    }
}
