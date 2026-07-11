package cam.engram.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId")
    fun flowById(mediaId: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE recordCount >= 0")
    suspend fun scanned(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE recordCount = 0 ORDER BY takenAtMillis DESC")
    fun queue(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE recordCount > 0 ORDER BY takenAtMillis DESC")
    fun timeline(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE recordCount = -1")
    suspend fun unscanned(): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE recordCount = 0")
    suspend fun unannotatedCount(): Int

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
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MemoryFts)

    @Query("DELETE FROM memory_fts WHERE rowid = :mediaId")
    suspend fun delete(mediaId: Long)

    @Query(
        "SELECT m.* FROM media_items m JOIN memory_fts f ON f.rowid = m.mediaId " +
            "WHERE memory_fts MATCH :query ORDER BY m.takenAtMillis DESC",
    )
    suspend fun search(query: String): List<MediaItemEntity>
}

@Dao
interface RecordCacheDao {
    @Upsert
    suspend fun upsert(entry: RecordCacheEntity)

    @Query("SELECT * FROM record_cache WHERE mediaId = :mediaId AND identityTakenAt = :identityTakenAt")
    suspend fun byKey(
        mediaId: Long,
        identityTakenAt: Long,
    ): RecordCacheEntity?

    @Query("SELECT * FROM record_cache")
    suspend fun all(): List<RecordCacheEntity>

    @Delete
    suspend fun delete(entry: RecordCacheEntity)
}

@Dao
interface EnrichmentCacheDao {
    @Upsert
    suspend fun upsert(entry: EnrichmentCacheEntity)

    @Query("SELECT * FROM enrichment_cache WHERE mediaId = :mediaId")
    suspend fun byId(mediaId: Long): EnrichmentCacheEntity?

    @Query("SELECT mediaId FROM enrichment_cache")
    suspend fun cachedIds(): List<Long>

    @Query("DELETE FROM enrichment_cache WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
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
