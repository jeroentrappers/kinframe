package app.kinframe.frame.data

import androidx.room.ColumnInfo
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
    /** The file was removed to free space (least recently shown); downloaded again only when it is about to show. */
    val evictedAt: Long? = null,
    /** Albums this placement is in, comma-separated ids (album mode plays one album). */
    val albumIds: String? = null,
    // ---- Live/Motion Photos (README §5.3): the short clip played over the still (`placement.motion`)
    val motionUrl: String? = null,
    val motionSha256: String? = null,
    @ColumnInfo(defaultValue = "0") val motionBytes: Long = 0,
    val motionMime: String? = null,
    val motionDurationMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val motionHasAudio: Boolean = false,
    /** The clip's file once downloaded; never needed to show the still. */
    val motionLocalFile: String? = null,
    @ColumnInfo(defaultValue = "0") val motionAttempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val motionNextAttemptAt: Long = 0,
) {
    /** A Live/Motion Photo: shown as a photo, its clip plays over it when downloaded. */
    val isLive: Boolean get() = kind == "live"
    val hasMotionFile: Boolean get() = isLive && motionLocalFile != null
    val albumIdList: List<String> get() = albumIds?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
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
    /** Last `calendar` change (`FrameCalendar`), so the calendar shows after a reboot while offline. */
    val calendarJson: String? = null,
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
        /** Allow/deny of a join request; `placementId` holds the request id. */
        const val JOIN_DECISION = "join_decision"
        /** Accept/decline of an event invitation; `placementId` holds the invitation id. */
        const val INVITATION_DECISION = "invitation_decision"
        /** Countdown on/off for a calendar event; `placementId` holds the event id. */
        const val COUNTDOWN = "countdown"
    }
}
