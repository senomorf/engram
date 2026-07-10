package cam.engram.app.data.db

import cam.engram.format.records.FrameLog

/**
 * Upserts [fresh] merged as a superset with whatever the cache already holds for
 * the same capture, so no index path ever shrinks the recovery set below records
 * once seen for this media (D3, finding 4). Identity is matched by takenAt so a
 * reused MediaStore id never grafts one photo's memories onto another (review F6);
 * an identityTakenAt of 0 predates the identity field and matches. Callers run
 * inside the item's index transaction so the read-merge-write cannot interleave
 * with another writer.
 */
suspend fun RecordCacheDao.upsertSuperset(fresh: RecordCacheEntity) {
    val existing = byId(fresh.mediaId)
    val identityMatches =
        existing != null && (existing.identityTakenAt == 0L || existing.identityTakenAt == fresh.identityTakenAt)
    val row =
        if (existing != null && identityMatches) {
            val (blob, count) = FrameLog.mergeSuperset(fresh.recordsBlob, fresh.recordCount, existing.recordsBlob)
            fresh.copy(
                recordsBlob = blob,
                recordCount = count,
                // scans leave video hashes empty; keep the prior hash so a cache orphan
                // (media removed) can still be exported
                contentHash = fresh.contentHash.ifEmpty { existing.contentHash },
            )
        } else {
            fresh
        }
    upsert(row)
}
