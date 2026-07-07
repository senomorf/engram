package photos.engram.format

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = HEX_DIGITS.indexOf(this[i * 2].lowercaseChar())
        val lo = HEX_DIGITS.indexOf(this[i * 2 + 1].lowercaseChar())
        require(hi >= 0 && lo >= 0) { "bad hex digit" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
