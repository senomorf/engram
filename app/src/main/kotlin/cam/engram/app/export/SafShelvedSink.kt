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
            val file =
                dir.findFile(name)?.takeIf { it.isFile }
                    ?: dir.createFile(mimeType, name)
                    ?: return null
            context.contentResolver.openOutputStream(file.uri, "wt")
        }.getOrNull()

    companion object {
        fun open(
            context: Context,
            treeUri: Uri,
        ): SafShelvedSink? = DocumentFile.fromTreeUri(context, treeUri)?.let { SafShelvedSink(context, it) }
    }
}
