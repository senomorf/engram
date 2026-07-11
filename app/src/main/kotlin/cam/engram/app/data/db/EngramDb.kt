package cam.engram.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        RecordCacheEntity::class,
        EnrichmentCacheEntity::class,
        DraftEntity::class,
        MemoryFts::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class EngramDb : RoomDatabase() {
    abstract fun media(): MediaDao

    abstract fun recordCache(): RecordCacheDao

    abstract fun enrichmentCache(): EnrichmentCacheDao

    abstract fun drafts(): DraftDao

    abstract fun search(): SearchDao

    companion object {
        // no destructive fallback (review F9): record_cache is the one
        // non-rebuildable store and must survive schema bumps via migration
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(connection: SupportSQLiteDatabase) {
                    connection.execSQL("ALTER TABLE media_items ADD COLUMN dateModified INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE record_cache ADD COLUMN identityTakenAt INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `enrichment_cache` (" +
                            "`mediaId` INTEGER NOT NULL, `recordBlob` BLOB NOT NULL, " +
                            "`updatedMillis` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
                    )
                }
            }

        // media_items gains the real file name so archives are named after the photo (D28)
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(connection: SupportSQLiteDatabase) {
                    connection.execSQL("ALTER TABLE media_items ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                }
            }

        // record_cache gains the stable identity a cache orphan needs to export (finding 9)
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(connection: SupportSQLiteDatabase) {
                    connection.execSQL("ALTER TABLE record_cache ADD COLUMN originalName TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE record_cache ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
                }
            }

        // record_cache is keyed by capture, not by device-local media id alone, so a
        // reused MediaStore id can never overwrite another capture's cached memories (D29)
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(connection: SupportSQLiteDatabase) {
                    connection.execSQL(
                        "CREATE TABLE record_cache_new (" +
                            "mediaId INTEGER NOT NULL, identityTakenAt INTEGER NOT NULL, " +
                            "sizeBytesAtScan INTEGER NOT NULL, recordsBlob BLOB NOT NULL, " +
                            "recordCount INTEGER NOT NULL, updatedMillis INTEGER NOT NULL, " +
                            "originalName TEXT NOT NULL DEFAULT '', contentHash TEXT NOT NULL DEFAULT '', " +
                            "PRIMARY KEY(mediaId, identityTakenAt))",
                    )
                    connection.execSQL(
                        "INSERT INTO record_cache_new (mediaId, identityTakenAt, sizeBytesAtScan, " +
                            "recordsBlob, recordCount, updatedMillis, originalName, contentHash) " +
                            "SELECT mediaId, identityTakenAt, sizeBytesAtScan, recordsBlob, recordCount, " +
                            "updatedMillis, originalName, contentHash FROM record_cache",
                    )
                    connection.execSQL("DROP TABLE record_cache")
                    connection.execSQL("ALTER TABLE record_cache_new RENAME TO record_cache")
                }
            }

        fun build(context: Context): EngramDb =
            Room
                .databaseBuilder(context, EngramDb::class.java, "engram.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        fun inMemory(context: Context): EngramDb =
            Room
                .inMemoryDatabaseBuilder(context, EngramDb::class.java)
                .allowMainThreadQueries()
                // run Room's query + invalidation work inline so a test closing the DB in
                // @After never races the InvalidationTracker on a background executor, which
                // intermittently threw a SQLiteConnectionPool ISE from the queue() Flow observer
                .setQueryExecutor { it.run() }
                .build()
    }
}
