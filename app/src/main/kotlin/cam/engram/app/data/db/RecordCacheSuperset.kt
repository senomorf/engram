package cam.engram.app.data.db

import cam.engram.format.records.FrameLog

/**
 * Upserts [fresh] merged as a superset with the cache row for the same capture,
 * so no index path ever shrinks the recovery set below records once seen for this
 * capture (D3, finding 4). The capture is addressed by its full key (mediaId plus
 * identityTakenAt, D29); a legacy pre-identity row (identityTakenAt 0) matches any
 * fresh scan and upgrades in place. Rows keyed to a different capture are never
 * touched: a reused MediaStore id cannot graft one photo's memories onto another
 * (review F6) nor overwrite the displaced capture's only cached copy. Callers run
 * inside the item's index transaction so the read-merge-write cannot interleave
 * with another writer.
 */
suspend fun RecordCacheDao.upsertSuperset(fresh: RecordCacheEntity) {
    val exact = byKey(fresh.mediaId, fresh.identityTakenAt)
    val legacy = if (exact == null) byKey(fresh.mediaId, 0L) else null
    val existing = exact ?: legacy
    val row =
        if (existing != null) {
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
    // the legacy row's content just upgraded into the fully keyed row; drop the 0-key shell
    if (legacy != null && fresh.identityTakenAt != 0L) delete(legacy)
}
