package cam.engram.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItemEntity::class, RecordCacheEntity::class, DraftEntity::class, MemoryFts::class],
    version = 1,
    exportSchema = false,
)
abstract class EngramDb : RoomDatabase() {
    abstract fun media(): MediaDao

    abstract fun recordCache(): RecordCacheDao

    abstract fun drafts(): DraftDao

    abstract fun search(): SearchDao

    companion object {
        fun build(context: Context): EngramDb =
            Room
                .databaseBuilder(context, EngramDb::class.java, "engram.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): EngramDb =
            Room
                .inMemoryDatabaseBuilder(context, EngramDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
