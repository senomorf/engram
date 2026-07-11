package cam.engram.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cam.engram.app.data.db.EngramDb
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * record_cache is the one non-rebuildable store, so schema bumps must migrate rather than
 * fall back destructively (review F9). Verifies MIGRATION_1_2 adds the new columns and table.
 */
@RunWith(RobolectricTestRunner::class)
class EngramDbMigrationTest {
    @Test
    fun migration1To2AddsColumnsAndEnrichmentTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE media_items (mediaId INTEGER PRIMARY KEY NOT NULL)")
                            db.execSQL("CREATE TABLE record_cache (mediaId INTEGER PRIMARY KEY NOT NULL)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        db.use {
            EngramDb.MIGRATION_1_2.migrate(it)
            // querying the new columns/table succeeds only if the migration applied
            it.query("SELECT dateModified FROM media_items").close()
            it.query("SELECT identityTakenAt FROM record_cache").close()
            it.query("SELECT mediaId FROM enrichment_cache").close()
        }
    }

    @Test
    fun migration3To4AddsDisplayName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE media_items (mediaId INTEGER PRIMARY KEY NOT NULL)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        db.use {
            EngramDb.MIGRATION_3_4.migrate(it)
            it.query("SELECT displayName FROM media_items").close()
        }
    }

    @Test
    fun migration2To3AddsCacheIdentityColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE media_items (mediaId INTEGER PRIMARY KEY NOT NULL)")
                            db.execSQL("CREATE TABLE record_cache (mediaId INTEGER PRIMARY KEY NOT NULL)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        db.use {
            EngramDb.MIGRATION_1_2.migrate(it)
            EngramDb.MIGRATION_2_3.migrate(it)
            // the new content-identity columns exist only if MIGRATION_2_3 applied
            it.query("SELECT originalName, contentHash FROM record_cache").close()
        }
    }

    @Test
    fun migration4To5RekeysRecordCachePreservingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // the v4 shape: single-column primary key
                            db.execSQL(
                                "CREATE TABLE record_cache (" +
                                    "mediaId INTEGER PRIMARY KEY NOT NULL, " +
                                    "identityTakenAt INTEGER NOT NULL DEFAULT 0, " +
                                    "sizeBytesAtScan INTEGER NOT NULL DEFAULT 0, " +
                                    "recordsBlob BLOB NOT NULL, " +
                                    "recordCount INTEGER NOT NULL DEFAULT 0, " +
                                    "updatedMillis INTEGER NOT NULL DEFAULT 0, " +
                                    "originalName TEXT NOT NULL DEFAULT '', " +
                                    "contentHash TEXT NOT NULL DEFAULT '')",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        db.use {
            it.execSQL(
                "INSERT INTO record_cache (mediaId, identityTakenAt, recordsBlob, recordCount, contentHash) " +
                    "VALUES (1, 100, x'01', 1, 'aa'), (2, 0, x'02', 2, '')",
            )
            EngramDb.MIGRATION_4_5.migrate(it)
            it.query("SELECT mediaId, identityTakenAt, contentHash FROM record_cache ORDER BY mediaId").use { c ->
                check(c.moveToFirst()) { "rows must survive the re-key" }
                check(c.getLong(0) == 1L && c.getLong(1) == 100L && c.getString(2) == "aa")
                check(c.moveToNext() && c.getLong(0) == 2L)
            }
            // the composite key now allows the same media id under a second capture
            it.execSQL(
                "INSERT INTO record_cache (mediaId, identityTakenAt, sizeBytesAtScan, recordsBlob, " +
                    "recordCount, updatedMillis) VALUES (1, 999, 0, x'03', 1, 0)",
            )
        }
    }
}
