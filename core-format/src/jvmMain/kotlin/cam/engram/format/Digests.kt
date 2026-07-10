package cam.engram.format

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Channel adapters for the pure-Kotlin digests: hash big media without loading it. */
object Digests {
    private const val CHUNK = 64 * 1024

    fun sha256Hex(ch: SeekableByteChannel): String {
        val sha = Sha256()
        val buf = ByteBuffer.allocate(CHUNK)
        ch.position(0)
        while (true) {
            buf.clear()
            val n = ch.read(buf)
            if (n < 0) break
            sha.update(buf.array(), 0, n)
        }
        return sha.digest().toHex()
    }
}
