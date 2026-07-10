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

    /** Returns a full APP13 payload with the caption upserted, preserving other resources. */
    fun upsertCaption(
        existingPayload: ByteArray?,
        caption: String,
    ): ByteArray {
        val resources = existingPayload?.let { parseResources(it) } ?: emptyList()
        val b = ByteArrayBuilder()
        b.append(APP13_HEADER)
        var replaced = false
        for ((id, raw) in resources) {
            if (id == IPTC_RESOURCE_ID) {
                // preserve every other IIM dataset (keywords, byline, copyright, ...) in the
                // existing 0x0404, replacing only the caption (finding 7)
                appendResource(b, IPTC_RESOURCE_ID, iimCaption(caption, resourceData(raw)))
                replaced = true
            } else {
                b.append(raw)
            }
        }
        if (!replaced) appendResource(b, IPTC_RESOURCE_ID, iimCaption(caption, null))
        return b.toByteArray()
    }

    fun readCaption(payload: ByteArray): String? {
        val iptc = parseResources(payload).firstOrNull { it.first == IPTC_RESOURCE_ID } ?: return null
        // second element holds the raw resource; extract its data section
        val data = resourceData(iptc.second) ?: return null
        var i = 0
        while (i + 5 <= data.size) {
            if (data.u8(i) != 0x1C) return null
            val record = data.u8(i + 1)
            val dataset = data.u8(i + 2)
            val len = data.u16be(i + 3)
            if (i + 5 + len > data.size) return null
            if (record == 2 && dataset == 120) return data.copyOfRange(i + 5, i + 5 + len).decodeToString()
            i += 5 + len
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
        existing: ByteArray?,
    ): ByteArray {
        val b = ByteArrayBuilder()
        // 1:90 coded character set: ESC % G means UTF-8
        dataset(b, 1, 90, byteArrayOf(0x1B, 0x25, 0x47))
        // 2:00 record version 4
        dataset(b, 2, 0, byteArrayOf(0x00, 0x04))
        // carry forward every dataset we do not own so annotation keeps prior IPTC metadata
        existing?.let { carryDatasets(b, it) }
        dataset(b, 2, 120, truncateUtf8(caption, CAPTION_LIMIT_BYTES))
        return b.toByteArray()
    }

    // re-emit each IIM dataset except the ones iimCaption sets (1:90, 2:00, 2:120); mirrors
    // readCaption's parse and degrades gracefully (stops) on a malformed or extended-length stream
    private fun carryDatasets(
        b: ByteArrayBuilder,
        data: ByteArray,
    ) {
        var i = 0
        while (i + 5 <= data.size) {
            if (data.u8(i) != 0x1C) return
            val record = data.u8(i + 1)
            val datasetId = data.u8(i + 2)
            val len = data.u16be(i + 3)
            if (len and 0x8000 != 0 || i + 5 + len > data.size) return
            val owned = (record == 1 && datasetId == 90) || (record == 2 && (datasetId == 0 || datasetId == 120))
            if (!owned) dataset(b, record, datasetId, data.copyOfRange(i + 5, i + 5 + len))
            i += 5 + len
        }
    }

    private fun dataset(
        b: ByteArrayBuilder,
        record: Int,
        dataset: Int,
        data: ByteArray,
    ) {
        b.append(0x1C)
        b.append(record)
        b.append(dataset)
        b.appendU16be(data.size)
        b.append(data)
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
