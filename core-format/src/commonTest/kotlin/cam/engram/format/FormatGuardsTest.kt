package cam.engram.format

import cam.engram.format.jpeg.JpegCodec
import cam.engram.format.jpeg.JpegFormatException
import cam.engram.format.png.PngCodec
import cam.engram.format.png.PngFormatException
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
}
