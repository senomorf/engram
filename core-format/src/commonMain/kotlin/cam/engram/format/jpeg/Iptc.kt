package cam.engram.format.jpeg

import cam.engram.format.ByteArrayBuilder
import cam.engram.format.startsWith
import cam.engram.format.u16be
import cam.engram.format.u32be
import cam.engram.format.u8

/**
 * IPTC-IIM caption inside a JPEG APP13 Photoshop resource block. Legacy
 * galleries and DAMs read 2:120 Caption/Abstract; writing it is part of the
 * dual-write promise (design D9). The IRB structure is internally relative,
 * so rewriting it is safe, unlike in-place EXIF surgery.
 */
object Iptc {
    val APP13_HEADER = "Photoshop 3.0".encodeToByteArray() + byteArrayOf(0)
    const val APP13_MARKER = 0xED
    private val RESOURCE_SIGNATURE = "8BIM".encodeToByteArray()
    private const val IPTC_RESOURCE_ID = 0x0404
    private const val CAPTION_LIMIT_BYTES = 2000

    fun isIptcApp13(seg: Segment): Boolean = seg.marker == APP13_MARKER && seg.payload.startsWith(APP13_HEADER)

    /**
     * Returns a full APP13 payload with the caption upserted, preserving other resources.
     * Every non-owned IIM dataset (keywords, byline, copyright, extended-length and all) is
     * carried byte-exact. If the existing IIM cannot be safely parsed, the caption mirror is
     * skipped and the original payload is returned unchanged (finding 7): silently dropping
     * datasets and then deleting the .bak backup would lose them irreversibly.
     */
    fun upsertCaption(
        existingPayload: ByteArray?,
        caption: String,
    ): ByteArray {
        if (existingPayload == null) {
            val b = ByteArrayBuilder()
            b.append(APP13_HEADER)
            appendResource(b, IPTC_RESOURCE_ID, iimCaption(caption, ByteArray(0)))
            return b.toByteArray()
        }
        val b = ByteArrayBuilder()
        b.append(APP13_HEADER)
        var replaced = false
        for ((id, raw) in parseResources(existingPayload)) {
            if (id == IPTC_RESOURCE_ID) {
                val carried = resourceData(raw)?.let { carriedDatasets(it) } ?: return existingPayload
                appendResource(b, IPTC_RESOURCE_ID, iimCaption(caption, carried))
                replaced = true
            } else {
                b.append(raw)
            }
        }
        if (!replaced) appendResource(b, IPTC_RESOURCE_ID, iimCaption(caption, ByteArray(0)))
        return b.toByteArray()
    }

    fun readCaption(payload: ByteArray): String? {
        val iptc = parseResources(payload).firstOrNull { it.first == IPTC_RESOURCE_ID } ?: return null
        // second element holds the raw resource; extract its data section
        val data = resourceData(iptc.second) ?: return null
        var i = 0
        while (i < data.size) {
            val ds = datasetAt(data, i) ?: return null
            if (data.u8(i + 1) == 2 && data.u8(i + 2) == 120) {
                return data.copyOfRange(ds.dataStart, ds.dataStart + ds.len).decodeToString()
            }
            i = ds.next
        }
        return null
    }

    // pairs of (resourceId, full raw resource bytes)
    private fun parseResources(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = APP13_HEADER.size
        while (i < payload.size) {
            if (!payload.startsWith(RESOURCE_SIGNATURE, i)) {
                throw JpegFormatException("malformed photoshop resource block at $i, refusing to rewrite APP13")
            }
            val start = i
            i += 4
            val id = payload.u16be(i)
            i += 2
            val nameLen = payload.u8(i)
            i += 1 + nameLen
            if ((nameLen + 1) % 2 != 0) i++
            if (i + 4 > payload.size) throw JpegFormatException("truncated photoshop resource at $start")
            val dataLen = payload.u32be(i)
            i += 4
            if (i + dataLen > payload.size) throw JpegFormatException("photoshop resource overruns APP13 at $start")
            i += dataLen.toInt()
            if (dataLen % 2 != 0L) i++
            out += id to payload.copyOfRange(start, minOf(i, payload.size))
        }
        return out
    }

    private fun resourceData(raw: ByteArray): ByteArray? {
        var i = 4 + 2
        val nameLen = raw.u8(i)
        i += 1 + nameLen
        if ((nameLen + 1) % 2 != 0) i++
        if (i + 4 > raw.size) return null
        val dataLen = raw.u32be(i)
        i += 4
        if (i + dataLen > raw.size) return null
        return raw.copyOfRange(i, i + dataLen.toInt())
    }

    private fun appendResource(
        b: ByteArrayBuilder,
        id: Int,
        data: ByteArray,
    ) {
        b.append(RESOURCE_SIGNATURE)
        b.appendU16be(id)
        b.append(0).append(0) // empty pascal name, padded to even
        b.appendU32be(data.size.toLong())
        b.append(data)
        if (data.size % 2 != 0) b.append(0)
    }

    private fun iimCaption(
        caption: String,
        carried: ByteArray,
    ): ByteArray {
        val b = ByteArrayBuilder()

        // one standard IIM dataset: tag marker, record, dataset id, 2-byte length, data
        fun put(
            record: Int,
            dataset: Int,
            data: ByteArray,
        ) {
            b
                .append(0x1C)
                .append(record)
                .append(dataset)
                .appendU16be(data.size)
                .append(data)
        }
        // 1:90 coded character set: ESC % G means UTF-8
        put(1, 90, byteArrayOf(0x1B, 0x25, 0x47))
        // 2:00 record version 4
        put(2, 0, byteArrayOf(0x00, 0x04))
        // every dataset we do not own, already serialized byte-exact (extended-length
        // datasets included) so annotation keeps prior IPTC metadata
        b.append(carried)
        put(2, 120, truncateUtf8(caption, CAPTION_LIMIT_BYTES))
        return b.toByteArray()
    }

    // serialize every IIM dataset except the ones iimCaption sets (1:90, 2:00, 2:120),
    // byte-exact so an extended-length dataset is preserved verbatim. Returns null if the
    // stream is malformed, so the caller can leave APP13 untouched rather than drop datasets.
    private fun carriedDatasets(data: ByteArray): ByteArray? {
        val b = ByteArrayBuilder()
        var i = 0
        while (i < data.size) {
            val ds = datasetAt(data, i) ?: return null
            val record = data.u8(i + 1)
            val datasetId = data.u8(i + 2)
            val owned = (record == 1 && datasetId == 90) || (record == 2 && (datasetId == 0 || datasetId == 120))
            if (!owned) b.append(data.copyOfRange(i, ds.next))
            i = ds.next
        }
        return b.toByteArray()
    }

    private class Dataset(
        val dataStart: Int,
        val len: Int,
        val next: Int,
    )

    // parse one IIM dataset header at [i]: a standard 2-byte length, or the extended-length
    // form (high bit set, low 15 bits = count of following big-endian length octets). Null if
    // the marker is wrong or the declared length runs past the buffer.
    private fun datasetAt(
        data: ByteArray,
        i: Int,
    ): Dataset? {
        if (i + 5 > data.size || data.u8(i) != 0x1C) return null
        val lenField = data.u16be(i + 3)
        val len: Int
        val headerLen: Int
        if (lenField and 0x8000 != 0) {
            val n = lenField and 0x7FFF
            if (n !in 1..4 || i + 5 + n > data.size) return null
            var l = 0L
            for (k in 0 until n) l = (l shl 8) or data.u8(i + 5 + k).toLong()
            if (l > Int.MAX_VALUE) return null
            len = l.toInt()
            headerLen = 5 + n
        } else {
            len = lenField
            headerLen = 5
        }
        if (i.toLong() + headerLen + len > data.size) return null
        return Dataset(i + headerLen, len, i + headerLen + len)
    }

    // IIM caps caption at 2000 bytes; the full text always lives in XMP anyway
    private fun truncateUtf8(
        text: String,
        limit: Int,
    ): ByteArray {
        val bytes = text.encodeToByteArray()
        if (bytes.size <= limit) return bytes
        var end = limit
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end)
    }
}
