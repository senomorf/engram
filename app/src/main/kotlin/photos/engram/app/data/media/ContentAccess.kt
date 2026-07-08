package photos.engram.app.data.media

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel

/** Thin seam over ContentResolver so scanners and pipelines are testable off-device. */
interface ContentAccess {
    fun readBytes(uri: String): ByteArray?

    fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T?

    /** Truncates the target and writes [bytes]; false when the write failed. */
    fun writeBytes(
        uri: String,
        bytes: ByteArray,
    ): Boolean

    fun copyToFile(
        uri: String,
        target: File,
    ): Boolean

    fun writeFromFile(
        uri: String,
        source: File,
    ): Boolean
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

    override fun writeBytes(
        uri: String,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            resolver.openOutputStream(Uri.parse(uri), "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)

    override fun copyToFile(
        uri: String,
        target: File,
    ): Boolean =
        runCatching {
            resolver.openInputStream(Uri.parse(uri))?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)

    override fun writeFromFile(
        uri: String,
        source: File,
    ): Boolean =
        runCatching {
            resolver.openOutputStream(Uri.parse(uri), "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)
}
