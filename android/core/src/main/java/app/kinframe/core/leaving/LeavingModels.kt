package app.kinframe.core.leaving

import app.kinframe.core.api.Item
import app.kinframe.core.social.PersonRef
import kotlinx.serialization.Serializable

/*
 * Hand-written models for leaving a family (openapi tag `leaving`, README §5.21, docs/spec/leaving.md). Class names
 * follow the schema names; fields map to snake_case through KinJson's naming strategy. Enum-like values stay strings
 * so a newer server never breaks parsing (README §7).
 */

/** `PhotosChoice`: what happens to the leaver's own photos. */
object PhotosChoice {
    /** They stay with the family or frame, still credited (the default). */
    const val KEEP = "keep"
    /** Taken away with the 30-day notice in the frames' trash. */
    const val TAKE = "take"
}

/** `CommentsChoice`. */
object CommentsChoice {
    const val KEEP = "keep"
    /** The leaver's comments in that family become tombstones. */
    const val DELETE = "delete"
}

/** `AccountDeletion.photos`. */
object DeletionPhotos {
    /** Default: every photo leaves every frame and family with the 30-day notice. */
    const val TAKE = "take"
    /** Each photo is handed over to the family's or frame's owner, credited "<name> (former member)". */
    const val LEAVE = "leave"
}

object KeepRequestStatus {
    const val OPEN = "open"
    const val ALLOWED = "allowed"
    const val PARTLY_ALLOWED = "partly_allowed"
    const val DECLINED = "declined"
    const val EXPIRED = "expired"
}

object KeepAnswer {
    const val ALL = "all"
    const val SOME = "some"
    const val NONE = "none"
}

/** `TakenAwayGroup.cannot_ask_reason`. */
object CannotAskReason {
    /** "Tom doesn't accept requests". */
    const val BLOCKED = "blocked"
    const val ALREADY_ASKED = "already_asked"
    const val EXPIRED = "expired"
    const val ACCOUNT_DELETED = "account_deleted"
    const val NOT_ALLOWED = "not_allowed"
}

/** `Conversation.read_only_reason`. */
object ReadOnlyReason {
    const val ARCHIVED = "archived"
    const val ACCOUNT_GONE = "account_gone"
    const val BLOCKED = "blocked"
    /** "You can no longer message Lies". */
    const val NO_LONGER_CONTACTS = "no_longer_contacts"
}

/** Push keys added by leaving (`LeavingPushKey`, README §6.3). */
object LeavingPush {
    const val PHOTOS_TAKEN_AWAY = "photos_taken_away"
    const val KEEP_REQUEST = "keep_request"
    const val KEEP_ANSWERED = "keep_answered"
    const val OWNERSHIP_CHANGED = "ownership_changed"
    val all = setOf(PHOTOS_TAKEN_AWAY, KEEP_REQUEST, KEEP_ANSWERED, OWNERSHIP_CHANGED)
}

/** Websocket event types added by leaving (README §6.2). */
object LeavingEvents {
    const val PLACEMENT_UPDATED = "placement.updated"
    const val KEEP_REQUEST_UPDATED = "keep_request.updated"
}

/** `placement.updated` reasons. */
object PlacementUpdateReason {
    const val TAKEN_AWAY = "taken_away"
    const val RESTORED = "restored"
    const val PURGED = "purged"
}

@Serializable
data class LeaveFeedRequest(
    val photos: String = PhotosChoice.KEEP,
    val comments: String = CommentsChoice.KEEP,
    val leaveFrameIds: List<String> = emptyList(),
)

@Serializable
data class LeaveFrameRequest(val photos: String = PhotosChoice.KEEP)

@Serializable
data class TakeAwayRequest(val comments: String = CommentsChoice.KEEP)

@Serializable
data class AccountDeletion(val photos: String = DeletionPhotos.TAKE)

@Serializable
data class TakeAwayFrame(val frameId: String, val frameName: String, val photos: Int = 0)

@Serializable
data class TakeAwayResult(
    val takingId: String? = null,
    val photosTakenAway: Int = 0,
    val commentsDeleted: Int = 0,
    /** When the frames' trash deletes what nobody asked to keep. */
    val until: String? = null,
    val frames: List<TakeAwayFrame> = emptyList(),
    val remoteFrames: Int = 0,
    val leftFrameIds: List<String> = emptyList(),
)

@Serializable
data class LeavePreviewFrame(
    val frameId: String,
    val frameName: String,
    val role: String = "",
    val canLeave: Boolean = true,
    /** Checked by default: the frame gets this family's photos and the caller is not its owner. */
    val suggested: Boolean = false,
    val directPhotos: Int = 0,
)

@Serializable
data class LeavePreview(
    val scope: String,
    val feedId: String? = null,
    val frameId: String? = null,
    val name: String,
    /** False for the take-away sheet of people who left or were removed. */
    val member: Boolean = true,
    val owner: Boolean = false,
    val photos: Int = 0,
    val comments: Int = 0,
    val alreadyTakenAway: Int = 0,
    val frames: List<LeavePreviewFrame> = emptyList(),
)

@Serializable
data class FormerFeed(val feedId: String, val name: String, val photos: Int = 0, val leftAt: String? = null)

@Serializable
data class FormerFrame(val frameId: String, val name: String, val photos: Int = 0, val leftAt: String? = null)

@Serializable
data class FormerPlaces(val feeds: List<FormerFeed> = emptyList(), val frames: List<FormerFrame> = emptyList())

@Serializable
data class KeepRequestItem(val item: Item, val allowed: Boolean? = null)

@Serializable
data class KeepRequest(
    val id: String,
    val status: String = KeepRequestStatus.OPEN,
    val scope: String = "frame",
    val frameId: String? = null,
    val frameName: String? = null,
    val feedId: String? = null,
    val feedName: String? = null,
    val takingId: String,
    /** The person asked (who took the photos away). */
    val person: PersonRef? = null,
    val askedBy: PersonRef? = null,
    val askedByName: String = "",
    val note: String? = null,
    val askedAt: String = "",
    val expiresAt: String = "",
    val answeredAt: String? = null,
    val itemCount: Int = 0,
    val allowedCount: Int = 0,
    /** `getKeepRequest` only. */
    val items: List<KeepRequestItem> = emptyList(),
) {
    val isOpen: Boolean get() = status == KeepRequestStatus.OPEN
    /** The frame's or family's name. */
    val placeName: String get() = frameName ?: feedName.orEmpty()
}

@Serializable
data class KeepRequestList(val requests: List<KeepRequest> = emptyList())

@Serializable
data class KeepRequestCreate(
    val takingId: String,
    val frameId: String? = null,
    val feedId: String? = null,
    /** Absent: every item of the taking in this scope. */
    val itemIds: List<String>? = null,
    val note: String? = null,
)

@Serializable
data class KeepRequestAnswer(val allow: String, val itemIds: List<String>? = null)

@Serializable
data class TakenAwayGroup(
    val takingId: String,
    val by: PersonRef? = null,
    val byName: String = "",
    val takenAt: String = "",
    val until: String = "",
    val count: Int = 0,
    val previewThumbUrls: List<String> = emptyList(),
    val keepRequest: KeepRequest? = null,
    val canAsk: Boolean = false,
    val cannotAskReason: String? = null,
)

@Serializable
data class TakenAwayGroupList(val groups: List<TakenAwayGroup> = emptyList())

/** `Placement.taken_away`. */
@Serializable
data class PlacementTakenAway(
    val takingId: String,
    val byUserId: String? = null,
    val byName: String = "",
    val at: String = "",
    val until: String = "",
    val keepRequestStatus: String? = null,
)

@Serializable
data class MemoryPreferences(
    val hiddenPeople: List<PersonRef> = emptyList(),
    val hiddenMemories: List<String> = emptyList(),
)

/** `PUT /v1/me/memories` replaces the lists it carries; a null list is left alone. */
@Serializable
data class MemoryPreferencesUpdate(
    val hiddenPeople: List<String>? = null,
    val hiddenMemories: List<String>? = null,
)
