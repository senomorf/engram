package cam.engram.app.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cam.engram.app.writeback.ShelvedSink
import java.io.OutputStream

/**
 * SAF-backed [ShelvedSink] (device-only, excluded from JVM coverage like [SafArchiveSink]):
 * streams shelved backups into a user-chosen folder. Streaming, not byte arrays, because a
 * shelved backup can be a full-size video.
 */
class SafShelvedSink internal constructor(
    private val context: Context,
    private val dir: DocumentFile,
) : ShelvedSink {
    override fun open(
        name: String,
        mimeType: String,
    ): OutputStream? =
        runCatching {
            // never write into a document that already exists: whether "wt" truncates is
            // provider-dependent (a raw file-backed tree was observed not to), and a short
            // write over a longer file would leave the previous tail behind, handing the user
            // a corrupt photo. Always create, and let the provider uniquify a clashing name so
            // an earlier rescued copy in the same folder is never overwritten either.
            val file = dir.createFile(mimeType, name) ?: return null
            context.contentResolver.openOutputStream(file.uri, "wt")
        }.getOrNull()

    companion object {
        fun open(
            context: Context,
            treeUri: Uri,
        ): SafShelvedSink? = DocumentFile.fromTreeUri(context, treeUri)?.let { SafShelvedSink(context, it) }
    }
}
