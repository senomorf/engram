package photos.engram.format

import photos.engram.format.jpeg.ExtendedXmp
import photos.engram.format.jpeg.JpegFormatException
import photos.engram.format.jpeg.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtendedXmpChunkTest {
    @Test
    fun multiChunkRoundTrip() {
        // 200k forces several APP1 extension segments
        val packet = "D".repeat(200_000)
        val segments = ExtendedXmp.buildSegments(packet)
        assertTrue(segments.size >= 3, "expected several chunks, got ${segments.size}")
        val reassembled = ExtendedXmp.collect(segments)!!
        assertEquals(packet, reassembled.packet)
        assertEquals(Md5.hexUpper(Md5.of(packet.encodeToByteArray())), reassembled.guid)
    }

    @Test
    fun missingChunkFailsClosed() {
        val segments = ExtendedXmp.buildSegments("E".repeat(200_000))
        val incomplete = segments.drop(1)
        assertFailsWith<JpegFormatException> { ExtendedXmp.collect(incomplete) }
    }

    @Test
    fun corruptedChunkFailsGuidCheck() {
        val segments = ExtendedXmp.buildSegments("F".repeat(100_000))
        val tampered =
            segments.mapIndexed { i, seg ->
                if (i == segments.lastIndex) {
                    val raw = seg.raw.copyOf()
                    raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
                    Segment(raw)
                } else {
                    seg
                }
            }
        assertFailsWith<JpegFormatException> { ExtendedXmp.collect(tampered) }
    }
}
