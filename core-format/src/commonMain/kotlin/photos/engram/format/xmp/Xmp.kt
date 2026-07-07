package photos.engram.format.xmp

// Frozen before the first real photo is written (design doc D8): the namespace
// URI is permanent once files exist.
const val ENGRAM_XMP_NAMESPACE = "https://ns.engram.photos/1.0/"
const val ENGRAM_XMP_PREFIX = "engram"
const val ENGRAM_SPEC_VERSION = "0.1"

class XmpUpdate(
    val mirrorDescription: String?,
    val payloadLength: Long,
    val recordCount: Int,
)

class XmpSummary(
    val hasEngram: Boolean,
    val specVersion: String?,
    val payloadLength: Long?,
    val recordCount: Int?,
    val description: String?,
)

interface XmpEngine {
    /** Merges engram properties into an existing packet, preserving foreign properties. */
    fun apply(existingPacket: String?, update: XmpUpdate): String

    fun read(packet: String): XmpSummary
}
