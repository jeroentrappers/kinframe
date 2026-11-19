package app.kinframe.frame.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [PlacementEntity::class, MemberEntity::class, SyncStateEntity::class, AckEntity::class, OutboxEntity::class, FeedTombstoneEntity::class, BirthdayGreetingEntity::class, AlbumFlagEntity::class],
    version = 11,
    exportSchema = true,
)
abstract class FrameDatabase : RoomDatabase() {
    abstract fun placements(): PlacementDao
    abstract fun members(): MemberDao
    abstract fun syncState(): SyncStateDao
    abstract fun acks(): AckDao
    abstract fun outbox(): OutboxDao
    abstract fun feedTombstones(): FeedTombstoneDao
    abstract fun birthdayGreetings(): BirthdayGreetingDao
    abstract fun albumFlags(): AlbumFlagDao

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

        /** v11: email to frame and Telegram: the service of `item.via`, so the caption reads "Jan via email" (email-inbox.md §9.2). */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN viaService TEXT DEFAULT NULL")
            }
        }

        /**
         * v10: photo sources (docs/spec/photo-sources.md): the service credited in the caption ("via Immich", §9.5) and
         * album flags for "Only in album mode" (§9.1). Albums weren't stored before, so the flags arrive with the next
         * `album.upsert`; until then everything plays as before.
         */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN viaLabel TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `album_flags` (`albumId` TEXT NOT NULL, `onlyInAlbumMode` INTEGER NOT NULL, PRIMARY KEY(`albumId`))")
            }
        }

        /** v9: birthdays (docs/spec/birthdays.md): the last `birthdays` change and the greetings sent from the frame. */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN birthdaysJson TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `birthday_greetings` (`birthdayId` TEXT NOT NULL, `date` TEXT NOT NULL, `sentAt` INTEGER NOT NULL, PRIMARY KEY(`birthdayId`, `date`))")
            }
        }

        /** v8: feeds (docs/spec/feeds.md): placement origin, the feed that placed it, the feeds holding it, backfill; tombstones for pause. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN origin TEXT NOT NULL DEFAULT 'send'")
                db.execSQL("ALTER TABLE placements ADD COLUMN originFeedId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN originFeedName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN feedIds TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN backfill INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `feed_tombstones` (`placementId` TEXT NOT NULL, `removedAt` INTEGER NOT NULL, PRIMARY KEY(`placementId`))")
            }
        }

        /** v7: items imported from Frameo's leftover data (caption credit "Anna · via Frameo"). */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE placements ADD COLUMN importedFrom TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE placements ADD COLUMN importedSenderName TEXT DEFAULT NULL")
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
