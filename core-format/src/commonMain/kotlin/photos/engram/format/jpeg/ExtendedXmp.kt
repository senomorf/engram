package photos.engram.format.jpeg

import photos.engram.format.Md5
import photos.engram.format.u32be

/**
 * ExtendedXMP (Adobe XMP spec part 3): overflow XMP stored across APP1
 * segments, each carrying the md5 guid of the full extended packet, its total
 * length, and the chunk offset. The standard packet links to it via
 * xmpNote:HasExtendedXMP.
 */
object ExtendedXmp {
    private const val GUID_LEN = 32
    private const val CHUNK_HEADER_LEN = GUID_LEN + 4 + 4

    class Reassembled(
        val guid: String,
        val packet: String,
    )

    /** Reassembles existing extension segments; throws on any inconsistency (fail closed). */
    fun collect(parts: List<JpegPart>): Reassembled? {
        val segs = parts.filterIsInstance<Segment>().filter { it.isExtendedXmpApp1() }
        if (segs.isEmpty()) return null
        var guid: String? = null
        var fullLen = -1L
        val chunks = mutableListOf<Pair<Long, ByteArray>>()
        for (s in segs) {
            val p = s.payload
            val base = EXTENDED_XMP_APP1_HEADER.size
            if (p.size < base + CHUNK_HEADER_LEN) throw JpegFormatException("truncated extended xmp segment")
            val g = p.copyOfRange(base, base + GUID_LEN).decodeToString()
            val len = p.u32be(base + GUID_LEN)
            val off = p.u32be(base + GUID_LEN + 4)
            if (guid ==
                null
            ) {
                guid = g
            } else if (guid !=
                g
            ) {
                throw JpegFormatException("extended xmp guid mismatch across segments")
            }
            if (fullLen <
                0
            ) {
                fullLen = len
            } else if (fullLen !=
                len
            ) {
                throw JpegFormatException("extended xmp length mismatch across segments")
            }
            chunks += off to p.copyOfRange(base + CHUNK_HEADER_LEN, p.size)
        }
        if (fullLen < 0 ||
            fullLen > MAX_EXTENDED_BYTES
        ) {
            throw JpegFormatException("implausible extended xmp length $fullLen")
        }
        val data = ByteArray(fullLen.toInt())
        var covered = 0L
        for ((off, chunk) in chunks) {
            if (off + chunk.size > fullLen) throw JpegFormatException("extended xmp chunk overruns declared length")
            chunk.copyInto(data, off.toInt())
            covered += chunk.size
        }
        if (covered != fullLen) throw JpegFormatException("extended xmp incomplete: $covered of $fullLen bytes present")
        val actualGuid = Md5.hexUpper(Md5.of(data))
        if (actualGuid != guid) throw JpegFormatException("extended xmp guid does not match content")
        return Reassembled(guid, data.decodeToString())
    }

    fun buildSegments(extendedPacket: String): List<Segment> {
        val data = extendedPacket.encodeToByteArray()
        require(data.size <= MAX_EXTENDED_BYTES) { "extended xmp too large" }
        val guid = Md5.hexUpper(Md5.of(data)).encodeToByteArray()
        val room = Segment.MAX_PAYLOAD - EXTENDED_XMP_APP1_HEADER.size - CHUNK_HEADER_LEN
        val segs = mutableListOf<Segment>()
        var off = 0
        while (off < data.size) {
            val end = minOf(data.size, off + room)
            val payload = ByteArray(EXTENDED_XMP_APP1_HEADER.size + CHUNK_HEADER_LEN + (end - off))
            var p = 0
            EXTENDED_XMP_APP1_HEADER.copyInto(payload, p)
            p += EXTENDED_XMP_APP1_HEADER.size
            guid.copyInto(payload, p)
            p += GUID_LEN
            putU32be(payload, p, data.size.toLong())
            putU32be(payload, p + 4, off.toLong())
            p += 8
            data.copyInto(payload, p, off, end)
            segs += Segment.of(JpegCodec.APP1, payload)
            off = end
        }
        return segs
    }

    private const val MAX_EXTENDED_BYTES = 64L * 1024 * 1024

    private fun putU32be(
        bytes: ByteArray,
        at: Int,
        v: Long,
    ) {
        bytes[at] = (v ushr 24).toByte()
        bytes[at + 1] = (v ushr 16).toByte()
        bytes[at + 2] = (v ushr 8).toByte()
        bytes[at + 3] = v.toByte()
    }
}
