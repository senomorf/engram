package cam.engram.app.writeback

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.app.data.scan.RecordScanner
import cam.engram.format.read.Memory
import cam.engram.format.records.FrameLog
import cam.engram.format.records.FrameLog.frameKey
import cam.engram.format.records.RecordStream

/**
 * Strip detection and one-tap re-embed (design D3): an item whose file lost
 * its records while the cache still holds them gets its memories written back.
 * The cache is matched by capture time as well as id, so a reused MediaStore id
 * can never graft one photo's memories onto a different photo (review F6).
 */
class StripRepair(
    private val db: EngramDb,
    private val writeBack: MediaWriteBack,
    private val scanner: RecordScanner,
) {
    suspend fun strippedItems(): List<MediaItemEntity> {
        val cached = db.recordCache().all().groupBy { it.mediaId }
        if (cached.isEmpty()) return emptyList()
        return db
            .media()
            .all()
            .filter { item ->
                val c = cached[item.mediaId]?.let { forCapture(it, item) }
                // the cache holds more records than the file now carries (total or partial strip)
                c != null && item.recordCount >= 0 && c.recordCount > item.recordCount
            }
    }

    suspend fun repair(item: MediaItemEntity): WriteOutcome {
        // the capture-scoped key (D29) makes the identity guard structural: a reused
        // media id addresses a different row, so a graft cannot be looked up at all
        val cache =
            db.recordCache().byKey(item.mediaId, item.takenAtMillis)
                ?: db.recordCache().byKey(item.mediaId, 0L)
                ?: return WriteOutcome.Failed("no cached records for this capture")
        val cachedFrames = FrameLog.crcOkFrames(cache.recordsBlob)
        if (cachedFrames.isEmpty()) return WriteOutcome.Failed("cache is empty or corrupt")
        // append only the frames the live file no longer carries, byte-exact and in cache
        // order: embedders keep whatever the file still has, so resubmitting the whole
        // cache would duplicate the survivors of a partial strip. Opaque frames (unknown
        // kinds or versions) repair the same way since nothing is re-encoded.
        val liveKeys =
            scanner
                .scan(item.uri, item.isVideo, item.mime)
                ?.recordsBlob
                ?.let { FrameLog.crcOkFrames(it) }
                .orEmpty()
                .map { it.frameKey() }
                .toSet()
        val missing = cachedFrames.filter { it.frameKey() !in liveKeys }
        if (missing.isEmpty()) return WriteOutcome.Failed("nothing to repair, every cached record is present")
        val mirror =
            Memory
                .fromRecords(RecordStream.decodeSequence(cache.recordsBlob).mapNotNull { it.decoded.record })
                .currentNote
                ?.text
        return writeBack.writeRecords(item, emptyList(), mirror, missing)
    }

    // exact capture first; identityTakenAt 0 predates the identity field (legacy
    // cache row) and matches so pre-migration caches still repair
    private fun forCapture(
        rows: List<RecordCacheEntity>,
        item: MediaItemEntity,
    ): RecordCacheEntity? =
        rows.firstOrNull { it.identityTakenAt == item.takenAtMillis }
            ?: rows.firstOrNull { it.identityTakenAt == 0L }
}
