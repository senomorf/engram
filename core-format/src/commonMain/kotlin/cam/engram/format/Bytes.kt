package cam.engram.format

fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()

fun ByteArray.u64be(at: Int): Long = (u32be(at) shl 32) or u32be(at + 4)

// in-place u32 write honoring the given endianness (MPF entries may be II little or MM big)
fun ByteArray.setU32(
    at: Int,
    value: Long,
    little: Boolean,
) {
    if (little) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
        this[at + 2] = (value ushr 16).toByte()
        this[at + 3] = (value ushr 24).toByte()
    } else {
        this[at] = (value ushr 24).toByte()
        this[at + 1] = (value ushr 16).toByte()
        this[at + 2] = (value ushr 8).toByte()
        this[at + 3] = value.toByte()
    }
}

fun ByteArray.startsWith(
    prefix: ByteArray,
    at: Int = 0,
): Boolean {
    if (at < 0 || at + prefix.size > size) return false
    for (i in prefix.indices) if (this[at + i] != prefix[i]) return false
    return true
}

class ByteArrayBuilder(
    initialCapacity: Int = 64,
) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size * 2
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun append(b: Int): ByteArrayBuilder {
        ensure(1)
        buf[size++] = b.toByte()
        return this
    }

    fun append(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
    ): ByteArrayBuilder {
        ensure(until - from)
        bytes.copyInto(buf, size, from, until)
        size += until - from
        return this
    }

    fun appendU16be(v: Int): ByteArrayBuilder = append(v ushr 8).append(v)

    fun appendU32be(v: Long): ByteArrayBuilder {
        appendU16be((v ushr 16).toInt())
        appendU16be((v and 0xFFFF).toInt())
        return this
    }

    fun appendU64be(v: Long): ByteArrayBuilder {
        appendU32be(v ushr 32)
        appendU32be(v and 0xFFFFFFFFL)
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
