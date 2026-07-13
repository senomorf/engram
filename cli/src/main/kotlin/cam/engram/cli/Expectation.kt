package cam.engram.cli

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Line-based sidecar written by generate and consumed by verify. Records what
 * was planted so survivability runs can judge exact / degraded / gone without
 * the original file.
 */
internal data class Expectation(
    val container: String,
    val noteText: String?,
    val noteId: String?,
    val audioId: String?,
    val audioMime: String?,
    val audioSha256: String?,
    val recordCount: Int,
    val mpfExpected: String,
    val extendedExpected: Boolean,
    // every record id present at generate time; absent in legacy sidecars (count-only)
    val ids: List<String> = emptyList(),
) {
    fun write(file: File) {
        val lines = mutableListOf("engram-expect=1", "container=$container", "records=$recordCount")
        if (ids.isNotEmpty()) lines += "ids=" + ids.joinToString(",")
        noteText?.let { lines += "note.b64=" + Base64.getEncoder().encodeToString(it.encodeToByteArray()) }
        noteId?.let { lines += "note.id=$it" }
        audioId?.let { lines += "audio.id=$it" }
        audioMime?.let { lines += "audio.mime=$it" }
        audioSha256?.let { lines += "audio.sha256=$it" }
        lines += "mpf=$mpfExpected"
        lines += "extended=$extendedExpected"
        file.writeText(lines.joinToString("\n") + "\n")
    }

    companion object {
        const val SUFFIX = ".engram-expect"

        fun read(file: File): Expectation {
            val pairs =
                file
                    .readLines()
                    .filter { it.contains('=') }
                    .map { it.substringBefore('=') to it.substringAfter('=') }
            // reject duplicate keys: a corrupted or hand-edited sidecar must fail closed, not let a
            // later line silently shadow an earlier one (finding F6)
            val duplicates =
                pairs
                    .groupingBy { it.first }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            require(
                duplicates.isEmpty(),
            ) { "duplicate keys in expectation ${file.path}: ${duplicates.joinToString(",")}" }
            val map = pairs.toMap()
            require(map["engram-expect"] == "1") { "not an engram-expect file: ${file.path}" }
            // every field generate writes unconditionally must be present, so a truncated sidecar
            // fails closed instead of yielding an empty baseline that reports a readable file intact
            // (finding F6)
            val container = requireNotNull(map["container"]) { "expectation missing container: ${file.path}" }
            val recordCount =
                map["records"]?.toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("expectation missing or malformed records: ${file.path}")
            val mpf = requireNotNull(map["mpf"]) { "expectation missing mpf: ${file.path}" }
            val extended =
                map["extended"]?.takeIf { it == "true" || it == "false" }
                    ?: throw IllegalArgumentException("expectation missing or malformed extended: ${file.path}")
            val ids = map["ids"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            // a present id list must match the record count, or the sidecar was truncated mid-list
            require(ids.isEmpty() || ids.size == recordCount) {
                "expectation ids (${ids.size}) inconsistent with records ($recordCount): ${file.path}"
            }
            return Expectation(
                container = container,
                noteText = map["note.b64"]?.let { Base64.getDecoder().decode(it).decodeToString() },
                noteId = map["note.id"],
                audioId = map["audio.id"],
                audioMime = map["audio.mime"],
                audioSha256 = map["audio.sha256"],
                recordCount = recordCount,
                mpfExpected = mpf,
                extendedExpected = extended == "true",
                ids = ids,
            )
        }
    }
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
