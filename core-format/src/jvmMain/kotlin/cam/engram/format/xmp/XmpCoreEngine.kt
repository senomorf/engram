package cam.engram.format.xmp

import cam.engram.format.Md5
import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPUtils
import com.adobe.internal.xmp.options.SerializeOptions

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
        // copy every localized description (all languages), not just x-default: a foreign multi-
        // language caption must survive the ExtendedXMP split, and dc:description must stay in the
        // standard packet so it reads without ExtendedXMP support. meta already carries the mirror
        // applied as x-default plus any merged foreign languages, so copying from it is complete.
        copyDescriptions(meta, standard)
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

    // copy the whole dc:description alt-array (every xml:lang) from [from] into [to], so an
    // ExtendedXMP split keeps foreign localized captions, not only x-default (reviewer)
    private fun copyDescriptions(
        from: XMPMeta,
        to: XMPMeta,
    ) {
        val count =
            try {
                from.countArrayItems(XMPConst.NS_DC, "description")
            } catch (e: XMPException) {
                // no description array to copy
                0
            }
        // Copy the specific languages via setLocalizedText, then write x-default directly on its node.
        // setLocalizedText auto-sets x-default to the first specific language it sees and re-syncs a
        // matching one, which would clobber the real x-default value; setArrayItem does not sync.
        var xDefault: String? = null
        for (i in 1..count) {
            val value =
                try {
                    from.getArrayItem(XMPConst.NS_DC, "description", i).value
                } catch (e: XMPException) {
                    // skip an unreadable item, keep copying the rest
                    null
                }
            if (value != null) {
                val lang = langOf(from, i)
                if (lang == "x-default") xDefault = value else trySetLocalized(to, lang, value)
            }
        }
        xDefault?.let { writeXDefault(to, it) }
    }

    private fun langOf(
        meta: XMPMeta,
        index: Int,
    ): String =
        (
            try {
                meta.getQualifier(XMPConst.NS_DC, "description[$index]", NS_XML, "lang")?.value
            } catch (e: XMPException) {
                // no xml:lang qualifier: treat as x-default
                null
            }
        ) ?: "x-default"

    private fun trySetLocalized(
        to: XMPMeta,
        lang: String,
        value: String,
    ) {
        try {
            to.setLocalizedText(XMPConst.NS_DC, "description", null, lang, value)
        } catch (e: XMPException) {
            // a malformed alt item is skipped, not fatal to the whole split
        }
    }

    // set x-default's value on its existing langAlt node (no setLocalizedText x-default sync), or
    // create it directly when no other language was written
    private fun writeXDefault(
        to: XMPMeta,
        value: String,
    ) {
        val n =
            try {
                to.countArrayItems(XMPConst.NS_DC, "description")
            } catch (e: XMPException) {
                // no array yet
                0
            }
        val idx = (1..n).firstOrNull { langOf(to, it) == "x-default" }
        if (idx == null) {
            trySetLocalized(to, "x-default", value)
            return
        }
        try {
            to.setArrayItem(XMPConst.NS_DC, "description", idx, value)
        } catch (e: XMPException) {
            // fall back to the localized setter if the direct set is rejected
            trySetLocalized(to, "x-default", value)
        }
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

        // the xml:lang qualifier namespace on dc:description alt-array items
        const val NS_XML = "http://www.w3.org/XML/1998/namespace"
    }
}
