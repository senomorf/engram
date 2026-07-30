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
            // the access gate must cover the snapshot itself (review N4): a grant that
            // upgrades while the query is in flight would otherwise bless a partial
            // snapshot as full and prune (dropping drafts) everything the subset missed
            val fullAccessAtSnapshot = hasFullMediaAccess()
            val snapshot = source.snapshot(includeScreenshots())
            val existing = db.media().all().associateBy { it.mediaId }
            val seen = snapshot.map { it.mediaId }.toSet()

            val upserts = mutableListOf<MediaItemEntity>()
            val identityChanged = mutableListOf<Long>()
            val reDated = mutableListOf<ReDated>()
            var added = 0
            for (item in snapshot) {
                val known = existing[item.mediaId]
                when {
                    known == null -> {
                        added++
                        upserts += item.toEntity()
                    }
                    // the capture identity changed. Either the media id now points at a different
                    // capture (a reused id) or the user re-dated this same photo, which DATE_TAKEN
                    // cannot distinguish on its own because it is editable metadata (D29, review
                    // N5). Both replace the row so identity, uri, mime and name track what is
                    // there now, but only a genuine reuse may evict the id-keyed enrichment and
                    // draft: doing that on a re-date silently destroys an unsaved note.
                    item.takenAtMillis != known.takenAtMillis -> {
                        upserts += item.toEntity()
                        if (sameCaptureReDated(known, item)) {
                            reDated += ReDated(item.mediaId, known.takenAtMillis, item.takenAtMillis)
                        } else {
                            // the old record-cache row survives as an orphan under its own identity
                            // (composite key); enrichment and draft are evicted below so the new
                            // capture cannot inherit the previous one's private content (finding H1)
                            identityChanged += item.mediaId
                        }
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
            // (or empty) snapshot that must not be mistaken for deletions and wipe the index
            // (H5); full access must have held before and after the query (N4)
            val removedIds = if (fullAccessAtSnapshot && hasFullMediaAccess()) existing.keys - seen else emptySet()
            // the row replacement and the id-keyed eviction commit together: a crash or a failed
            // delete between them would leave a reused id's new row in place with the old capture's
            // private draft and enrichment still attached, and the next reconcile (identity now
            // matching) would never re-evict them (finding F3)
            db.withTransaction {
                if (upserts.isNotEmpty()) db.media().upsert(upserts)
                if (removedIds.isNotEmpty()) db.media().delete(removedIds.toList())
                // a removed capture's id-keyed draft must die with its media row, atomically: else a
                // later reused id inherits it through the known == null new-item path and grafts the old
                // private note onto the new photo (reviewer follow-up to F3/H1). The search and
                // enrichment rows commit here too (N13): once the media row is gone the id never
                // reappears in `existing`, so cleanup trailing the scan loop would leak them forever
                removedIds.forEach {
                    db.drafts().delete(it)
                    db.search().delete(it)
                    db.enrichmentCache().delete(it)
                }
                // a reused id's old enrichment and draft are keyed by media id alone, so drop them
                // (the record cache is preserved as an orphan by its composite key) (finding H1)
                identityChanged.forEach {
                    db.enrichmentCache().delete(it)
                    db.drafts().delete(it)
                }
                // a re-dated capture keeps its memories: move the cache row onto the new identity
                // rather than leaving it orphaned under the old one. Merged as a superset so a row
                // already standing at the new identity is never shrunk, and committed here so the
                // re-key cannot be separated from the media row it belongs to.
                reDated.forEach { r ->
                    db.recordCache().byKey(r.mediaId, r.from)?.let { row ->
                        db.recordCache().upsertSuperset(row.copy(identityTakenAt = r.to))
                        db.recordCache().delete(row)
                    }
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
            prefetchEnrichment()
            ReconcileStats(added, removedIds.size, scanned)
        }

    /** One media id whose capture was re-dated in place, with the identities to move between. */
    private class ReDated(
        val mediaId: Long,
        val from: Long,
        val to: Long,
    )

    /**
     * Is this the same capture with an edited date rather than a reused media id (review N5)?
     * DATE_TAKEN is user-editable, so it cannot answer this alone; the file's bytes can.
     *
     * Cheap signal first: an unchanged size and mtime mean the file itself was never rewritten,
     * so only the MediaStore date column moved. When the file was touched, fall back to the
     * content hash the cache recorded for the old identity, which proves sameness even though
     * the mtime moved. With neither available the answer is unknowable, and the conservative
     * reading is reuse: evicting a draft costs an unsaved note, while keeping one on a genuinely
     * different photo leaks private content onto it (finding H1).
     */
    private suspend fun sameCaptureReDated(
        known: MediaItemEntity,
        fresh: SourceItem,
    ): Boolean {
        if (known.sizeBytes == fresh.sizeBytes && known.dateModified == fresh.dateModified) return true
        val cached =
            db
                .recordCache()
                .byKey(known.mediaId, known.takenAtMillis)
                ?.contentHash
                ?.takeIf { it.isNotEmpty() } ?: return false
        val live =
            scanner
                .scan(fresh.uri, fresh.isVideo, fresh.mime)
                ?.contentHash
                ?.takeIf { it.isNotEmpty() } ?: return false
        return cached == live
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
