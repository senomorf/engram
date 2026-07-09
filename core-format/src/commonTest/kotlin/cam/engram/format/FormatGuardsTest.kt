package cam.engram.format

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegFormatException
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngFormatException
import cam.engram.format.records.EngramRecord
import cam.engram.format.records.RecordKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Fail-closed guards: the parsers must reject malformed containers rather than
 * silently mangle foreign metadata (design sec 10, review F1).
 */
class FormatGuardsTest {
    @Test
    fun jpegRejectsBadSegmentLength() {
        // APP0 marker with a declared length below the mandatory 2 bytes
        assertFailsWith<JpegFormatException> {
            JpegCodec.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x01))
        }
    }

    @Test
    fun jpegRejectsTruncatedSegmentHeader() {
        assertFailsWith<JpegFormatException> {
            JpegCodec.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        }
    }

    @Test
    fun jpegRejectsUnexpectedSoi() {
        assertFailsWith<JpegFormatException> {
            JpegCodec.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD8.toByte()))
        }
    }

    @Test
    fun pngRejectsTruncatedChunk() {
        // signature then a chunk header claiming data that is not present
        assertFailsWith<PngFormatException> {
            PngCodec.parse(PngCodec.SIGNATURE + byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x49, 0x48, 0x44, 0x52))
        }
    }

    @Test
    fun pngRejectsImplausibleChunkLength() {
        assertFailsWith<PngFormatException> {
            PngCodec.parse(
                PngCodec.SIGNATURE +
                    byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x49, 0x48, 0x44, 0x52),
            )
        }
    }

    @Test
    fun jpegRoundTripsStandaloneMarker() {
        // SOI, a standalone TEM (0xFF01) parsed as a MarkerOnly, then EOI
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        assertContentEquals(jpeg, JpegCodec.serialize(JpegCodec.parse(jpeg)))
    }

    @Test
    fun pngRejectsChunkLengthOverrunningFile() {
        // header fits, but the declared chunk length runs past the end of the file
        val bytes =
            PngCodec.SIGNATURE + byteArrayOf(0, 0, 0, 100, 0x49, 0x48, 0x44, 0x52) + ByteArray(4)
        assertFailsWith<PngFormatException> { PngCodec.parse(bytes) }
    }

    @Test
    fun recordRejectsWrongIdLength() {
        assertFailsWith<IllegalArgumentException> {
            EngramRecord(RecordKind.Note, 1, ByteArray(0), id = ByteArray(4))
        }
    }

    @Test
    fun recordRejectsOverlongWriter() {
        assertFailsWith<IllegalArgumentException> {
            EngramRecord(RecordKind.Note, 1, ByteArray(0), writer = "w".repeat(300))
        }
    }

    @Test
    fun decodeAtReturnsNullForForeignBytes() {
        assertNull(EngramRecord.decodeAt(ByteArray(40) { 0x55 }, 0))
    }
}
