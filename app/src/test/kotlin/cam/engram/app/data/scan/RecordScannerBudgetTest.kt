package cam.engram.app.data.scan

import cam.engram.app.FakeContentAccess
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import cam.engram.format.testing.SyntheticMedia
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review N8: media arrives from outside the app, so the byte-wise carve behind every scan
 * is budgeted. A region crafted to make each candidate cost a full-span CRC must exhaust
 * that budget and report as not structurally complete: verify must never bless such a read,
 * and crash recovery must never settle on it.
 */
class RecordScannerBudgetTest {
    private val access = FakeContentAccess()
    private val scanner = RecordScanner(access)

    @Test
    fun aCarveBombScansAsStructurallyIncomplete() {
        val uri = "content://media/1"
        access.files[uri] = SyntheticMedia.jpegPlain() + carveBomb(8 * 1024 * 1024)

        val outcome = scanner.scan(uri, isVideo = false, mime = "image/jpeg")!!

        assertFalse(outcome.structurallyComplete, "an exhausted carve must not read as a sound file")
        assertEquals(0, outcome.recordCount, "the bomb carries no real record")
    }

    @Test
    fun anOrdinaryAnnotatedPhotoStaysComplete() {
        val uri = "content://media/2"
        val note = EngramRecord(RecordKind.Note, 1, "real".encodeToByteArray())
        access.files[uri] = SyntheticMedia.jpegPlain() + note.encode()

        val outcome = scanner.scan(uri, isVideo = false, mime = "image/jpeg")!!

        assertTrue(outcome.structurallyComplete, "an honest carve stays well inside the budget")
        assertEquals(1, outcome.recordCount)
    }

    // back-to-back headers whose claimed payloads each span to the region's end: without a
    // budget the carve does one full-span CRC per candidate (quadratic)
    private fun carveBomb(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var at = 0
        while (at + EngramRecord.HEADER_LEN + 4 <= size) {
            EngramRecord.MAGIC.copyInto(bytes, at)
            bytes[at + 4] = EngramRecord.WIRE_VERSION.toByte()
            bytes[at + 5] = RecordKind.Note.code.toByte()
            val claimed = size - at - EngramRecord.HEADER_LEN - 4
            bytes[at + 33] = (claimed ushr 24).toByte()
            bytes[at + 34] = (claimed ushr 16).toByte()
            bytes[at + 35] = (claimed ushr 8).toByte()
            bytes[at + 36] = claimed.toByte()
            at += EngramRecord.HEADER_LEN
        }
        return bytes
    }
}
