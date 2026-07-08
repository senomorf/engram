package cam.engram.app

import cam.engram.app.data.media.ContentAccess
import java.io.File
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel

/** In-memory ContentResolver stand-in shared by unit tests. */
class FakeContentAccess : ContentAccess {
    val files = mutableMapOf<String, ByteArray>()
    var rejectWrites = false
    var corruptWrites = false

    override fun readBytes(uri: String): ByteArray? = files[uri]

    override fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T? {
        val bytes = files[uri] ?: return null
        val tmp = File.createTempFile("fake", ".bin")
        return try {
            tmp.writeBytes(bytes)
            FileInputStream(tmp).channel.use(block)
        } finally {
            tmp.delete()
        }
    }

    override fun writeBytes(
        uri: String,
        bytes: ByteArray,
    ): Boolean {
        if (rejectWrites) return false
        files[uri] = if (corruptWrites) ByteArray(4) { 0x11 } else bytes
        return true
    }

    override fun copyToFile(
        uri: String,
        target: File,
    ): Boolean {
        val bytes = files[uri] ?: return false
        target.writeBytes(bytes)
        return true
    }

    override fun writeFromFile(
        uri: String,
        source: File,
    ): Boolean {
        if (rejectWrites) return false
        files[uri] = source.readBytes()
        return true
    }
}
