package cam.engram.app.export

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.FrameLog
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
        val inventory = mutableListOf<EngramArchive.ManifestFile>()
        for (entry in db.recordCache().all()) {
            val tally = exportEntry(entry, sink)
            exported += tally.exported
            failed += tally.failed
            audioCount += tally.audio
            inventory += tally.files
        }
        // a dropped manifest write leaves an unusable archive, so surface it as a failure (finding 9)
        val manifestOk =
            sink.write("manifest.json", EngramArchive.manifest(exported, inventory).encodeToByteArray())
        return ExportResult(exported, audioCount, if (manifestOk) failed else failed + 1)
    }

    private suspend fun exportEntry(
        entry: RecordCacheEntity,
        sink: ArchiveSink,
    ): EntryTally {
        // the byte-exact record log is authoritative: opaque frames (unknown kinds or
        // versions) export too; skip only when nothing CRC-valid survives in the cache
        val rawFrames = FrameLog.crcOkFrames(entry.recordsBlob)
        if (rawFrames.isEmpty()) return EntryTally.SKIPPED
        val records = RecordStream.decodeSequence(entry.recordsBlob).mapNotNull { it.decoded.record }
        // prefer the live media hash; fall back to the hash + name stored at scan time so a cache
        // orphan (the media file moved or was deleted) still exports, never a silent skip (finding 9)
        val item = db.media().byId(entry.mediaId)
        val liveBytes = item?.let { access.readBytes(it.uri) }
        val hash: String
        val name: String
        if (item != null && liveBytes != null) {
            hash = EngramArchive.contentHashName(liveBytes)
            name = item.relativePath
        } else {
            hash = entry.contentHash
            name = entry.originalName
        }
        // no live media and no hash stored at scan time (legacy row): cannot content-address it
        if (hash.isEmpty()) return EntryTally.FAILED
        val rendered = EngramArchive.render(EngramArchive.Item(hash, name, records, rawFrames))
        val written = mutableListOf<EngramArchive.ManifestFile>()

        fun put(
            fileName: String,
            bytes: ByteArray,
        ): Boolean {
            val ok = sink.write(fileName, bytes)
            if (ok) written += EngramArchive.ManifestFile(fileName, EngramArchive.contentHashName(bytes))
            return ok
        }
        if (!put("$hash.json", rendered.json.encodeToByteArray())) return EntryTally.FAILED
        var itemOk = true
        val log = rendered.recordLog
        val logName = rendered.recordLogName
        if (log != null && logName != null && !put(logName, log)) itemOk = false
        var audio = 0
        rendered.audio.forEach { blob ->
            if (put(blob.fileName, blob.data)) audio++ else itemOk = false
        }
        return if (itemOk) {
            EntryTally(exported = 1, failed = 0, audio = audio, files = written)
        } else {
            EntryTally(exported = 0, failed = 1, audio = audio, files = written)
        }
    }

    private class EntryTally(
        val exported: Int,
        val failed: Int,
        val audio: Int,
        val files: List<EngramArchive.ManifestFile> = emptyList(),
    ) {
        companion object {
            val SKIPPED = EntryTally(0, 0, 0)
            val FAILED = EntryTally(0, 1, 0)
        }
    }
}
