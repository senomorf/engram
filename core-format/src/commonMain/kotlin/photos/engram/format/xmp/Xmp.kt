package photos.engram.format.xmp

// Frozen before the first real photo is written (design doc D8): the namespace
// URI is permanent once files exist.
const val ENGRAM_XMP_NAMESPACE = "https://ns.engram.photos/1.0/"
const val ENGRAM_XMP_PREFIX = "engram"
const val ENGRAM_SPEC_VERSION = "0.1"

// Adobe xmpNote namespace carrying the HasExtendedXMP guid link.
const val XMP_NOTE_NAMESPACE = "http://ns.adobe.com/xmp/note/"
const val XMP_NOTE_PREFIX = "xmpNote"
const val HAS_EXTENDED_XMP = "HasExtendedXMP"

/** Thrown when a write cannot proceed without risking loss of existing metadata. */
class XmpWriteException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class XmpUpdate(
    val mirrorDescription: String?,
    val payloadLength: Long,
    val recordCount: Int,
)

/** [extendedPacket] is set when the merged packet did not fit [XmpEngine.apply]'s limit. */
class XmpApplyResult(
    val standardPacket: String,
    val extendedPacket: String?,
)

class XmpSummary(
    val hasEngram: Boolean,
    val specVersion: String?,
    val payloadLength: Long?,
    val recordCount: Int?,
    val description: String?,
    val extendedXmpGuid: String?,
)

interface XmpEngine {
    /**
     * Merges engram properties into the existing packet(s), preserving foreign
     * properties. Fails closed with [XmpWriteException] when existing metadata
     * cannot be parsed: silently dropping camera XMP is never acceptable.
     * When the merged packet exceeds [standardLimitBytes], non-essential
     * properties move to the returned extended packet (ExtendedXMP).
     */
    fun apply(
        existingStandard: String?,
        existingExtended: String?,
        update: XmpUpdate,
        standardLimitBytes: Int,
    ): XmpApplyResult

    fun read(packet: String): XmpSummary
}
