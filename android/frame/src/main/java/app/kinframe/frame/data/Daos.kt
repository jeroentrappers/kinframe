package app.kinframe.frame.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlacementDao {
    @Query("SELECT * FROM placements WHERE id = :id")
    suspend fun get(id: String): PlacementEntity?

    @Upsert
    suspend fun upsert(p: PlacementEntity)

    @Query("DELETE FROM placements WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM placements")
    suspend fun deleteAll()

    @Query("SELECT * FROM placements")
    suspend fun all(): List<PlacementEntity>

    @Query("SELECT * FROM placements")
    fun observeAll(): Flow<List<PlacementEntity>>

    @Query("SELECT COUNT(*) FROM placements")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM placements WHERE mediaSha256 = :sha AND localFile IS NOT NULL")
    suspend fun countUsingSha(sha: String): Int

    @Query(
        """SELECT * FROM placements
           WHERE localFile IS NULL AND mediaUrl IS NOT NULL AND trashed = 0 AND evictedAt IS NULL
             AND status IN ('ready','delivered','seen') AND nextAttemptAt <= :now
           ORDER BY receivedAt DESC LIMIT :limit""",
    )
    suspend fun pendingDownloads(now: Long, limit: Int): List<PlacementEntity>

    @Query(
        """SELECT MIN(nextAttemptAt) FROM placements
           WHERE localFile IS NULL AND mediaUrl IS NOT NULL AND trashed = 0 AND evictedAt IS NULL
             AND status IN ('ready','delivered','seen')""",
    )
    suspend fun earliestRetry(): Long?

    @Query("UPDATE placements SET localFile = :file, storedAt = :at, downloadAttempts = 0, lastError = NULL, evictedAt = NULL WHERE id = :id")
    suspend fun markStored(id: String, file: String, at: Long)

    @Query("UPDATE placements SET localFile = :file, storedAt = :at, downloadAttempts = 0, lastError = NULL, evictedAt = NULL WHERE mediaSha256 = :sha AND localFile IS NULL")
    suspend fun markStoredBySha(sha: String, file: String, at: Long): Int

    /** Storage eviction: forget the file for every placement using it. */
    @Query("UPDATE placements SET localFile = NULL, storedAt = NULL, evictedAt = :at WHERE localFile = :file")
    suspend fun evict(file: String, at: Long): Int

    /** Re-download on demand: put an evicted placement back in the download queue, first in line. */
    @Query("UPDATE placements SET evictedAt = NULL, nextAttemptAt = 0, downloadAttempts = 0 WHERE id = :id AND evictedAt IS NOT NULL AND localFile IS NULL")
    suspend fun unevict(id: String): Int

    @Query("SELECT COUNT(*) FROM placements WHERE evictedAt IS NOT NULL AND localFile IS NULL")
    suspend fun countEvicted(): Int

    @Query("SELECT id FROM placements WHERE mediaSha256 = :sha")
    suspend fun idsWithSha(sha: String): List<String>

    @Query("UPDATE placements SET downloadAttempts = :attempts, nextAttemptAt = :next, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: String, attempts: Int, next: Long, error: String?)

    @Query("UPDATE placements SET localFile = NULL, storedAt = NULL WHERE id = :id")
    suspend fun clearLocalFile(id: String)

    @Query("UPDATE placements SET localFile = NULL, storedAt = NULL, nextAttemptAt = 0")
    suspend fun clearAllLocalFiles()

    @Query("UPDATE placements SET firstShownAt = COALESCE(firstShownAt, :at), lastShownAt = :at, isNew = 0 WHERE id = :id")
    suspend fun markShown(id: String, at: Long)

    @Query("UPDATE placements SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE placements SET rotation = :rotation WHERE id = :id")
    suspend fun setRotation(id: String, rotation: Int)

    /** [fit] "fill" or "fit"; null follows the frame's setting. */
    @Query("UPDATE placements SET fit = :fit WHERE id = :id")
    suspend fun setFit(id: String, fit: String?)

    @Query("UPDATE placements SET trashed = :trashed WHERE id = :id")
    suspend fun setTrashed(id: String, trashed: Boolean)

    @Query("UPDATE placements SET reaction = :reaction WHERE id = :id")
    suspend fun setReaction(id: String, reaction: String?)

    @Query("SELECT localFile FROM placements WHERE localFile IS NOT NULL UNION SELECT motionLocalFile FROM placements WHERE motionLocalFile IS NOT NULL")
    suspend fun allLocalFiles(): List<String>

    /** Live photos whose still is stored but whose clip is not (yet): the clip never blocks the still. */
    @Query(
        """SELECT * FROM placements
           WHERE kind = 'live' AND localFile IS NOT NULL AND motionUrl IS NOT NULL AND motionLocalFile IS NULL
             AND trashed = 0 AND motionNextAttemptAt <= :now
           ORDER BY receivedAt DESC LIMIT :limit""",
    )
    suspend fun pendingMotion(now: Long, limit: Int): List<PlacementEntity>

    @Query(
        """SELECT MIN(motionNextAttemptAt) FROM placements
           WHERE kind = 'live' AND localFile IS NOT NULL AND motionUrl IS NOT NULL AND motionLocalFile IS NULL AND trashed = 0""",
    )
    suspend fun earliestMotionRetry(): Long?

    @Query("UPDATE placements SET motionLocalFile = :file, motionAttempts = 0 WHERE motionSha256 = :sha")
    suspend fun markMotionStored(sha: String, file: String): Int

    @Query("UPDATE placements SET motionAttempts = :attempts, motionNextAttemptAt = :next WHERE id = :id")
    suspend fun markMotionFailed(id: String, attempts: Int, next: Long)

    @Query("SELECT COUNT(*) FROM placements WHERE localFile IS NOT NULL")
    suspend fun countCached(): Int

    @Query("SELECT COUNT(*) FROM placements WHERE localFile IS NULL AND mediaUrl IS NOT NULL AND trashed = 0 AND evictedAt IS NULL AND status IN ('ready','delivered','seen')")
    suspend fun countMissing(): Int
}

@Dao
interface MemberDao {
    @Upsert suspend fun upsert(m: MemberEntity)
    @Query("DELETE FROM members WHERE userId = :id") suspend fun delete(id: String)
    @Query("DELETE FROM members") suspend fun deleteAll()
    @Query("SELECT * FROM members") fun observeAll(): Flow<List<MemberEntity>>
    @Query("SELECT * FROM members WHERE userId = :id") suspend fun get(id: String): MemberEntity?
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0") suspend fun get(): SyncStateEntity?
    @Query("SELECT * FROM sync_state WHERE id = 0") fun observe(): Flow<SyncStateEntity?>
    @Upsert suspend fun put(s: SyncStateEntity)
    @Query("DELETE FROM sync_state") suspend fun deleteAll()
}

@Dao
interface AckDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: AckEntity): Long
    @Query("SELECT * FROM acks ORDER BY id LIMIT :limit") suspend fun batch(limit: Int): List<AckEntity>
    @Query("DELETE FROM acks WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM acks") suspend fun count(): Int
    /** Placements with an ack still to send: their files are never evicted. */
    @Query("SELECT DISTINCT placementId FROM acks") suspend fun placementIds(): List<String>
    @Query("DELETE FROM acks") suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(o: OutboxEntity): Long
    @Query("SELECT * FROM outbox WHERE notBefore <= :now ORDER BY id LIMIT 1") suspend fun nextDue(now: Long): OutboxEntity?
    @Query("SELECT MIN(notBefore) FROM outbox") suspend fun earliest(): Long?
    @Query("SELECT * FROM outbox WHERE placementId = :pid ORDER BY id") suspend fun forPlacement(pid: String): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY id") suspend fun ofKind(kind: String): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY id") fun observeKind(kind: String): Flow<List<OutboxEntity>>
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM outbox WHERE kind = :kind AND placementId = :pid") suspend fun deleteKindFor(kind: String, pid: String)
    @Query("UPDATE outbox SET attempts = :attempts, notBefore = :notBefore, payload = :payload WHERE id = :id")
    suspend fun reschedule(id: Long, attempts: Int, notBefore: Long, payload: String)
    @Query("DELETE FROM outbox") suspend fun deleteAll()
}
