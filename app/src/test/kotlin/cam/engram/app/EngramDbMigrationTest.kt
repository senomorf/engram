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
}
