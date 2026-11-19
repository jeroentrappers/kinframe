package app.kinframe.core.api

import kotlinx.serialization.Serializable

/*
 * Hand-written models for feeds and subscriptions (openapi tags `feeds` and `subscriptions`, docs/spec/feeds.md).
 * Class names follow the schema names; fields map to snake_case through KinJson's naming strategy. Enum-like values
 * stay strings so a newer server never breaks parsing (README §7).
 */

object FeedRole {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val POSTER = "poster"
    /** Reads, comments, reacts and chats, but does not post photos (family social, README §5.18). */
    const val MEMBER = "member"
}

/**
 * `FeedSummary` and `Feed` in one class (Feed = FeedSummary + the detail fields), like [Frame]. The list endpoint
 * fills only the summary part; the rest keeps its defaults.
 */
@Serializable
data class Feed(
    val id: String,
    val name: String,
    /** Short-lived signed thumbnail of the cover item. */
    val coverUrl: String? = null,
    val role: String = FeedRole.POSTER,
    val memberCount: Int = 0,
    /** Active and paused subscriptions. */
    val subscriptionCount: Int = 0,
    val lastPostAt: String? = null,
    val createdAt: String? = null,
    val ownerName: String? = null,
    val coverItemId: String? = null,
    val autoApproveSubscriptions: Boolean = false,
    val inviteRequiresApproval: Boolean = true,
    val membersSeeReactions: Boolean = false,
    /** Posters may create, list and revoke invites too (default: only owner and admins). */
    val postersCanInvite: Boolean = false,
    /** Posters add birthday people directly instead of suggesting them (birthdays.md §4). */
    val postersCanAddBirthdays: Boolean = false,
    /** The caller's own preference: morning and greeting notifications for this feed's birthdays. */
    val birthdayNotifications: Boolean = true,
    /** Approved birthday people. */
    val birthdayCount: Int = 0,
    /** Suggestions waiting; owner and admins only (0 for posters). */
    val pendingBirthdayCount: Int = 0,
    val postCount: Int? = null,
    /** Owner and admins only (0 for posters). */
    val pendingSubscriptionCount: Int = 0,
    val pendingJoinRequestCount: Int = 0,
    // ---- family social (README §5.18–§5.20); absent on servers without it.
    /** Every member sees per-frame receipts of every post. */
    val membersSeeReceipts: Boolean = false,
    /** Joiners become posters (true) or members (false). */
    val newMembersCanPost: Boolean = true,
    val membersCanCreateCircles: Boolean = true,
    val circleCount: Int = 0,
    /** Frames on other servers may follow this family through `allow_remote` invites (README §5.22). */
    val allowRemoteFrames: Boolean = false,
    /** The caller's mute for this family ([app.kinframe.core.social.FeedMute]). */
    val mute: String = app.kinframe.core.social.FeedMute.NONE,
    val freshness: app.kinframe.core.social.Freshness? = null,
    /** Null when the family turned streaks off. */
    val streak: app.kinframe.core.social.FeedStreakSummary? = null,
    /** The family chat. */
    val conversationId: String? = null,
) {
    /** Posters and above post photos; `member` only reads, comments, reacts and chats. */
    val canPost: Boolean get() = role != FeedRole.MEMBER

    val isOwner: Boolean get() = role == FeedRole.OWNER

    /** Owner or admin: members, invites, subscriptions, settings and removing any post. */
    val isAdmin: Boolean get() = role == FeedRole.OWNER || role == FeedRole.ADMIN

    /** Create and revoke invites: admins, and posters when the feed allows it. */
    val canInvite: Boolean get() = isAdmin || postersCanInvite

    /** Adds birthday people directly (approved at once); everyone else suggests them. */
    val canAddBirthdays: Boolean get() = isAdmin || postersCanAddBirthdays
}

@Serializable
data class FeedCreate(
    val name: String,
    val autoApproveSubscriptions: Boolean = false,
    val inviteRequiresApproval: Boolean = true,
    /** Frames' reactions for every member: on by default since family social. */
    val membersSeeReactions: Boolean = true,
    val postersCanInvite: Boolean = false,
    val postersCanAddBirthdays: Boolean = false,
)

@Serializable
data class FeedMember(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String = FeedRole.POSTER,
    val joinedAt: String? = null,
    val lastPostedAt: String? = null,
)

object FeedInvitePurpose {
    /** Become a poster (after approval unless the feed's `invite_requires_approval` is off). */
    const val JOIN = "join"
    /** Subscribe frames you manage, pre-approved, without joining the feed. */
    const val SUBSCRIBE = "subscribe"
}

@Serializable
data class FeedInviteCreate(
    val purpose: String,
    /** Null means unlimited; always sent. */
    val maxUses: Int? = null,
    val expiresInHours: Int = 168,
    val note: String? = null,
    /** `subscribe` only, in families with `allow_remote_frames`: frames on other servers may use this link. */
    val allowRemote: Boolean = false,
)

@Serializable
data class FeedInvite(
    val id: String,
    val purpose: String = FeedInvitePurpose.JOIN,
    val token: String,
    /** 8 Crockford base32 characters; shown as `KF7Q-M2XA` ([FeedCodes.format]). */
    val code: String = "",
    /** `https://<web>/fi/<token>`; also shown as QR. */
    val url: String,
    val createdByName: String? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
    val note: String? = null,
    val expiresAt: String,
    /** Frames on other servers may use this link (README §5.22). */
    val allowRemote: Boolean = false,
)

@Serializable
data class FeedInviteFrame(
    val frameId: String,
    val frameName: String,
    /** A live subscription of this frame to the feed, or null. */
    val subscriptionStatus: String? = null,
)

@Serializable
data class FeedInvitePreview(
    val token: String,
    val purpose: String = FeedInvitePurpose.JOIN,
    val feedName: String,
    val coverUrl: String? = null,
    val ownerName: String? = null,
    val invitedByName: String = "",
    val memberCount: Int = 0,
    /** `join` only: the request waits for an admin. */
    val requiresApproval: Boolean = false,
    val alreadyMember: Boolean = false,
    /** `subscribe` only: the frames the caller manages and whether each already subscribes. */
    val frames: List<FeedInviteFrame> = emptyList(),
)

/** `FeedInviteAccept`: `subscribe` invites need [frameIds] (and [historyCount] with `last_n`); `join` sends nothing. */
@Serializable
data class FeedInviteAccept(
    val frameIds: List<String>? = null,
    val history: String? = null,
    val historyCount: Int? = null,
    /** `subscribe` invites: show the feed's birthday people on the frames (default true; null leaves the default). */
    val birthdays: Boolean? = null,
)

object FeedInviteStatus {
    const val JOINED = "joined"
    const val PENDING = "pending"
    const val ALREADY_MEMBER = "already_member"
    const val SUBSCRIBED = "subscribed"
}

@Serializable
data class FeedInviteResult(
    val status: String,
    val feed: Feed,
    val joinRequestId: String? = null,
    val subscriptions: List<Subscription> = emptyList(),
)

object SubscriptionStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val REJECTED = "rejected"
    const val REMOVED = "removed"

    /** Pending, active or paused: at most one per frame and feed. */
    fun isLive(status: String?): Boolean = status == PENDING || status == ACTIVE || status == PAUSED
}

object SubscriptionHistory {
    /** Only new posts. */
    const val NONE = "none"
    /** The `history_count` most recent items. */
    const val LAST_N = "last_n"
    const val ALL = "all"
    const val MAX_COUNT = 1000
}

/** `SubscriptionRequest` (`POST /feeds/{id}/subscriptions`). */
@Serializable
data class SubscriptionRequest(
    val frameId: String,
    val history: String = SubscriptionHistory.NONE,
    val historyCount: Int? = null,
    /** Show the feed's birthday people on the frame (default true). */
    val birthdays: Boolean = true,
)

/** `SubscriptionUpdate` (`PATCH /subscriptions/{id}`): at least one field; omitting [history] keeps it. */
@Serializable
data class SubscriptionUpdate(val history: String? = null, val historyCount: Int? = null, val birthdays: Boolean? = null)

@Serializable
data class SubscriptionFeed(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val memberCount: Int? = null,
    /** Owner first. */
    val adminNames: List<String> = emptyList(),
)

@Serializable
data class SubscriptionFrame(
    val id: String,
    val name: String,
    val location: String? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    /** The frame app shows birthdays (its heartbeat lists `birthdays`); false means "update the frame app". Null: unknown (older server). */
    val birthdaysSupported: Boolean? = null,
)

@Serializable
data class SubscriptionBackfill(val state: String = "idle", val placed: Int = 0, val total: Int = 0) {
    val running: Boolean get() = state == "queued" || state == "running"
}

@Serializable
data class SubscriptionEstimate(val items: Int = 0, val bytes: Long = 0)

object SubscriptionRemovedReason {
    const val UNSUBSCRIBED = "unsubscribed"
    const val REMOVED_BY_FEED = "removed_by_feed"
    const val FRAME_REMOVED = "frame_removed"
    const val FEED_DELETED = "feed_deleted"
    const val WITHDRAWN = "withdrawn"
}

@Serializable
data class Subscription(
    val id: String,
    val status: String = SubscriptionStatus.PENDING,
    val history: String = SubscriptionHistory.NONE,
    val historyCount: Int? = null,
    val feed: SubscriptionFeed,
    val frame: SubscriptionFrame,
    /** Feed owner/admins and frame managers only. */
    val requestedByName: String? = null,
    val viaInvite: Boolean = false,
    /** The feed's birthday people are shown on the frame while active (frame side; default true). */
    val birthdays: Boolean = true,
    /** Approved birthday people of the feed (frame managers outside the feed see only this). */
    val birthdayCount: Int? = null,
    val createdAt: String? = null,
    /** First activation: the history boundary. */
    val activatedAt: String? = null,
    val pausedAt: String? = null,
    val endedAt: String? = null,
    val removedReason: String? = null,
    val backfill: SubscriptionBackfill? = null,
    val estimate: SubscriptionEstimate? = null,
    /** Frame side: pause, resume, change history, unsubscribe. */
    val canManage: Boolean = false,
    /** Feed side: approve, reject, remove. */
    val canDecide: Boolean = false,
    /** The subscriber is a frame on another server (README §5.22). */
    val remote: app.kinframe.core.servers.SubscriptionRemote? = null,
) {
    val isLive: Boolean get() = SubscriptionStatus.isLive(status)
}

/** Per post: `frames` counts active subscriptions holding it; paused frames are only in [paused]. */
@Serializable
data class FeedReceiptSummary(
    val frames: Int = 0,
    val delivered: Int = 0,
    val seen: Int = 0,
    val awaitingApproval: Int = 0,
    val paused: Int = 0,
    val removed: Int = 0,
)

@Serializable
data class FeedReaction(
    val frameId: String,
    val frameName: String,
    val itemId: String,
    val placementId: String,
    val reaction: String? = null,
    val replies: List<String> = emptyList(),
    val at: String? = null,
)

@Serializable
data class FeedPoster(val userId: String, val displayName: String, val avatarUrl: String? = null)

@Serializable
data class FeedPost(
    val id: String,
    val feedId: String,
    val poster: FeedPoster,
    /** The poster's send (own posts only). */
    val sendId: String? = null,
    val createdAt: String,
    /** Live items in send order. */
    val items: List<Item> = emptyList(),
    /** Null when the caller may not see receipts of this post (feeds.md §4.1). */
    val receipts: FeedReceiptSummary? = null,
    val reactions: List<FeedReaction>? = null,
    /** A photo source or Google Photos import made this post for the poster: "Anna · 14 photos via Immich". */
    val via: ItemVia? = null,
    // ---- family social (README §5.18, §5.20)
    /** The circle this post was shared with; null for the whole family. */
    val circleId: String? = null,
    val circleName: String? = null,
    val promptId: String? = null,
    /** Comments and people's reactions; absent without `social`. */
    val thread: app.kinframe.core.social.ThreadSummary? = null,
)

@Serializable
data class FeedPostsPage(val posts: List<FeedPost> = emptyList(), val nextCursor: String? = null)

/** One item on one subscribed frame (`FeedDeliveryStatus`). */
object FeedDeliveryStatus {
    const val PENDING = "pending"
    const val READY = "ready"
    const val DELIVERED = "delivered"
    const val SEEN = "seen"
    const val FAILED = "failed"
    const val AWAITING_APPROVAL = "awaiting_approval"
    const val SUSPENDED = "suspended"
    const val REMOVED = "removed"
}

@Serializable
data class FeedReceiptReply(val preset: String? = null, val at: String? = null)

@Serializable
data class FeedReceiptItem(
    val itemId: String,
    val placementId: String? = null,
    val status: String = FeedDeliveryStatus.PENDING,
    val deliveredAt: String? = null,
    val seenAt: String? = null,
    val reaction: String? = null,
    val replies: List<FeedReceiptReply> = emptyList(),
)

@Serializable
data class FeedReceiptFrame(
    val subscriptionId: String,
    val frameId: String,
    val frameName: String,
    val frameLocation: String? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    /** `active` or `paused`. */
    val subscriptionStatus: String = SubscriptionStatus.ACTIVE,
    val items: List<FeedReceiptItem> = emptyList(),
)

@Serializable
data class FeedPostReceipts(
    val postId: String,
    val summary: FeedReceiptSummary = FeedReceiptSummary(),
    val frames: List<FeedReceiptFrame> = emptyList(),
)

/** One feed a send was posted to (`Send.feeds[]`). */
@Serializable
data class SendFeed(
    val feedId: String,
    val feedName: String,
    val postId: String,
    /** The post was removed from the feed (unsend or admin). */
    val removed: Boolean = false,
    val summary: FeedReceiptSummary? = null,
)

/** How a placement got onto the frame (`PlacementOrigin`). */
object PlacementOrigin {
    const val SEND = "send"
    const val SHARE = "share"
    const val FEED = "feed"
    const val IMPORT = "import"
}

/**
 * Feed invite codes (README §5.12): 8 Crockford base32 characters, shown as `KF7Q-M2XA`, never all digits, so the one
 * "Enter a code" field tells them apart from 8-digit frame codes.
 */
object FeedCodes {
    const val LENGTH = 8
    /** Crockford base32: digits and letters without I, L, O and U. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * The code characters someone typed or pasted, upper-cased, at most 8. Crockford's decoding rules apply (O → 0,
     * I and L → 1); spaces, hyphens and anything else are dropped. A pasted invite link is handled by the caller.
     */
    fun chars(input: String): String = buildString {
        for (ch in input.uppercase()) {
            val c = when (ch) { 'O' -> '0'; 'I', 'L' -> '1'; else -> ch }
            if (c in ALPHABET) append(c)
            if (length == LENGTH) break
        }
    }

    /** A complete feed code (8 characters, at least one letter), or null. */
    fun normalize(input: String): String? = chars(input).takeIf { it.length == LENGTH && it.any { c -> c.isLetter() } }

    /** Whether the input looks like a feed code rather than a frame code: it holds a letter. */
    fun looksLikeFeedCode(input: String): Boolean = input.any { it.isLetter() }

    /** `KF7QM2XA` → `KF7Q-M2XA`; partial input stays partial. */
    fun format(code: String): String = if (code.length <= 4) code else code.take(4) + "-" + code.drop(4)
}
