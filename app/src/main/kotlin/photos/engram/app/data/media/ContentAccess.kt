package photos.engram.app.data.media

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel

/** Thin seam over ContentResolver so scanners and pipelines are testable off-device. */
interface ContentAccess {
    fun readBytes(uri: String): ByteArray?

    fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T?
}

class ResolverContentAccess(
    private val resolver: ContentResolver,
) : ContentAccess {
    override fun readBytes(uri: String): ByteArray? =
        runCatching {
            resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }.getOrNull()

    override fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T? =
        runCatching {
            resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use(block)
            }
        }.getOrNull()
}
