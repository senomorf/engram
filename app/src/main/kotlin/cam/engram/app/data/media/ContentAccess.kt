package cam.engram.app.data.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel

/**
 * Result of writing to a content:// target. The content write cannot be atomic (opening
 * in "wt" truncates before the bytes land), so the caller must tell an untouched target
 * apart from a truncated-but-incomplete one to protect the never-lose-the-photo invariant.
 */
sealed interface WriteResult {
    /** the output stream never opened, so the target is byte-for-byte untouched */
    data object NotOpened : WriteResult

    /** the stream opened (target already truncated) but the write did not complete */
    data object OpenedUncertain : WriteResult

    data object Ok : WriteResult
}

/** Thin seam over ContentResolver so scanners and pipelines are testable off-device. */
interface ContentAccess {
    fun readBytes(uri: String): ByteArray?

    fun <T> withChannel(
        uri: String,
        block: (SeekableByteChannel) -> T,
    ): T?

    /** Truncates the target then writes [bytes]; the result distinguishes untouched from partial. */
    fun writeBytes(
        uri: String,
        bytes: ByteArray,
    ): WriteResult

    /** Copies the uri into [target] and fsyncs it; false when the copy did not complete. */
    fun copyToFile(
        uri: String,
        target: File,
    ): Boolean

    fun writeFromFile(
        uri: String,
        source: File,
    ): WriteResult
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
    ): WriteResult {
        // open failure (missing consent, provider death) leaves the target untouched
        val out =
            runCatching { resolver.openOutputStream(Uri.parse(uri), "wt") }.getOrNull()
                ?: return WriteResult.NotOpened
        // the target is now truncated: any failure past this point is uncertain, not untouched
        return runCatching {
            out.use { it.write(bytes) }
            WriteResult.Ok
        }.getOrDefault(WriteResult.OpenedUncertain)
    }

    override fun copyToFile(
        uri: String,
        target: File,
    ): Boolean =
        runCatching {
            resolver.openInputStream(readUri(uri))?.use { input ->
                target.outputStream().use { out ->
                    input.copyTo(out)
                    // fsync so a crash cannot leave the backup unflushed before the destructive write
                    out.fd.sync()
                }
            } != null
        }.getOrDefault(false)

    override fun writeFromFile(
        uri: String,
        source: File,
    ): WriteResult {
        val out =
            runCatching { resolver.openOutputStream(Uri.parse(uri), "wt") }.getOrNull()
                ?: return WriteResult.NotOpened
        return runCatching {
            out.use { source.inputStream().use { inp -> inp.copyTo(out) } }
            WriteResult.Ok
        }.getOrDefault(WriteResult.OpenedUncertain)
    }
}
