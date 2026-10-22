package app.kinframe.frame.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local mirror of a placement (an item on this frame) plus local download/playback state. */
@Entity(tableName = "placements", indices = [Index("mediaSha256"), Index("receivedAt")])
data class PlacementEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val kind: String,
    val status: String,
    val senderId: String?,
    val senderName: String?,
    val caption: String?,
    val captionEdited: Boolean,
    val capturedAt: Long?,
    val receivedAt: Long,
    val focalX: Double?,
    val focalY: Double?,
    val fit: String?,
    val rotation: Int,
    val hidden: Boolean,
    val trashed: Boolean,
    val pendingModeration: Boolean,
    val reaction: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val hasAudio: Boolean?,
    val mediaUrl: String?,
    val mediaSha256: String?,
    val mediaBytes: Long,
    val mediaMime: String?,
    // ---- local state, preserved across upserts
    /** File name inside the media directory once downloaded and verified. */
    val localFile: String? = null,
    val storedAt: Long? = null,
    val downloadAttempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val firstShownAt: Long? = null,
    val lastShownAt: Long? = null,
    /** Arrived while the frame was running and not shown yet: plays first (new-photo experience). */
    val isNew: Boolean = false,
) {
    val isVideo: Boolean get() = kind == "video"
    val isDeliverable: Boolean get() = status == "ready" || status == "delivered" || status == "seen"
    val isVisible: Boolean get() = isDeliverable && !hidden && !trashed && !pendingModeration
}

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: String?,
)

/** Single row (id = 0): change-feed cursor and frame-level documents. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val nextSeq: Long = 0,
    val settingsJson: String? = null,
    val frameName: String? = null,
    val frameLocation: String? = null,
    val ownerName: String? = null,
    val weatherJson: String? = null,
)

/** Durable ack queue (README §5.4): delivered when stored, seen on first display. */
@Entity(tableName = "acks", indices = [Index(value = ["placementId", "kind"], unique = true)])
data class AckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placementId: String,
    val kind: String,
    val at: Long,
) {
    companion object {
        const val DELIVERED = "delivered"
        const val SEEN = "seen"
    }
}

/** Durable queue of actions taken at the frame, sent when online (reactions, hide/delete, settings). */
@Entity(tableName = "outbox", indices = [Index("placementId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val placementId: String?,
    val payload: String,
    val notBefore: Long,
    val attempts: Int = 0,
    val createdAt: Long,
) {
    companion object {
        const val REACTION = "reaction"
        const val PLACEMENT_PATCH = "placement_patch"
        const val SETTINGS_PATCH = "settings_patch"
        const val REPLY = "reply"
    }
}
