package app.kinframe.frame.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** The last `comments` change of a placement (`PlacementComments`: three newest, plain text): the comment card. */
@Entity(tableName = "placement_comments")
data class PlacementCommentsEntity(
    @PrimaryKey val placementId: String,
    val json: String,
    val updatedAt: Long,
)

/** A comment card shown on this frame: at most once per photo per local day (family-social.md §8). */
@Entity(tableName = "comment_cards_shown", primaryKeys = ["placementId", "date"])
data class CommentCardShownEntity(
    val placementId: String,
    /** The frame's local date, `YYYY-MM-DD`. */
    val date: String,
)

@Dao
interface SocialDao {
    @Upsert suspend fun putComments(e: PlacementCommentsEntity)
    @Query("SELECT * FROM placement_comments WHERE placementId = :id") suspend fun comments(id: String): PlacementCommentsEntity?
    @Query("DELETE FROM placement_comments WHERE placementId = :id") suspend fun deleteComments(id: String)
    @Query("DELETE FROM placement_comments") suspend fun deleteAllComments()

    /** -1 when the card was already shown for this photo on [CommentCardShownEntity.date]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markCardShown(e: CommentCardShownEntity): Long
    @Query("SELECT COUNT(*) > 0 FROM comment_cards_shown WHERE placementId = :id AND date = :date") suspend fun cardShown(id: String, date: String): Boolean
    /** Dates sort as text (`YYYY-MM-DD`). */
    @Query("DELETE FROM comment_cards_shown WHERE date < :before") suspend fun pruneCardsShown(before: String)
}
