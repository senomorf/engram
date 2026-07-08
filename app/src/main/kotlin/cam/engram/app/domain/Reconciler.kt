package cam.engram.app.domain

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner

class ReconcileStats(
    val added: Int,
    val removed: Int,
    val scanned: Int,
)

/**
 * Syncs the index with MediaStore and scans changed files. The index stays
 * rebuildable from files (design D3): dropping the database and reconciling
 * again restores everything except the strip-recovery cache.
 */
class Reconciler(
    private val db: EngramDb,
    private val source: MediaSource,
    private val scanner: RecordScanner,
    private val includeScreenshots: () -> Boolean,
    private val clock: () -> Long,
) {
    suspend fun reconcile(): ReconcileStats {
        val snapshot = source.snapshot(includeScreenshots())
        val existing = db.media().all().associateBy { it.mediaId }
        val seen = snapshot.map { it.mediaId }.toSet()

        val upserts = mutableListOf<MediaItemEntity>()
        var added = 0
        for (item in snapshot) {
            val known = existing[item.mediaId]
            if (known == null) {
                added++
                upserts += item.toEntity()
            } else if (known.sizeBytes != item.sizeBytes) {
                // file changed on disk: rescan
                upserts += known.copy(sizeBytes = item.sizeBytes, recordCount = -1)
            }
        }
        val removedIds = existing.keys - seen
        if (upserts.isNotEmpty()) db.media().upsert(upserts)
        if (removedIds.isNotEmpty()) db.media().delete(removedIds.toList())

        var scanned = 0
        for (pending in db.media().unscanned()) {
            val outcome = scanner.scan(pending.uri, pending.isVideo, pending.mime) ?: continue
            scanned++
            db.media().upsert(
                listOf(
                    pending.copy(
                        recordCount = outcome.recordCount,
                        payloadLength = outcome.payloadLength,
                        lastScanMillis = clock(),
                    ),
                ),
            )
            val blob = outcome.recordsBlob
            if (blob != null && outcome.recordCount > 0) {
                db.recordCache().upsert(
                    RecordCacheEntity(
                        mediaId = pending.mediaId,
                        sizeBytesAtScan = pending.sizeBytes,
                        recordsBlob = blob,
                        recordCount = outcome.recordCount,
                        updatedMillis = clock(),
                    ),
                )
            }
            indexSearch(pending.mediaId, outcome.searchableText)
        }
        if (removedIds.isNotEmpty()) removedIds.forEach { db.search().delete(it) }
        return ReconcileStats(added, removedIds.size, scanned)
    }

    private suspend fun indexSearch(
        mediaId: Long,
        text: String,
    ) {
        if (text.isBlank()) db.search().delete(mediaId) else db.search().upsert(MemoryFts(mediaId, text))
    }

    private fun SourceItem.toEntity(): MediaItemEntity =
        MediaItemEntity(
            mediaId = mediaId,
            uri = uri,
            isVideo = isVideo,
            mime = mime,
            relativePath = relativePath,
            takenAtMillis = takenAtMillis,
            sizeBytes = sizeBytes,
            recordCount = -1,
            payloadLength = 0,
            lastScanMillis = 0,
        )
}
