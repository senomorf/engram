package cam.engram.format.jpeg

import cam.engram.format.u8

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
    // file offset of the primary MP entry "Individual Image Size" field and its endianness,
    // so a writer can patch the size after it grows the primary image (finding 3)
    val primarySizeFieldPos: Long? = null,
    val little: Boolean = false,
    // stable kind slugs parallel to [problems]: the human strings carry offsets and spans
    // that legitimately shift when a write grows the primary, so regression comparison
    // must happen on kinds, not strings (review N9)
    val problemKinds: List<String> = emptyList(),
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
    private const val TAG_VERSION = 0xB000
    private const val TAG_NUMBER_OF_IMAGES = 0xB001
    private const val TAG_MP_ENTRY = 0xB002
    private const val TYPE_LONG = 4
    private const val TYPE_UNDEFINED = 7
    private const val MP_ENTRY_SIZE = 16

    /**
     * True when [after] carries any problem kind [before] did not: the write introduced a
     * new inconsistency. Same kinds with different numbers (spans and offsets shift when
     * the primary grows) are not a regression, and a pre-broken file does not become
     * unwritable (review N9).
     */
    fun worsened(
        before: MpfReport,
        after: MpfReport,
    ): Boolean {
        val known = before.problemKinds.toSet()
        return after.problemKinds.any { it !in known }
    }

    // dense guard-clause validator by design; scattering the offset math would hurt review
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    fun inspect(bytes: ByteArray): MpfReport {
        val parts =
            try {
                JpegCodec.parse(bytes)
            } catch (e: JpegFormatException) {
                return MpfReport(
                    false,
                    emptyList(),
                    listOf("unparseable jpeg: ${e.message}"),
                    problemKinds = listOf("unparseable"),
                )
            }
        // the primary image is SOI..EOI (everything before the first appended trailer image)
        val eoiIndex = parts.indexOfFirst { it is MarkerOnly && it.marker == JpegCodec.EOI }
        val primarySpan = if (eoiIndex >= 0) parts.take(eoiIndex + 1).sumOf { it.raw.size.toLong() } else -1L
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
        val kinds = mutableListOf<String>()
        val images = mutableListOf<MpfImageRef>()
        val payload = seg.payload
        val tiff = MPF_APP2_HEADER.size
        val tiffBaseFilePos = segPos + 4 + tiff

        fun problem(
            kind: String,
            msg: String,
        ) {
            kinds += kind
            problems += msg
        }

        fun fail(
            kind: String,
            msg: String,
        ): MpfReport {
            problem(kind, msg)
            return MpfReport(true, images, problems, problemKinds = kinds)
        }

        if (payload.size < tiff + 8) return fail("payload-short", "mpf payload too short")
        val little =
            when {
                payload.u8(tiff) == 0x49 && payload.u8(tiff + 1) == 0x49 -> true
                payload.u8(tiff) == 0x4D && payload.u8(tiff + 1) == 0x4D -> false
                else -> return fail("endian", "bad tiff endian marker")
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

        if (!inPayload(0, 8)) return fail("tiff-bounds", "tiff header out of bounds")
        if (u16(2) != 42) return fail("tiff-magic", "bad tiff magic")
        val ifd = u32(4)
        if (!inPayload(ifd, 2)) return fail("ifd-bounds", "ifd out of bounds")
        val count = u16(ifd)
        var entriesRel = -1L
        var entryCount = 0
        var declaredImages = -1L
        for (k in 0 until count) {
            val e = ifd + 2 + k * 12L
            if (!inPayload(e, 12)) return fail("entry-bounds", "ifd entry out of bounds")
            when (u16(e)) {
                TAG_NUMBER_OF_IMAGES -> {
                    if (u16(e + 2) != TYPE_LONG) problem("count-type", "NumberOfImages has wrong type")
                    declaredImages = u32(e + 8)
                }
                TAG_MP_ENTRY -> {
                    if (u16(e + 2) != TYPE_UNDEFINED) problem("entries-type", "MP entry tag has wrong type")
                    val cnt = u32(e + 4)
                    if (cnt % MP_ENTRY_SIZE != 0L) {
                        problem("entries-size", "MP entry byte count $cnt not a multiple of $MP_ENTRY_SIZE")
                    }
                    entryCount = (cnt / MP_ENTRY_SIZE).toInt()
                    entriesRel = u32(e + 8)
                }
                TAG_VERSION -> Unit
            }
        }
        if (entriesRel < 0) return fail("no-entries", "no MP entry tag")
        if (entryCount == 0) return fail("zero-images", "MP entry tag declares zero images")
        if (declaredImages < 0) {
            problem("count-missing", "NumberOfImages tag missing")
        } else if (declaredImages != entryCount.toLong()) {
            problem("count-mismatch", "NumberOfImages $declaredImages disagrees with MP entry count $entryCount")
        }
        for (k in 0 until entryCount) {
            val b = entriesRel + MP_ENTRY_SIZE.toLong() * k
            if (!inPayload(b, MP_ENTRY_SIZE)) return fail("entries-bounds", "mp entry out of bounds")
            val size = u32(b + 4)
            val off = u32(b + 8)
            val abs = if (k == 0) null else tiffBaseFilePos + off
            images += MpfImageRef(k, size, off, abs)
            if (k == 0) {
                if (off != 0L) problem("primary-offset", "primary image offset expected 0, got $off")
                // the primary size must track the SOI..EOI span; a grown primary (after a metadata
                // write) with a stale size is an inconsistent MPF that HDR consumers may reject
                if (primarySpan >= 0 && size != primarySpan) {
                    problem("primary-size", "primary image size $size disagrees with SOI..EOI span $primarySpan")
                }
            } else {
                when {
                    abs == null || abs + 2 > bytes.size -> problem("image-$k-bounds", "image $k offset beyond file")
                    bytes.u8(abs.toInt()) != 0xFF || bytes.u8(abs.toInt() + 1) != 0xD8 ->
                        problem("image-$k-soi", "image $k does not point at SOI (file offset $abs)")
                    abs + size > bytes.size -> problem("image-$k-size", "image $k size beyond file")
                }
            }
        }
        return MpfReport(true, images, problems, tiffBaseFilePos + entriesRel + 4, little, kinds)
    }
}
