package photos.engram.format.jpeg

import photos.engram.format.startsWith
import photos.engram.format.u16be
import photos.engram.format.u8

class JpegFormatException(message: String) : Exception(message)

/** Parts hold the exact original bytes; serialize(parse(x)) is byte-identical. */
sealed class JpegPart(val raw: ByteArray)

class MarkerOnly(raw: ByteArray) : JpegPart(raw) {
    val marker: Int get() = raw.u8(1)
}

class Segment(raw: ByteArray) : JpegPart(raw) {
    val marker: Int get() = raw.u8(1)
    val payload: ByteArray get() = raw.copyOfRange(4, raw.size)

    companion object {
        const val MAX_PAYLOAD = 65533

        fun of(marker: Int, payload: ByteArray): Segment {
            require(payload.size <= MAX_PAYLOAD) { "segment payload too large: ${payload.size}" }
            val raw = ByteArray(4 + payload.size)
            raw[0] = 0xFF.toByte()
            raw[1] = marker.toByte()
            val len = payload.size + 2
            raw[2] = (len ushr 8).toByte()
            raw[3] = len.toByte()
            payload.copyInto(raw, 4)
            return Segment(raw)
        }
    }
}

class Entropy(raw: ByteArray) : JpegPart(raw)

class Filler(raw: ByteArray) : JpegPart(raw)

class TrailerData(raw: ByteArray) : JpegPart(raw)

object JpegCodec {

    const val SOI = 0xD8
    const val EOI = 0xD9
    const val SOS = 0xDA
    const val APP0 = 0xE0
    const val APP1 = 0xE1
    const val APP2 = 0xE2

    fun parse(bytes: ByteArray): List<JpegPart> {
        if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != SOI) throw JpegFormatException("not a jpeg")
        val parts = mutableListOf<JpegPart>(MarkerOnly(bytes.copyOfRange(0, 2)))
        var i = 2
        while (true) {
            val fillStart = i
            while (i + 1 < bytes.size && bytes.u8(i) == 0xFF && bytes.u8(i + 1) == 0xFF) i++
            if (i > fillStart) parts += Filler(bytes.copyOfRange(fillStart, i))
            if (i + 1 >= bytes.size) throw JpegFormatException("truncated at offset $i")
            if (bytes.u8(i) != 0xFF) throw JpegFormatException("expected marker at offset $i")
            val marker = bytes.u8(i + 1)
            when {
                marker == EOI -> {
                    parts += MarkerOnly(bytes.copyOfRange(i, i + 2))
                    if (i + 2 < bytes.size) parts += TrailerData(bytes.copyOfRange(i + 2, bytes.size))
                    return parts
                }
                marker == SOI -> throw JpegFormatException("unexpected SOI at offset $i")
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    parts += MarkerOnly(bytes.copyOfRange(i, i + 2))
                    i += 2
                }
                else -> {
                    if (i + 4 > bytes.size) throw JpegFormatException("truncated segment header at offset $i")
                    val len = bytes.u16be(i + 2)
                    if (len < 2 || i + 2 + len > bytes.size) throw JpegFormatException("bad segment length at offset $i")
                    parts += Segment(bytes.copyOfRange(i, i + 2 + len))
                    i += 2 + len
                    if (marker == SOS) {
                        val start = i
                        var j = i
                        while (true) {
                            if (j + 1 >= bytes.size) throw JpegFormatException("entropy data truncated")
                            if (bytes.u8(j) == 0xFF) {
                                val next = bytes.u8(j + 1)
                                if (next == 0x00 || next in 0xD0..0xD7) {
                                    j += 2
                                    continue
                                }
                                break
                            }
                            j++
                        }
                        if (j > start) parts += Entropy(bytes.copyOfRange(start, j))
                        i = j
                    }
                }
            }
        }
    }

    fun serialize(parts: List<JpegPart>): ByteArray {
        val out = ByteArray(parts.sumOf { it.raw.size })
        var pos = 0
        parts.forEach { p ->
            p.raw.copyInto(out, pos)
            pos += p.raw.size
        }
        return out
    }
}

val XMP_APP1_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
val EXTENDED_XMP_APP1_HEADER = "http://ns.adobe.com/xmp/extension/\u0000".encodeToByteArray()
val EXIF_APP1_HEADER = "Exif\u0000\u0000".encodeToByteArray()
val MPF_APP2_HEADER = "MPF\u0000".encodeToByteArray()

fun Segment.isXmpApp1(): Boolean = marker == JpegCodec.APP1 && payload.startsWith(XMP_APP1_HEADER)
fun Segment.isExtendedXmpApp1(): Boolean = marker == JpegCodec.APP1 && payload.startsWith(EXTENDED_XMP_APP1_HEADER)
fun Segment.isExifApp1(): Boolean = marker == JpegCodec.APP1 && payload.startsWith(EXIF_APP1_HEADER)
fun Segment.isMpfApp2(): Boolean = marker == JpegCodec.APP2 && payload.startsWith(MPF_APP2_HEADER)

fun Segment.xmpPacket(): String = payload.copyOfRange(XMP_APP1_HEADER.size, payload.size).decodeToString()
