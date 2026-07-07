package photos.engram.cli

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
) {
    fun write(file: File) {
        val lines = mutableListOf("engram-expect=1", "container=$container", "records=$recordCount")
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
            val map =
                file
                    .readLines()
                    .filter { it.contains('=') }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            require(map["engram-expect"] == "1") { "not an engram-expect file: ${file.path}" }
            return Expectation(
                container = map["container"] ?: "unknown",
                noteText = map["note.b64"]?.let { Base64.getDecoder().decode(it).decodeToString() },
                noteId = map["note.id"],
                audioId = map["audio.id"],
                audioMime = map["audio.mime"],
                audioSha256 = map["audio.sha256"],
                recordCount = map["records"]?.toIntOrNull() ?: 0,
                mpfExpected = map["mpf"] ?: "absent",
                extendedExpected = map["extended"] == "true",
            )
        }
    }
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
