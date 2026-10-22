package app.kinframe.frame.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [PlacementEntity::class, MemberEntity::class, SyncStateEntity::class, AckEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun placements(): PlacementDao
    abstract fun members(): MemberDao
    abstract fun syncState(): SyncStateDao
    abstract fun acks(): AckDao
    abstract fun outbox(): OutboxDao

    suspend fun <R> tx(block: suspend () -> R): R = withTransaction(block)

    /** Folds the WAL into the main database file (before power-off). */
    fun checkpoint() {
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    /** `PRAGMA quick_check` after an unclean shutdown. */
    fun quickCheckOk(): Boolean = runCatching {
        openHelper.writableDatabase.query("PRAGMA quick_check").use { c -> c.moveToFirst() && c.getString(0) == "ok" }
    }.getOrDefault(false)

    companion object {
        const val NAME = "frame.db"

        /** v2: storage eviction (`placements.evictedAt`). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN evictedAt INTEGER DEFAULT NULL")
            }
        }

        fun open(context: Context): FrameDatabase =
            Room.databaseBuilder(context, FrameDatabase::class.java, NAME)
                // WAL even on low-RAM devices (Room would pick TRUNCATE there): crash-safe and lets the
                // slideshow read while the sync engine writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // The server is the source of truth: a schema change simply re-syncs from scratch.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
