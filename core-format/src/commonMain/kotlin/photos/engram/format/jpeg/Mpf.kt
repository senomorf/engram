package photos.engram.format.jpeg

import photos.engram.format.u8

class MpfImageRef(
    val index: Int,
    val sizeBytes: Long,
    val offsetRelative: Long,
    val absoluteOffset: Long?,
)

class MpfReport(
    val present: Boolean,
    val images: List<MpfImageRef>,
    val problems: List<String>,
) {
    val valid: Boolean get() = present && problems.isEmpty()
}

/**
 * MPF (CIPA DC-007) carries offsets to images appended after the primary EOI,
 * relative to the MP endian field inside the APP2 payload. Ultra HDR gain maps
 * live behind these offsets; breaking them silently kills HDR rendering, so
 * every write is validated against this inspector.
 */
object MpfInspector {
    // dense guard-clause validator by design; scattering the offset math would hurt review
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun inspect(bytes: ByteArray): MpfReport {
        val parts =
            try {
                JpegCodec.parse(bytes)
            } catch (e: JpegFormatException) {
                return MpfReport(false, emptyList(), listOf("unparseable jpeg: ${e.message}"))
            }
        var pos = 0L
        var segPos = -1L
        var mpf: Segment? = null
        for (p in parts) {
            if (p is Segment && p.isMpfApp2()) {
                segPos = pos
                mpf = p
                break
            }
            pos += p.raw.size
        }
        val seg = mpf ?: return MpfReport(false, emptyList(), emptyList())
        val problems = mutableListOf<String>()
        val images = mutableListOf<MpfImageRef>()
        val payload = seg.payload
        val tiff = MPF_APP2_HEADER.size
        val tiffBaseFilePos = segPos + 4 + tiff

        fun fail(msg: String): MpfReport {
            problems += msg
            return MpfReport(true, images, problems)
        }

        if (payload.size < tiff + 8) return fail("mpf payload too short")
        val little =
            when {
                payload.u8(tiff) == 0x49 && payload.u8(tiff + 1) == 0x49 -> true
                payload.u8(tiff) == 0x4D && payload.u8(tiff + 1) == 0x4D -> false
                else -> return fail("bad tiff endian marker")
            }

        fun inPayload(
            rel: Long,
            len: Int,
        ) = rel >= 0 && tiff + rel + len <= payload.size

        fun u16(rel: Long): Int {
            val a = tiff + rel.toInt()
            return if (little) {
                payload.u8(a) or (payload.u8(a + 1) shl 8)
            } else {
                (payload.u8(a) shl 8) or payload.u8(a + 1)
            }
        }

        fun u32(rel: Long): Long {
            val a = tiff + rel.toInt()
            return if (little) {
                payload.u8(a).toLong() or (payload.u8(a + 1).toLong() shl 8) or
                    (payload.u8(a + 2).toLong() shl 16) or (payload.u8(a + 3).toLong() shl 24)
            } else {
                (payload.u8(a).toLong() shl 24) or (payload.u8(a + 1).toLong() shl 16) or
                    (payload.u8(a + 2).toLong() shl 8) or payload.u8(a + 3).toLong()
            }
        }

        if (!inPayload(0, 8)) return fail("tiff header out of bounds")
        if (u16(2) != 42) return fail("bad tiff magic")
        val ifd = u32(4)
        if (!inPayload(ifd, 2)) return fail("ifd out of bounds")
        val count = u16(ifd)
        var entriesRel = -1L
        var entryCount = 0
        for (k in 0 until count) {
            val e = ifd + 2 + k * 12L
            if (!inPayload(e, 12)) return fail("ifd entry out of bounds")
            if (u16(e) == 0xB002) {
                entryCount = (u32(e + 4) / 16).toInt()
                entriesRel = u32(e + 8)
            }
        }
        if (entriesRel < 0) return fail("no MP entry tag")
        for (k in 0 until entryCount) {
            val b = entriesRel + 16L * k
            if (!inPayload(b, 16)) return fail("mp entry out of bounds")
            val size = u32(b + 4)
            val off = u32(b + 8)
            val abs = if (k == 0) null else tiffBaseFilePos + off
            images += MpfImageRef(k, size, off, abs)
            if (k == 0) {
                if (off != 0L) problems += "primary image offset expected 0, got $off"
            } else {
                when {
                    abs == null || abs + 2 > bytes.size -> problems += "image $k offset beyond file"
                    bytes.u8(abs.toInt()) != 0xFF || bytes.u8(abs.toInt() + 1) != 0xD8 ->
                        problems += "image $k does not point at SOI (file offset $abs)"
                    abs + size > bytes.size -> problems += "image $k size beyond file"
                }
            }
        }
        return MpfReport(true, images, problems)
    }
}
