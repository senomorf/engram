package cam.engram.app.export

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.RecordStream

class ExportResult(
    val itemCount: Int,
    val audioCount: Int,
    val failedCount: Int,
)

/** Writes a named blob into the archive tree; false when the write did not complete. */
fun interface ArchiveSink {
    fun write(
        name: String,
        bytes: ByteArray,
    ): Boolean
}

/**
 * Builds the Engram Archive from the strip-recovery cache (design D14): one JSON
 * document plus audio blobs per item, content-addressed by the media file's hash
 * so entries stay matchable across reinstall (like the CLI), never touching the
 * network. Pure logic over an [ArchiveSink]; the SAF tree is resolved by
 * [SafArchiveSink]. Every write is checked, so a failed write counts as failed
 * rather than a success the manifest would over-report (finding E).
 */
class ArchiveExporter(
    private val db: EngramDb,
    private val access: ContentAccess,
) {
    suspend fun exportTo(sink: ArchiveSink): ExportResult {
        var audioCount = 0
        var exported = 0
        var failed = 0
        for (entry in db.recordCache().all()) {
            val tally = exportEntry(entry, sink)
            exported += tally.exported
            failed += tally.failed
            audioCount += tally.audio
        }
        sink.write("manifest.json", EngramArchive.manifest(exported).encodeToByteArray())
        return ExportResult(exported, audioCount, failed)
    }

    private suspend fun exportEntry(
        entry: RecordCacheEntity,
        sink: ArchiveSink,
    ): EntryTally {
        val item = db.media().byId(entry.mediaId) ?: return EntryTally.SKIPPED
        val records = RecordStream.decodeSequence(entry.recordsBlob).mapNotNull { it.decoded.record }
        if (records.isEmpty()) return EntryTally.SKIPPED
        // hash the media bytes so the entry is content-addressed, not keyed by a
        // device-local MediaStore id that is reused across reinstall (finding E)
        val bytes = access.readBytes(item.uri) ?: return EntryTally.FAILED
        val hash = EngramArchive.contentHashName(bytes)
        val rendered = EngramArchive.render(EngramArchive.Item(hash, item.relativePath, records))
        if (!sink.write("$hash.json", rendered.json.encodeToByteArray())) return EntryTally.FAILED
        var audio = 0
        var itemOk = true
        rendered.audio.forEach { blob ->
            if (sink.write(blob.fileName, blob.data)) audio++ else itemOk = false
        }
        return if (itemOk) {
            EntryTally(
                exported = 1,
                failed = 0,
                audio = audio,
            )
        } else {
            EntryTally(exported = 0, failed = 1, audio = audio)
        }
    }

    private class EntryTally(
        val exported: Int,
        val failed: Int,
        val audio: Int,
    ) {
        companion object {
            val SKIPPED = EntryTally(0, 0, 0)
            val FAILED = EntryTally(0, 1, 0)
        }
    }
}
