package photos.engram.format

object Crc32 {
    private val table =
        UIntArray(256) { i ->
            var c = i.toUInt()
            repeat(8) { c = if (c and 1u != 0u) 0xEDB88320u xor (c shr 1) else c shr 1 }
            c
        }

    fun of(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): Long {
        var c = 0xFFFFFFFFu
        for (i in from until until) {
            c = table[((c xor bytes[i].toUByte().toUInt()) and 0xFFu).toInt()] xor (c shr 8)
        }
        return (c xor 0xFFFFFFFFu).toLong()
    }
}
