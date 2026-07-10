package cam.engram.app.domain

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.EnrichmentCacheEntity
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.MemoryFts
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.media.ContentAccess
import cam.engram.app.data.media.MediaSource
import cam.engram.app.data.media.SourceItem
import cam.engram.app.data.scan.RecordScanner
import cam.engram.format.archive.EngramArchive
import cam.engram.format.records.RecordStream
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
    private val access: ContentAccess,
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
                    db.recordCache().upsert(cacheRow(pending, blob, outcome.recordCount))
                }
                indexSearch(pending.mediaId, outcome.searchableText)
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

    // keep the cache at the union of the just-scanned frames and any the cache already held for
    // the same capture, so a partial strip never shrinks the recovery set below records once
    // seen for this photo (finding 4)
    private suspend fun cacheRow(
        item: MediaItemEntity,
        scannedBlob: ByteArray,
        scannedCount: Int,
    ): RecordCacheEntity {
        val existing = db.recordCache().byId(item.mediaId)
        val identityMatches =
            existing != null && (existing.identityTakenAt == 0L || existing.identityTakenAt == item.takenAtMillis)
        val (blob, count) =
            if (existing != null && identityMatches) {
                mergeSuperset(scannedBlob, scannedCount, existing.recordsBlob)
            } else {
                scannedBlob to scannedCount
            }
        // content-address the media now, while it is still readable, so a later cache orphan
        // (media removed) can still be exported by name and hash (finding 9)
        val hash =
            access.readBytes(item.uri)?.let { EngramArchive.contentHashName(it) } ?: existing?.contentHash.orEmpty()
        return RecordCacheEntity(
            mediaId = item.mediaId,
            identityTakenAt = item.takenAtMillis,
            sizeBytesAtScan = item.sizeBytes,
            recordsBlob = blob,
            recordCount = count,
            updatedMillis = clock(),
            originalName = item.relativePath,
            contentHash = hash,
        )
    }

    // append every cached CRC-valid frame the new scan no longer carries so lost records survive
    // in the cache for strip-repair; frames are matched by id + CRC to dedup identical ones
    private fun mergeSuperset(
        scannedBlob: ByteArray,
        scannedCount: Int,
        cachedBlob: ByteArray,
    ): Pair<ByteArray, Int> {
        val scannedKeys = crcOkFrames(scannedBlob).map { it.frameKey() }.toSet()
        val missing = crcOkFrames(cachedBlob).filter { it.frameKey() !in scannedKeys }
        if (missing.isEmpty()) return scannedBlob to scannedCount
        val merged = missing.fold(scannedBlob) { acc, frame -> acc + frame }
        return merged to (scannedCount + missing.size)
    }

    private fun crcOkFrames(blob: ByteArray): List<ByteArray> =
        RecordStream
            .decodeSequence(blob)
            .filter { it.decoded.crcOk }
            .map { blob.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }

    // the 16-byte id lives at frame offset 8 and the CRC in the last 4 bytes (wire format); the
    // pair keys a frame so unknown kinds dedup too and distinct records never collide
    private fun ByteArray.frameKey(): List<Byte> = (copyOfRange(8, 24) + copyOfRange(size - 4, size)).toList()

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
