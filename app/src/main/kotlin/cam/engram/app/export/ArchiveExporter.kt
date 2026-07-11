package cam.engram.app.export

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.format.Digests
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
 * network. Rows resolving to the same content hash merge their logs superset-style
 * into one entry: same bytes = same archive identity (spec sec 11), so a duplicate
 * copy can never overwrite another memory's files (finding E). Pure logic over an
 * [ArchiveSink]; the SAF tree is resolved by [SafArchiveSink]. Every write is
 * checked, so a failed write counts as failed rather than a success the manifest
 * would over-report.
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
        val resolved = mutableListOf<Resolved>()
        for (entry in db.recordCache().all()) {
            when (val r = resolve(entry)) {
                Resolution.Skip -> Unit
                Resolution.Fail -> failed++
                is Resolved -> resolved += r
            }
        }
        for (group in resolved.groupBy { it.hash }.values) {
            val tally = exportMerged(group, sink)
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

    private sealed interface Resolution {
        data object Skip : Resolution

        data object Fail : Resolution
    }

    private class Resolved(
        val hash: String,
        val name: String,
        val hasLiveMedia: Boolean,
        val blob: ByteArray,
        val count: Int,
    ) : Resolution

    private suspend fun resolve(entry: RecordCacheEntity): Resolution {
        // the byte-exact record log is authoritative: opaque frames (unknown kinds or
        // versions) export too; skip only when nothing CRC-valid survives in the cache
        if (FrameLog.crcOkFrames(entry.recordsBlob).isEmpty()) return Resolution.Skip
        // prefer the live media hash, but only when the live row is the SAME capture:
        // a reused media id must not lend the new file's hash and name to the displaced
        // capture's records (D29). Legacy identity-0 rows predate the field and match.
        val item = db.media().byId(entry.mediaId)
        if (item != null && (entry.identityTakenAt == 0L || entry.identityTakenAt == item.takenAtMillis)) {
            // hash by streaming the channel: exporting a video must not load it whole
            val liveHash = access.withChannel(item.uri) { Digests.sha256Hex(it) }
            if (liveHash != null) {
                return Resolved(
                    hash = liveHash,
                    name = item.displayName.ifEmpty { item.relativePath },
                    hasLiveMedia = true,
                    blob = entry.recordsBlob,
                    count = entry.recordCount,
                )
            }
        }
        // no live capture: fall back to the hash + name stored at scan time so a cache
        // orphan (moved, deleted, or displaced by id reuse) still exports (finding 9)
        return when {
            // no hash stored at scan time either (legacy row): cannot content-address it
            entry.contentHash.isEmpty() -> Resolution.Fail
            else ->
                Resolved(
                    hash = entry.contentHash,
                    name = entry.originalName,
                    hasLiveMedia = false,
                    blob = entry.recordsBlob,
                    count = entry.recordCount,
                )
        }
    }

    private fun exportMerged(
        group: List<Resolved>,
        sink: ArchiveSink,
    ): EntryTally {
        val head = group.first()
        var blob = head.blob
        var count = head.count
        for (next in group.drop(1)) {
            val (merged, mergedCount) = FrameLog.mergeSuperset(blob, count, next.blob)
            blob = merged
            count = mergedCount
        }
        val name = group.firstOrNull { it.hasLiveMedia }?.name ?: head.name
        val rawFrames = FrameLog.crcOkFrames(blob)
        val records = RecordStream.decodeSequence(blob).mapNotNull { it.decoded.record }
        val rendered = EngramArchive.render(EngramArchive.Item(head.hash, name, records, rawFrames))
        val written = mutableListOf<EngramArchive.ManifestFile>()

        fun put(
            fileName: String,
            bytes: ByteArray,
        ): Boolean {
            val ok = sink.write(fileName, bytes)
            if (ok) written += EngramArchive.ManifestFile(fileName, EngramArchive.contentHashName(bytes))
            return ok
        }
        if (!put("${head.hash}.json", rendered.json.encodeToByteArray())) return EntryTally.FAILED
        var itemOk = true
        val log = rendered.recordLog
        val logName = rendered.recordLogName
        if (log != null && logName != null && !put(logName, log)) itemOk = false
        var audio = 0
        rendered.audio.forEach { clip ->
            if (put(clip.fileName, clip.data)) audio++ else itemOk = false
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
            val FAILED = EntryTally(0, 1, 0)
        }
    }
}
