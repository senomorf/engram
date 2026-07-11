package cam.engram.format.archive

import cam.engram.format.Sha256
import cam.engram.format.read.Memory
import cam.engram.format.records.EngramRecord
import cam.engram.format.toHex

/**
 * Engram Archive (spec section 7 area, design D14): a plaintext, self-describing
 * serialization of every memory, independent of any app database. One JSON
 * document per media item plus audio blobs written beside it. This module
 * produces the JSON; the host writes files (SAF on Android, disk in the CLI).
 *
 * Minimal hand-rolled JSON so :core-format stays dependency-free and KMP-safe.
 */
object EngramArchive {
    const val MANIFEST_VERSION = 3

    class Item(
        val contentHashHex: String,
        val originalName: String,
        val records: List<EngramRecord>,
        // byte-exact CRC-valid frames in log order, typed and opaque alike: the
        // authoritative record log (spec sec 11); the JSON is a readable view of it
        val rawFrames: List<ByteArray> = emptyList(),
        // false when the source media hash was unknowable (a legacy pre-hash orphan):
        // the entry is then named by its record log's hash instead (spec sec 11)
        val sourceHashKnown: Boolean = true,
    )

    class AudioBlob(
        val fileName: String,
        val data: ByteArray,
    )

    class Rendered(
        val json: String,
        val audio: List<AudioBlob>,
        /** concatenated wire frames for the .records sidecar; null when the item carries none */
        val recordLog: ByteArray? = null,
        val recordLogName: String? = null,
    )

    class ManifestFile(
        val name: String,
        val hash: String,
    )

    /**
     * Manifest v3: the archive inventory. Every written file is listed with its
     * sha-256 so a validator can prove the archive complete without guessing from
     * directory contents (v2 used md5). Fields are append-only (spec sec 11).
     */
    fun manifest(
        itemCount: Int,
        files: List<ManifestFile> = emptyList(),
    ): String =
        buildJson {
            field("archive", "engram")
            field("manifestVersion", MANIFEST_VERSION.toString(), raw = true)
            field("itemCount", itemCount.toString(), raw = true)
            arrayField("files", files) { """{"name":${quote(it.name)},"sha256":${quote(it.hash)}}""" }
        }

    /** Renders one item's JSON view, its audio blobs, and its byte-exact record log. */
    fun render(item: Item): Rendered {
        val memory = Memory.fromRecords(item.records)
        val audioBlobs = mutableListOf<AudioBlob>()
        memory.audio.forEachIndexed { i, clip ->
            val ext = if (clip.mime.contains("mp4") || clip.mime.contains("aac")) "m4a" else "ogg"
            audioBlobs += AudioBlob("${item.contentHashHex}_$i.$ext", clip.data)
        }
        val recordLog =
            if (item.rawFrames.isEmpty()) null else item.rawFrames.fold(ByteArray(0)) { acc, f -> acc + f }
        val recordLogName = recordLog?.let { "${item.contentHashHex}.records" }
        val json =
            buildJson {
                field("contentHash", item.contentHashHex)
                field("originalName", item.originalName)
                rawField("currentNote", memory.currentNote?.let { quote(it.text) } ?: "null")
                arrayField("noteHistory", memory.noteHistory) { quote(it.text) }
                arrayField("transcripts", memory.transcripts) { quote(it.text) }
                objectField("enrichment", memory.enrichment)
                arrayField("audio", audioBlobs) { quote(it.fileName) }
                rawField("recordLog", recordLogName?.let { quote(it) } ?: "null")
                rawField("frameCount", item.rawFrames.size.toString())
                // emitted only when false so every existing archive's JSON stays byte-stable
                if (!item.sourceHashKnown) rawField("sourceHashKnown", "false")
            }
        return Rendered(json, audioBlobs, recordLog, recordLogName)
    }

    private class JsonBuilder {
        private val sb = StringBuilder("{")
        private var first = true

        private fun comma() {
            if (!first) sb.append(",")
            first = false
        }

        fun field(
            key: String,
            value: String,
            raw: Boolean = false,
        ) {
            comma()
            sb.append(quote(key)).append(":").append(if (raw) value else quote(value))
        }

        fun rawField(
            key: String,
            rawValue: String,
        ) {
            comma()
            sb.append(quote(key)).append(":").append(rawValue)
        }

        fun <T> arrayField(
            key: String,
            items: List<T>,
            render: (T) -> String,
        ) {
            comma()
            sb.append(quote(key)).append(":[")
            items.forEachIndexed { i, item ->
                if (i > 0) sb.append(",")
                sb.append(render(item))
            }
            sb.append("]")
        }

        fun objectField(
            key: String,
            map: Map<String, String>,
        ) {
            comma()
            sb.append(quote(key)).append(":{")
            var f = true
            for ((k, v) in map) {
                if (!f) sb.append(",")
                f = false
                sb.append(quote(k)).append(":").append(quote(v))
            }
            sb.append("}")
        }

        fun build(): String = sb.append("}").toString()
    }

    private fun buildJson(block: JsonBuilder.() -> Unit): String = JsonBuilder().apply(block).build()

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u" + c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    // a real digest, not size+prefix: distinct files never collide on their archive
    // JSON/audio filenames (review F13). Sha-256 (was md5) so the name can double as
    // an import identity key (D28); cache rows hashed before the switch refresh on
    // their next scan, and their old names stay valid within their own archives.
    fun contentHashName(bytes: ByteArray): String = Sha256.of(bytes).toHex()
}
