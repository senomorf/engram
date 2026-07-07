package photos.engram.format.xmp

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.SerializeOptions

class XmpCoreEngine : XmpEngine {
    init {
        Registry.ensure()
    }

    override fun apply(
        existingPacket: String?,
        update: XmpUpdate,
    ): String {
        val meta = existingPacket?.takeIf { it.isNotBlank() }?.let { parseLenient(it) } ?: XMPMetaFactory.create()
        update.mirrorDescription?.let {
            meta.setLocalizedText(XMPConst.NS_DC, "description", null, "x-default", it)
        }
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "SpecVersion", ENGRAM_SPEC_VERSION)
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "PayloadLength", update.payloadLength.toString())
        meta.setProperty(ENGRAM_XMP_NAMESPACE, "RecordCount", update.recordCount.toString())
        val opts = SerializeOptions().setUseCompactFormat(true).setPadding(PADDING)
        return XMPMetaFactory.serializeToString(meta, opts)
    }

    override fun read(packet: String): XmpSummary {
        val meta =
            try {
                XMPMetaFactory.parseFromString(packet)
            } catch (e: XMPException) {
                return XmpSummary(false, null, null, null, null)
            }

        fun prop(name: String): String? =
            try {
                meta.getPropertyString(ENGRAM_XMP_NAMESPACE, name)
            } catch (e: XMPException) {
                null
            }

        val desc =
            try {
                meta.getLocalizedText(XMPConst.NS_DC, "description", null, "x-default")?.value
            } catch (e: XMPException) {
                null
            }
        val spec = prop("SpecVersion")
        return XmpSummary(
            hasEngram = spec != null,
            specVersion = spec,
            payloadLength = prop("PayloadLength")?.toLongOrNull(),
            recordCount = prop("RecordCount")?.toIntOrNull(),
            description = desc,
        )
    }

    private fun parseLenient(packet: String): XMPMeta =
        try {
            XMPMetaFactory.parseFromString(packet)
        } catch (e: XMPException) {
            // unparseable foreign packet: preserving it would need raw-XML surgery,
            // v0 chooses a fresh packet over failing the whole write
            XMPMetaFactory.create()
        }

    private object Registry {
        init {
            XMPMetaFactory.getSchemaRegistry().registerNamespace(ENGRAM_XMP_NAMESPACE, ENGRAM_XMP_PREFIX)
        }

        fun ensure() = Unit
    }

    private companion object {
        const val PADDING = 2048
    }
}
