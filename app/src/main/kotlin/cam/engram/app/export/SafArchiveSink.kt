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
        private const val FOLDER_PREFIX = "engram-archive"

        /**
         * Creates a fresh archive folder under [treeUri]; null if it cannot. Each
         * export gets its own directory so runs never mix and every manifest stays
         * authoritative for exactly the files beside it (D28).
         */
        fun open(
            context: Context,
            treeUri: Uri,
            clock: () -> Long = System::currentTimeMillis,
        ): SafArchiveSink? = DocumentFile.fromTreeUri(context, treeUri)?.let { openIn(context, it, clock) }

        // seam for the instrumented test: a raw DocumentFile root stands in for the tree
        internal fun openIn(
            context: Context,
            root: DocumentFile,
            clock: () -> Long,
        ): SafArchiveSink? {
            val dir = root.createDirectory("$FOLDER_PREFIX-${clock()}") ?: return null
            return SafArchiveSink(context, dir)
        }
    }
}
