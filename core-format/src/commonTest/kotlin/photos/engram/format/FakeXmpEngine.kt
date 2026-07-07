package photos.engram.format

import photos.engram.format.xmp.XmpEngine
import photos.engram.format.xmp.XmpSummary
import photos.engram.format.xmp.XmpUpdate

/** Line-based stand-in: exercises container plumbing without xmpcore. */
class FakeXmpEngine : XmpEngine {
    override fun apply(
        existingPacket: String?,
        update: XmpUpdate,
    ): String {
        val preserved =
            existingPacket
                ?.lineSequence()
                ?.filterNot { it.startsWith("desc=") || it.startsWith("len=") || it.startsWith("cnt=") }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                .orEmpty()
        return buildString {
            if (preserved.isNotBlank()) appendLine(preserved)
            appendLine("desc=${update.mirrorDescription.orEmpty()}")
            appendLine("len=${update.payloadLength}")
            append("cnt=${update.recordCount}")
        }
    }

    override fun read(packet: String): XmpSummary {
        fun v(key: String) = packet.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
        val len = v("len")?.toLongOrNull()
        return XmpSummary(len != null, if (len != null) "fake" else null, len, v("cnt")?.toIntOrNull(), v("desc"))
    }
}
