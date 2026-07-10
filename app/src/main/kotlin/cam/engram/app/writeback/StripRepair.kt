package cam.engram.app.writeback

import cam.engram.app.data.db.EngramDb
import cam.engram.app.data.db.MediaItemEntity
import cam.engram.app.data.db.RecordCacheEntity
import cam.engram.format.records.RecordKind
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
) {
    suspend fun strippedItems(): List<MediaItemEntity> {
        val cached = db.recordCache().all().associateBy { it.mediaId }
        if (cached.isEmpty()) return emptyList()
        return db
            .media()
            .all()
            .filter { item ->
                val c = cached[item.mediaId]
                // the cache holds more records than the file now carries (total or partial strip)
                c != null && item.recordCount >= 0 && c.recordCount > item.recordCount && sameIdentity(c, item)
            }
    }

    suspend fun repair(item: MediaItemEntity): WriteOutcome {
        val cache =
            db.recordCache().byId(item.mediaId)
                ?: return WriteOutcome.Failed("no cached records")
        if (!sameIdentity(cache, item)) {
            return WriteOutcome.Failed("cached records belong to a different photo, refusing to graft")
        }
        val hits = RecordStream.decodeSequence(cache.recordsBlob)
        val records = hits.mapNotNull { it.decoded.record }
        if (records.isEmpty()) return WriteOutcome.Failed("cache is empty or corrupt")
        // CRC-valid frames we cannot model as records (unknown/future kinds) ride along
        // verbatim so this rewriter does not drop them (spec: unknown kinds preserved)
        val carryFrames =
            hits
                .filter { it.decoded.crcOk && it.decoded.record == null }
                .map { cache.recordsBlob.copyOfRange(it.offset, it.offset + it.decoded.byteLength) }
        val mirror =
            records
                .filter { it.kind == RecordKind.Note }
                .maxByOrNull { it.tsMillis }
                ?.payload
                ?.decodeToString()
        return writeBack.writeRecords(item, records, mirror, carryFrames)
    }

    // identityTakenAt of 0 predates the identity field (legacy cache row): treat
    // as matching so pre-migration caches still repair rather than silently drop
    private fun sameIdentity(
        cache: RecordCacheEntity,
        item: MediaItemEntity,
    ): Boolean = cache.identityTakenAt == 0L || cache.identityTakenAt == item.takenAtMillis
}
