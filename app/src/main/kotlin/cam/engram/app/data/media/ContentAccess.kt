package cam.engram.app.data.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
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
    // when true (the app holds ACCESS_MEDIA_LOCATION), reads request the unredacted
    // original so backup, exif and export see the real GPS instead of scoped-storage
    // redacted bytes; annotating a camera photo then no longer strips its location
    private val requireOriginal: () -> Boolean = { false },
) : ContentAccess {
    // setRequireOriginal throws UnsupportedOperationException when the provider cannot
    // supply an original; fall back to the plain uri so reads still work
    private fun readUri(uri: String): Uri {
        val base = Uri.parse(uri)
        return if (requireOriginal()) {
            runCatching { MediaStore.setRequireOriginal(base) }.getOrDefault(base)
        } else {
            base
        }
    }

    override fun readBytes(uri: String): ByteArray? =
        runCatching {
            resolver.openInputStream(readUri(uri))?.use { it.readBytes() }
        }.getOrNull()

    override fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T? =
        runCatching {
            resolver.openFileDescriptor(readUri(uri), "r")?.use { pfd ->
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
            resolver.openInputStream(readUri(uri))?.use { input ->
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
