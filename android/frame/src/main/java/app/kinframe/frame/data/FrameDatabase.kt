package app.kinframe.frame.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [PlacementEntity::class, MemberEntity::class, SyncStateEntity::class, AckEntity::class, OutboxEntity::class],
    version = 6,
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

        /** v6: who shared a placement from another frame (caption credit). */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN sharedByName TEXT DEFAULT NULL")
            }
        }

        /** v5: the merged calendar (`sync_state.calendarJson`). */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN calendarJson TEXT DEFAULT NULL")
            }
        }

        /** v4: Live/Motion Photo clips (`placement.motion`). */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN motionUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionSha256 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionMime TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionDurationMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionHasAudio INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionLocalFile TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE placements ADD COLUMN motionNextAttemptAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: album membership per placement (album mode). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN albumIds TEXT DEFAULT NULL")
            }
        }

        /** v2: storage eviction (`placements.evictedAt`). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN evictedAt INTEGER DEFAULT NULL")
            }
        }

        fun open(context: Context, name: String = NAME): FrameDatabase =
            Room.databaseBuilder(context, FrameDatabase::class.java, name)
                // WAL even on low-RAM devices (Room would pick TRUNCATE there): crash-safe and lets the
                // slideshow read while the sync engine writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // The server is the source of truth: a schema change simply re-syncs from scratch.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
