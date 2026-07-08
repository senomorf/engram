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
    fun validFixtureStaysValidUnderStrictChecks() {
        assertTrue(MpfInspector.inspect(SyntheticMedia.jpegWithMpfSecondary()).valid)
    }
}
