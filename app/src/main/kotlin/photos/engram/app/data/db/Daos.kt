package photos.engram.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Upsert
    suspend fun upsert(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items")
    suspend fun all(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId")
    suspend fun byId(mediaId: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE recordCount = 0 ORDER BY takenAtMillis DESC")
    fun queue(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE recordCount = -1")
    suspend fun unscanned(): List<MediaItemEntity>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN recordCount > 0 THEN 1 ELSE 0 END), 0) AS annotated, " +
            "COALESCE(SUM(CASE WHEN recordCount = 0 THEN 1 ELSE 0 END), 0) AS waiting, " +
            "COALESCE(SUM(CASE WHEN recordCount = -1 THEN 1 ELSE 0 END), 0) AS unscanned " +
            "FROM media_items",
    )
    fun counts(): Flow<Counts>

    @Query("DELETE FROM media_items WHERE mediaId IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface RecordCacheDao {
    @Upsert
    suspend fun upsert(entry: RecordCacheEntity)

    @Query("SELECT * FROM record_cache WHERE mediaId = :mediaId")
    suspend fun byId(mediaId: Long): RecordCacheEntity?

    @Query("SELECT * FROM record_cache")
    suspend fun all(): List<RecordCacheEntity>
}

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE mediaId = :mediaId")
    suspend fun byId(mediaId: Long): DraftEntity?

    @Query("SELECT * FROM drafts")
    suspend fun all(): List<DraftEntity>

    @Query("DELETE FROM drafts WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}
