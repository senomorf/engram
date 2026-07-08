package photos.engram.app.writeback

import photos.engram.app.data.db.EngramDb
import photos.engram.app.data.db.MediaItemEntity
import photos.engram.format.records.RecordStream

/**
 * Strip detection and one-tap re-embed (design D3): an item whose file lost
 * its records while the cache still holds them gets its memories written back.
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
            .filter { it.recordCount == 0 && (cached[it.mediaId]?.recordCount ?: 0) > 0 }
    }

    suspend fun repair(item: MediaItemEntity): WriteOutcome {
        val cache =
            db.recordCache().byId(item.mediaId)
                ?: return WriteOutcome.Failed("no cached records")
        val hits = RecordStream.decodeSequence(cache.recordsBlob)
        val records = hits.mapNotNull { it.decoded.record }
        if (records.isEmpty()) return WriteOutcome.Failed("cache is empty or corrupt")
        val mirror =
            records
                .filter { it.kind == photos.engram.format.records.RecordKind.Note }
                .maxByOrNull { it.tsMillis }
                ?.payload
                ?.decodeToString()
        return writeBack.writeRecords(item, records, mirror)
    }
}
