package cam.engram.app.domain

import androidx.room.withTransaction
import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.EnrichmentCacheEntity
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.db.upsertSuperset
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ReconcileStats(
    val added: Int,
    val removed: Int,
    val scanned: Int,
)

/**
 * Syncs the index with MediaStore and scans changed files. The index stays
 * rebuildable from files (design D3): dropping the database and reconciling
 * again restores everything except the strip-recovery cache. Runs entirely on
 * [io] so callers on the main scope never block (review F1).
 */
class Reconciler(
    private val db: EngramDb,
    private val source: MediaSource,
    private val scanner: RecordScanner,
    private val includeScreenshots: suspend () -> Boolean,
    private val io: CoroutineDispatcher,
    // background enrichment prefetch (review F5): returns the encoded enrichment
    // record for an item, or null. Network work, off the user save path.
    private val enrichmentPrefetch: suspend (MediaItemEntity) -> ByteArray? = { null },
    private val clock: () -> Long,
) {
    suspend fun reconcile(): ReconcileStats =
        withContext(io) {
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
                } else if (known.sizeBytes != item.sizeBytes || known.dateModified != item.dateModified) {
                    // file changed on disk (size or mtime): rescan (review F11)
                    upserts +=
                        known.copy(
                            sizeBytes = item.sizeBytes,
                            dateModified = item.dateModified,
                            recordCount = -1,
                        )
                }
            }
            val removedIds = existing.keys - seen
            if (upserts.isNotEmpty()) db.media().upsert(upserts)
            if (removedIds.isNotEmpty()) db.media().delete(removedIds.toList())

            var scanned = 0
            for (pending in db.media().unscanned()) {
                val outcome = scanner.scan(pending.uri, pending.isVideo, pending.mime) ?: continue
                scanned++
                val row =
                    pending.copy(
                        recordCount = outcome.recordCount,
                        payloadLength = outcome.payloadLength,
                        lastScanMillis = clock(),
                    )
                // the media row, the recovery cache, and the search index commit together
                // per item (D3): a failed cache write leaves the item unscanned for retry
                db.withTransaction {
                    db.media().upsert(listOf(row))
                    val blob = outcome.recordsBlob
                    if (blob != null && outcome.recordCount > 0) {
                        db.recordCache().upsertSuperset(
                            cacheRow(pending, blob, outcome.recordCount, outcome.contentHash),
                        )
                    }
                    indexSearch(pending.mediaId, outcome.searchableText)
                }
            }
            if (removedIds.isNotEmpty()) {
                removedIds.forEach {
                    db.search().delete(it)
                    db.enrichmentCache().delete(it)
                }
            }
            prefetchEnrichment()
            ReconcileStats(added, removedIds.size, scanned)
        }

    private suspend fun prefetchEnrichment() {
        val cached = db.enrichmentCache().cachedIds().toSet()
        // only images we might annotate and have not enriched yet; bounded so a
        // single reconcile never fires an unbounded burst of network calls
        val candidates =
            db
                .media()
                .scanned()
                .filter { !it.isVideo && it.mediaId !in cached }
                .take(PREFETCH_LIMIT)
        for (item in candidates) {
            val encoded = enrichmentPrefetch(item) ?: continue
            db.enrichmentCache().upsert(EnrichmentCacheEntity(item.mediaId, encoded, clock()))
        }
    }

    private suspend fun indexSearch(
        mediaId: Long,
        text: String,
    ) {
        if (text.isBlank()) db.search().delete(mediaId) else db.search().upsert(MemoryFts(mediaId, text))
    }

    // the superset merge with any existing cache row happens in upsertSuperset, inside
    // the caller's per-item transaction; this only shapes the freshly scanned row
    private fun cacheRow(
        item: MediaItemEntity,
        scannedBlob: ByteArray,
        scannedCount: Int,
        contentHash: String,
    ): RecordCacheEntity =
        RecordCacheEntity(
            mediaId = item.mediaId,
            identityTakenAt = item.takenAtMillis,
            sizeBytesAtScan = item.sizeBytes,
            recordsBlob = scannedBlob,
            recordCount = scannedCount,
            updatedMillis = clock(),
            originalName = item.relativePath,
            contentHash = contentHash,
        )

    private fun SourceItem.toEntity(): MediaItemEntity =
        MediaItemEntity(
            mediaId = mediaId,
            uri = uri,
            isVideo = isVideo,
            mime = mime,
            relativePath = relativePath,
            takenAtMillis = takenAtMillis,
            sizeBytes = sizeBytes,
            dateModified = dateModified,
            recordCount = -1,
            payloadLength = 0,
            lastScanMillis = 0,
        )

    private companion object {
        const val PREFETCH_LIMIT = 20
    }
}
