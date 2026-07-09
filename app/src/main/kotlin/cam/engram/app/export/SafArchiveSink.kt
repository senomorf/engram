package cam.engram.app.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF-backed [ArchiveSink] (device-only, excluded from JVM coverage and exercised
 * by androidTest). Resolves the user-chosen tree, then writes each blob fail-closed:
 * a null DocumentFile or output stream returns false instead of silently no-op.
 */
class SafArchiveSink internal constructor(
    private val context: Context,
    private val dir: DocumentFile,
) : ArchiveSink {
    override fun write(
        name: String,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            val file =
                dir.findFile(name)?.takeIf { it.isFile }
                    ?: dir.createFile("application/octet-stream", name)
                    ?: return false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)

    companion object {
        private const val FOLDER = "engram-archive"

        /** Opens (or creates) the archive folder under [treeUri]; null if it cannot. */
        fun open(
            context: Context,
            treeUri: Uri,
        ): SafArchiveSink? {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val dir =
                root.findFile(FOLDER)?.takeIf { it.isDirectory }
                    ?: root.createDirectory(FOLDER)
                    ?: return null
            return SafArchiveSink(context, dir)
        }
    }
}
