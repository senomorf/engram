package cam.engram.app

import cam.engram.app.data.db.RecordCacheDao
import cam.engram.app.data.db.RecordCacheEntity

// single-capture convenience for tests: the newest cache row for a media id.
// Production code addresses rows by their full capture key (mediaId, identityTakenAt).
suspend fun RecordCacheDao.byId(mediaId: Long): RecordCacheEntity? =
    all().filter { it.mediaId == mediaId }.maxByOrNull { it.updatedMillis }
