package photos.engram.app.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import photos.engram.app.data.db.EngramDb
import photos.engram.format.archive.EngramArchive
import photos.engram.format.records.RecordStream

class ExportResult(
    val itemCount: Int,
    val audioCount: Int,
)

/**
 * Writes the Engram Archive to a user-chosen SAF tree (design D14). Reads
 * records from the strip-recovery cache so export works even for files a
 * cloud pipeline later stripped, and never touches the network.
 */
class ArchiveExporter(
    private val context: Context,
    private val db: EngramDb,
) {
    suspend fun export(treeUri: Uri): ExportResult =
        withContext(Dispatchers.IO) {
            val root =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("cannot open chosen folder")
            val dir =
                root.findFile(FOLDER)?.takeIf { it.isDirectory }
                    ?: root.createDirectory(FOLDER)
                    ?: error("cannot create archive folder")

            val cached = db.recordCache().all()
            var audioCount = 0
            var exported = 0
            for (entry in cached) {
                val item = db.media().byId(entry.mediaId)
                val records = RecordStream.decodeSequence(entry.recordsBlob).mapNotNull { it.decoded.record }
                if (item != null && records.isNotEmpty()) {
                    val hash = "${entry.mediaId}"
                    val rendered =
                        EngramArchive.render(EngramArchive.Item(hash, item.relativePath + entry.mediaId, records))
                    writeText(dir, "$hash.json", rendered.json)
                    rendered.audio.forEach { blob ->
                        writeBytes(dir, blob.fileName, blob.data)
                        audioCount++
                    }
                    exported++
                }
            }
            writeText(dir, "manifest.json", EngramArchive.manifest(exported))
            ExportResult(exported, audioCount)
        }

    private fun writeText(
        dir: DocumentFile,
        name: String,
        text: String,
    ) = writeBytes(dir, name, text.encodeToByteArray())

    private fun writeBytes(
        dir: DocumentFile,
        name: String,
        bytes: ByteArray,
    ) {
        val file =
            dir.findFile(name)?.takeIf { it.isFile }
                ?: dir.createFile("application/octet-stream", name)
                ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
    }

    private companion object {
        const val FOLDER = "engram-archive"
    }
}
