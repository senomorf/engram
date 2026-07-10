package cam.engram.format.archive

/**
 * Reader for the archive's own JSON dialect (spec sec 11): exactly the subset
 * [EngramArchive] emits (flat objects, string, integer and null fields, arrays
 * of strings or flat objects). Hand-rolled so :core-format stays dependency
 * free; not a general JSON parser.
 */
object ArchiveReader {
    class Manifest(
        val manifestVersion: Int,
        val itemCount: Int,
        val files: List<EngramArchive.ManifestFile>,
    )

    class ItemView(
        val contentHash: String,
        val originalName: String,
        val recordLog: String?,
        val frameCount: Int,
        val audio: List<String>,
    )

    fun parseManifest(json: String): Manifest {
        val root = JsonParser(json).parseObject()
        return Manifest(
            manifestVersion = root.int("manifestVersion"),
            itemCount = root.int("itemCount"),
            files =
                (root["files"] as? List<*>).orEmpty().map {
                    val o = it as? Map<*, *> ?: error("files entry is not an object")
                    // v3 inventories carry sha256; v2 carried md5
                    EngramArchive.ManifestFile(
                        o.str("name"),
                        (o["sha256"] ?: o["md5"]) as? String ?: error("file hash missing"),
                    )
                },
        )
    }

    fun parseItem(json: String): ItemView {
        val root = JsonParser(json).parseObject()
        return ItemView(
            contentHash = root.str("contentHash"),
            originalName = (root["originalName"] as? String).orEmpty(),
            recordLog = root["recordLog"] as? String,
            frameCount = (root["frameCount"] as? Long)?.toInt() ?: 0,
            audio = (root["audio"] as? List<*>).orEmpty().map { it as? String ?: error("audio entry is not a string") },
        )
    }

    private fun Map<*, *>.int(key: String): Int = (this[key] as? Long)?.toInt() ?: error("$key missing or not a number")

    private fun Map<*, *>.str(key: String): String = this[key] as? String ?: error("$key missing or not a string")

    // recursive-descent over the emitted subset: objects, arrays, strings with the
    // escapes quote() writes, integers, booleans, null
    private class JsonParser(
        private val s: String,
    ) {
        private var i = 0

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = mutableMapOf<String, Any?>()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                val key = parseString()
                expect(':')
                out[key] = parseValue()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> error("unexpected '$c' at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += parseValue()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> error("unexpected '$c' at $i")
                }
            }
        }

        private fun parseValue(): Any? =
            when (val c = peek()) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                'n' -> literal("null", null)
                't' -> literal("true", true)
                'f' -> literal("false", false)
                else ->
                    if (c == '-' || c in '0'..'9') parseNumber() else error("unexpected '$c' at $i")
            }

        private fun parseNumber(): Long {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            return s.substring(start, i).toLong()
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = s.getOrNull(i++) ?: error("unterminated string")) {
                    '"' -> return sb.toString()
                    '\\' ->
                        when (val e = s.getOrNull(i++) ?: error("unterminated escape")) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("unsupported escape '$e' at $i")
                        }
                    else -> sb.append(c)
                }
            }
        }

        private fun <T> literal(
            word: String,
            value: T,
        ): T {
            require(s.regionMatches(i, word, 0, word.length)) { "expected $word at $i" }
            i += word.length
            return value
        }

        private fun peek(): Char = s.getOrNull(i) ?: error("unexpected end of input")

        private fun next(): Char = s.getOrNull(i++) ?: error("unexpected end of input")

        private fun expect(c: Char) {
            val got = next()
            require(got == c) { "expected '$c' but got '$got' at $i" }
        }
    }
}
