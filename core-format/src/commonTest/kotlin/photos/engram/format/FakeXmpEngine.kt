package photos.engram.format

import photos.engram.format.xmp.XmpApplyResult
import photos.engram.format.xmp.XmpEngine
import photos.engram.format.xmp.XmpSummary
import photos.engram.format.xmp.XmpUpdate

/** Line-based stand-in: exercises container plumbing without xmpcore. Never splits. */
class FakeXmpEngine : XmpEngine {
    override fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: XmpUpdate,
        standardLimitBytes: Int,
    ): XmpApplyResult {
        val preserved =
            listOfNotNull(existingStandard, existingExtended)
                .joinToString("\n")
                .lineSequence()
                .filterNot { it.startsWith("desc=") || it.startsWith("len=") || it.startsWith("cnt=") }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        val packet =
            buildString {
                if (preserved.isNotBlank()) appendLine(preserved)
                appendLine("desc=${update.mirrorDescription.orEmpty()}")
                appendLine("len=${update.payloadLength}")
                append("cnt=${update.recordCount}")
            }
        return XmpApplyResult(packet, null)
    }

    override fun read(packet: String): XmpSummary {
        fun v(key: String) = packet.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
        val len = v("len")?.toLongOrNull()
        return XmpSummary(
            hasEngram = len != null,
            specVersion = if (len != null) "fake" else null,
            payloadLength = len,
            recordCount = v("cnt")?.toIntOrNull(),
            description = v("desc"),
            extendedXmpGuid = v("extguid"),
        )
    }
}
