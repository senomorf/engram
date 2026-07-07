package photos.engram.format.xmp

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPUtils
import com.adobe.internal.xmp.options.SerializeOptions
import photos.engram.format.Md5

class XmpCoreEngine : XmpEngine {
    init {
        Registry.ensure()
    }

    override fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: XmpUpdate,
        standardLimitBytes: Int,
    ): XmpApplyResult {
        val meta =
            existingStandard?.takeIf { it.isNotBlank() }?.let { parseStrict(it, "standard") } ?: XMPMetaFactory.create()
        existingExtended?.takeIf { it.isNotBlank() }?.let { ext ->
            val extMeta = parseStrict(ext, "extended")
            try {
                XMPUtils.appendProperties(extMeta, meta, true, false)
            } catch (e: XMPException) {
                throw XmpWriteException("cannot merge existing extended xmp", e)
            }
        }
        safeDelete(meta, XMP_NOTE_NAMESPACE, HAS_EXTENDED_XMP)
        update.mirrorDescription?.let {
            meta.setLocalizedText(XMPConst.NS_DC, "description", null, "x-default", it)
        }
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "SpecVersion", ENGRAM_SPEC_VERSION)
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "PayloadLength", update.payloadLength.toString())
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "RecordCount", update.recordCount.toString())
        val single = serialize(meta, PADDING)
        if (single.encodeToByteArray().size <= standardLimitBytes) {
            return XmpApplyResult(single, null)
        }
        return split(meta, update, standardLimitBytes)
    }

    private fun split(
        meta: XMPMeta,
        update: XmpUpdate,
        standardLimitBytes: Int,
    ): XmpApplyResult {
        // extended part: everything except the essentials that must stay readable
        // without ExtendedXMP support (description, engram props, the guid link)
        val extended =
            try {
                XMPMetaFactory.parseFromString(serialize(meta, 0))
            } catch (e: XMPException) {
                throw XmpWriteException("internal: cannot clone xmp for split", e)
            }
        safeDelete(extended, XMPConst.NS_DC, "description")
        safeDelete(extended, ENGRAM_XMP_NAMESPACE, "SpecVersion")
        safeDelete(extended, ENGRAM_XMP_NAMESPACE, "PayloadLength")
        safeDelete(extended, ENGRAM_XMP_NAMESPACE, "RecordCount")
        safeDelete(extended, XMP_NOTE_NAMESPACE, HAS_EXTENDED_XMP)
        val extendedStr =
            try {
                val opts = SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(true)
                XMPMetaFactory.serializeToString(extended, opts)
            } catch (e: XMPException) {
                throw XmpWriteException("internal: cannot serialize extended xmp", e)
            }
        val guid = Md5.hexUpper(Md5.of(extendedStr.encodeToByteArray()))
        val standard = XMPMetaFactory.create()
        val desc =
            update.mirrorDescription
                ?: try {
                    meta.getLocalizedText(XMPConst.NS_DC, "description", null, "x-default")?.value
                } catch (e: XMPException) {
                    null
                }
        desc?.let { standard.setLocalizedText(XMPConst.NS_DC, "description", null, "x-default", it) }
        standard.setProperty(ENGRAM_XMP_NAMESPACE, "SpecVersion", ENGRAM_SPEC_VERSION)
        standard.setProperty(ENGRAM_XMP_NAMESPACE, "PayloadLength", update.payloadLength.toString())
        standard.setProperty(ENGRAM_XMP_NAMESPACE, "RecordCount", update.recordCount.toString())
        standard.setProperty(XMP_NOTE_NAMESPACE, HAS_EXTENDED_XMP, guid)
        val stdStr = serialize(standard, PADDING)
        if (stdStr.encodeToByteArray().size > standardLimitBytes) {
            throw XmpWriteException("standard xmp exceeds segment limit even after ExtendedXMP split")
        }
        return XmpApplyResult(stdStr, extendedStr)
    }

    override fun read(packet: String): XmpSummary {
        val meta =
            try {
                XMPMetaFactory.parseFromString(packet)
            } catch (e: XMPException) {
                return XmpSummary(false, null, null, null, null, null)
            }

        fun prop(
            ns: String,
            name: String,
        ): String? =
            try {
                meta.getPropertyString(ns, name)
            } catch (e: XMPException) {
                null
            }

        val desc =
            try {
                meta.getLocalizedText(XMPConst.NS_DC, "description", null, "x-default")?.value
            } catch (e: XMPException) {
                null
            }
        val spec = prop(ENGRAM_XMP_NAMESPACE, "SpecVersion")
        return XmpSummary(
            hasEngram = spec != null,
            specVersion = spec,
            payloadLength = prop(ENGRAM_XMP_NAMESPACE, "PayloadLength")?.toLongOrNull(),
            recordCount = prop(ENGRAM_XMP_NAMESPACE, "RecordCount")?.toIntOrNull(),
            description = desc,
            extendedXmpGuid = prop(XMP_NOTE_NAMESPACE, HAS_EXTENDED_XMP),
        )
    }

    private fun parseStrict(
        packet: String,
        label: String,
    ): XMPMeta =
        try {
            XMPMetaFactory.parseFromString(packet)
        } catch (e: XMPException) {
            // fail closed: silently replacing an unparseable camera packet would
            // destroy Motion Photo, HDR and other foreign metadata (review F1)
            throw XmpWriteException("existing $label xmp packet does not parse, refusing to overwrite it", e)
        }

    private fun serialize(
        meta: XMPMeta,
        padding: Int,
    ): String =
        try {
            XMPMetaFactory.serializeToString(meta, SerializeOptions().setUseCompactFormat(true).setPadding(padding))
        } catch (e: XMPException) {
            throw XmpWriteException("cannot serialize xmp", e)
        }

    private fun safeDelete(
        meta: XMPMeta,
        ns: String,
        name: String,
    ) {
        try {
            meta.deleteProperty(ns, name)
        } catch (e: XMPException) {
            // property absent or schema unknown: nothing to delete
        }
    }

    private object Registry {
        init {
            XMPMetaFactory.getSchemaRegistry().registerNamespace(ENGRAM_XMP_NAMESPACE, ENGRAM_XMP_PREFIX)
            XMPMetaFactory.getSchemaRegistry().registerNamespace(XMP_NOTE_NAMESPACE, XMP_NOTE_PREFIX)
        }

        fun ensure() = Unit
    }

    private companion object {
        const val PADDING = 2048
    }
}
