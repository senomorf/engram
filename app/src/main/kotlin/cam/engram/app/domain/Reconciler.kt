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
    // media access gate (finding H5): the whole-library prune runs only with durable full
    // access, so a lapsed or partial grant (a subset, or empty, snapshot) never wipes the index
    private val hasFullMediaAccess: suspend () -> Boolean = { true },
    private val clock: () -> Long,
) {
    suspend fun reconcile(): ReconcileStats =
        withContext(io) {
            val snapshot = source.snapshot(includeScreenshots())
            val existing = db.media().all().associateBy { it.mediaId }
            val seen = snapshot.map { it.mediaId }.toSet()

            val upserts = mutableListOf<MediaItemEntity>()
            val identityChanged = mutableListOf<Long>()
            var added = 0
            for (item in snapshot) {
                val known = existing[item.mediaId]
                when {
                    known == null -> {
                        added++
                        upserts += item.toEntity()
                    }
                    // the media id now points at a different capture (a reused id): replace the
                    // whole row so identity, uri, mime and name track the new photo. The old
                    // record-cache row survives as an orphan under its own identity (composite
                    // key); the id-keyed enrichment and draft are evicted below so the new capture
                    // cannot inherit the previous one's private content (finding H1)
                    item.takenAtMillis != known.takenAtMillis -> {
                        upserts += item.toEntity()
                        identityChanged += item.mediaId
                    }
                    known.sizeBytes != item.sizeBytes || known.dateModified != item.dateModified -> {
                        // file changed on disk (size or mtime): rescan (review F11)
                        upserts +=
                            known.copy(
                                sizeBytes = item.sizeBytes,
                                dateModified = item.dateModified,
                                recordCount = -1,
                            )
                    }
                }
            }
            // prune only with durable full access: a partial or lost grant returns a subset
            // (or empty) snapshot that must not be mistaken for deletions and wipe the index (H5)
            val removedIds = if (hasFullMediaAccess()) existing.keys - seen else emptySet()
            // the row replacement and the id-keyed eviction commit together: a crash or a failed
            // delete between them would leave a reused id's new row in place with the old capture's
            // private draft and enrichment still attached, and the next reconcile (identity now
            // matching) would never re-evict them (finding F3)
            db.withTransaction {
                if (upserts.isNotEmpty()) db.media().upsert(upserts)
                if (removedIds.isNotEmpty()) db.media().delete(removedIds.toList())
                // a removed capture's id-keyed draft must die with its media row, atomically: else a
                // later reused id inherits it through the known == null new-item path and grafts the old
                // private note onto the new photo (reviewer follow-up to F3/H1)
                removedIds.forEach { db.drafts().delete(it) }
                // a reused id's old enrichment and draft are keyed by media id alone, so drop them
                // (the record cache is preserved as an orphan by its composite key) (finding H1)
                identityChanged.forEach {
                    db.enrichmentCache().delete(it)
                    db.drafts().delete(it)
                }
            }

            // pre-hash cache rows (migrated with an empty contentHash) backfill through
            // the standard rescan below; a no-op pass once every hash has landed
            val backfill = db.recordCache().idsNeedingHashBackfill()
            if (backfill.isNotEmpty()) db.media().markUnscanned(backfill)

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
            originalName = item.displayName.ifEmpty { item.relativePath },
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
            displayName = displayName,
        )

    private companion object {
        const val PREFETCH_LIMIT = 20
    }
}
