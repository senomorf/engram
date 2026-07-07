package photos.engram.format.mp4

import photos.engram.format.ByteArrayBuilder
import photos.engram.format.u32be

/**
 * iTunes-style caption mirror: moov/udta/meta/ilst with a (c)cmt data atom.
 * Only safe when moov is the last content box: growing an earlier moov shifts
 * mdat and silently breaks stco/co64 chunk offsets, so those layouts are
 * declined rather than risked.
 */
@Suppress("TooManyFunctions") // cohesive box-surgery unit: tiny builders per box type
object Mp4Caption {
    private val TYPE_MOOV = "moov".encodeToByteArray()
    private val TYPE_UDTA = "udta".encodeToByteArray()
    private val TYPE_META = "meta".encodeToByteArray()
    private val TYPE_HDLR = "hdlr".encodeToByteArray()
    private val TYPE_ILST = "ilst".encodeToByteArray()
    private val TYPE_DATA = "data".encodeToByteArray()

    // the comment atom four-cc starts with 0xA9, not valid utf-8 on its own
    private val TYPE_CMT = byteArrayOf(0xA9.toByte(), 0x63, 0x6D, 0x74)
    private val HANDLER_MDIR = "mdir".encodeToByteArray()
    private val HANDLER_RESERVED = "appl".encodeToByteArray()

    /** Returns rewritten bytes, or null when the layout makes a caption write unsafe. */
    fun tryWrite(
        bytes: ByteArray,
        caption: String,
    ): ByteArray? {
        val boxes = Mp4Codec.topLevel(bytes)
        val moovIdx = boxes.indexOfLast { it.type == "moov" }
        if (moovIdx < 0) return null
        // boxes after moov must be ours: they hold no offsets into the file
        if (boxes.drop(moovIdx + 1).any { !Mp4Codec.isEngramBox(it) }) return null
        val moov = boxes[moovIdx]
        val moovBytes = bytes.copyOfRange(moov.offset.toInt(), (moov.offset + moov.boxLength).toInt())
        val rebuilt = rewriteMoovBox(moovBytes, caption) ?: return null
        val out = ByteArrayBuilder(bytes.size + rebuilt.size)
        out.append(bytes, 0, moov.offset.toInt())
        out.append(rebuilt)
        out.append(bytes, (moov.offset + moov.boxLength).toInt(), bytes.size)
        return out.toByteArray()
    }

    private class Child(
        val type: ByteArray,
        val body: ByteArray,
    )

    private fun children(
        container: ByteArray,
        from: Int,
    ): List<Child>? {
        val out = mutableListOf<Child>()
        var i = from
        while (i < container.size) {
            if (i + 8 > container.size) return null
            val len = container.u32be(i)
            if (len < 8 || i + len > container.size) return null
            out += Child(container.copyOfRange(i + 4, i + 8), container.copyOfRange(i + 8, (i + len).toInt()))
            i += len.toInt()
        }
        return out
    }

    private fun box(
        type: ByteArray,
        body: ByteArray,
    ): ByteArray {
        val b = ByteArrayBuilder(8 + body.size)
        b.appendU32be(8L + body.size)
        b.append(type)
        b.append(body)
        return b.toByteArray()
    }

    private fun serialize(kids: List<Child>): ByteArray {
        val b = ByteArrayBuilder()
        kids.forEach { b.append(box(it.type, it.body)) }
        return b.toByteArray()
    }

    /** Rewrites a standalone moov box (header included); null when not editable safely. */
    fun rewriteMoovBox(
        moovBytes: ByteArray,
        caption: String,
    ): ByteArray? {
        if (moovBytes.u32be(0) == 1L) return null // largesize moov: not worth handling
        val kids = children(moovBytes, 8)?.toMutableList() ?: return null
        val udtaIdx = kids.indexOfFirst { it.type.contentEquals(TYPE_UDTA) }
        val newUdta =
            if (udtaIdx >= 0) {
                rewriteUdta(kids[udtaIdx].body, caption) ?: return null
            } else {
                buildUdta(caption)
            }
        if (udtaIdx >= 0) kids[udtaIdx] = Child(TYPE_UDTA, newUdta) else kids += Child(TYPE_UDTA, newUdta)
        return box(TYPE_MOOV, serialize(kids))
    }

    private fun rewriteUdta(
        udtaBody: ByteArray,
        caption: String,
    ): ByteArray? {
        val kids = children(udtaBody, 0)?.toMutableList() ?: return null
        val metaIdx = kids.indexOfFirst { it.type.contentEquals(TYPE_META) }
        if (metaIdx < 0) {
            kids += Child(TYPE_META, buildMetaBody(caption))
            return serialize(kids)
        }
        val metaBody = kids[metaIdx].body
        if (metaBody.size < 4) return null
        val flags = metaBody.copyOfRange(0, 4)
        val metaKids = children(metaBody, 4)?.toMutableList() ?: return null
        val hdlr = metaKids.firstOrNull { it.type.contentEquals(TYPE_HDLR) }
        // a meta owned by another handler is not ours to edit
        if (hdlr != null && !handlerIsMdir(hdlr.body)) return null
        if (hdlr == null) metaKids.add(0, Child(TYPE_HDLR, hdlrBody()))
        val ilstIdx = metaKids.indexOfFirst { it.type.contentEquals(TYPE_ILST) }
        if (ilstIdx < 0) {
            metaKids += Child(TYPE_ILST, box(TYPE_CMT, box(TYPE_DATA, dataBody(caption))))
        } else {
            val items = children(metaKids[ilstIdx].body, 0)?.toMutableList() ?: return null
            val cmtIdx = items.indexOfFirst { it.type.contentEquals(TYPE_CMT) }
            val cmt = Child(TYPE_CMT, box(TYPE_DATA, dataBody(caption)))
            if (cmtIdx >= 0) items[cmtIdx] = cmt else items += cmt
            metaKids[ilstIdx] = Child(TYPE_ILST, serialize(items))
        }
        return serialize(kids.apply { this[metaIdx] = Child(TYPE_META, flags + serialize(metaKids)) })
    }

    private fun buildUdta(caption: String): ByteArray = box(TYPE_META, buildMetaBody(caption))

    private fun buildMetaBody(caption: String): ByteArray {
        val b = ByteArrayBuilder()
        b.append(ByteArray(4)) // fullbox version and flags
        b.append(box(TYPE_HDLR, hdlrBody()))
        b.append(box(TYPE_ILST, box(TYPE_CMT, box(TYPE_DATA, dataBody(caption)))))
        return b.toByteArray()
    }

    private fun hdlrBody(): ByteArray {
        val b = ByteArrayBuilder()
        b.append(ByteArray(4)) // version and flags
        b.append(ByteArray(4)) // predefined
        b.append(HANDLER_MDIR)
        b.append(HANDLER_RESERVED)
        b.append(ByteArray(8))
        b.append(0) // empty name
        return b.toByteArray()
    }

    private fun dataBody(caption: String): ByteArray {
        val b = ByteArrayBuilder()
        b.appendU32be(1L) // type indicator: utf-8 text
        b.appendU32be(0L) // default locale
        b.append(caption.encodeToByteArray())
        return b.toByteArray()
    }

    private fun handlerIsMdir(hdlrBody: ByteArray): Boolean {
        if (hdlrBody.size < 12) return false
        return hdlrBody.copyOfRange(8, 12).contentEquals(HANDLER_MDIR)
    }

    fun readCaption(bytes: ByteArray): String? {
        val boxes = Mp4Codec.topLevel(bytes)
        val moov = boxes.lastOrNull { it.type == "moov" } ?: return null
        return readCaptionFromMoovBox(bytes.copyOfRange(moov.offset.toInt(), (moov.offset + moov.boxLength).toInt()))
    }

    /** Same as [readCaption] but takes a standalone moov box, for streaming callers. */
    fun readCaptionFromMoovBox(moovBytes: ByteArray): String? {
        val dataBody =
            children(moovBytes, 8)
                ?.firstOrNull { it.type.contentEquals(TYPE_UDTA) }
                ?.let { children(it.body, 0) }
                ?.firstOrNull { it.type.contentEquals(TYPE_META) }
                ?.body
                ?.takeIf { it.size >= 4 }
                ?.let { children(it, 4) }
                ?.firstOrNull { it.type.contentEquals(TYPE_ILST) }
                ?.let { children(it.body, 0) }
                ?.firstOrNull { it.type.contentEquals(TYPE_CMT) }
                ?.let { children(it.body, 0) }
                ?.firstOrNull { it.type.contentEquals(TYPE_DATA) }
                ?.body
        return if (dataBody == null ||
            dataBody.size < 8
        ) {
            null
        } else {
            dataBody.copyOfRange(8, dataBody.size).decodeToString()
        }
    }
}
