package app.kinframe.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Email to frame (openapi tag `email-inbox`; README §5.16, docs/spec/email-inbox.md): inbox addresses that turn mailed
 * photos into sends to a frame or posts to a feed, their senders and held mail. The operator's inbound webhook is not
 * for clients and has no call here. Enum-like values stay strings so a newer server never breaks parsing.
 */

/** What an inbox address or a Telegram chat fills: exactly one of a frame (a direct send) or a feed (a post). */
@Serializable
data class ChannelTarget(
    val kind: String = KIND_FRAME,
    val frameId: String? = null,
    val frameName: String? = null,
    val feedId: String? = null,
    val feedName: String? = null,
) {
    val isFeed: Boolean get() = kind == KIND_FEED
    val name: String get() = (if (isFeed) feedName else frameName).orEmpty()

    companion object {
        const val KIND_FRAME = "frame"
        const val KIND_FEED = "feed"
    }
}

/** Who mail (or Telegram media) from people without a matching account is sent by. */
@Serializable
data class ChannelOwner(val userId: String, val displayName: String, val avatarUrl: String? = null)

object EmailAddressStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
}

object EmailSenderState {
    const val ALLOWED = "allowed"
    const val BLOCKED = "blocked"
    const val PENDING = "pending"
}

object EmailSenderOrigin {
    const val MANUAL = "manual"
    const val APPROVED = "approved"
    const val MAIL = "mail"
}

/** `EmailHeldReason`: why a mail waits. */
object EmailHeldReason {
    /** Authenticated, but not allowed: approve or reject the sender. */
    const val UNKNOWN_SENDER = "unknown_sender"
    /** Couldn't be authenticated: deliver this one by hand, or delete it. Never approve the sender from it. */
    const val UNVERIFIED_SENDER = "unverified_sender"
}

/** `EmailOutcome`: what became of a mail (email-inbox.md §13). */
object EmailOutcome {
    const val DELIVERED = "delivered"
    const val HELD = "held"
    const val NO_MEDIA = "no_media"
    const val TOO_LARGE = "too_large"
    const val ENCRYPTED = "encrypted"
    const val DUPLICATE = "duplicate"
    const val BLOCKED = "blocked"
    const val REJECTED_DMARC = "rejected_dmarc"
    const val IGNORED = "ignored"
    const val RATE_LIMITED = "rate_limited"
    const val FAILED = "failed"
}

/** `email_sender.decided` decisions. */
object EmailSenderDecision {
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val BLOCKED = "blocked"
    const val ADDED = "added"
    const val REMOVED = "removed"
    const val UNBLOCKED = "unblocked"
}

/** Websocket message types for inbox addresses (README §6.2). */
object EmailEvents {
    const val ADDRESS_UPDATED = "email_address.updated"
    const val ADDRESS_REMOVED = "email_address.removed"
    const val SENDER_PENDING = "email_sender.pending"
    const val SENDER_DECIDED = "email_sender.decided"
}

/** Push keys for inbox addresses (README §6.3). */
object EmailPush {
    /** The address's managers: someone new is waiting (`ids`: `address_id`, `sender_id`, `frame_id` or `feed_id`). */
    const val PENDING_SENDER = "email_pending_sender"
    /** The same, 3 days before the held mail is deleted (a text variant of [PENDING_SENDER]). */
    const val PENDING_EXPIRING = "email_pending_expiring"
}

@Serializable
data class EmailAddressCounts(
    val allowedSenders: Int = 0,
    val blockedSenders: Int = 0,
    val pendingSenders: Int = 0,
    val heldMessages: Int = 0,
    /** Mails delivered in the last 30 days. */
    @SerialName("delivered_30d") val delivered30d: Int = 0,
)

/** An inbox address. [address] is a capability: shown to managers only. */
@Serializable
data class EmailAddress(
    val id: String,
    val address: String,
    val slug: String = "",
    val name: String = "",
    val target: ChannelTarget = ChannelTarget(),
    val owner: ChannelOwner? = null,
    val status: String = EmailAddressStatus.ACTIVE,
    val anyoneMaySend: Boolean = false,
    val sendConfirmations: Boolean = true,
    val offerAppInvite: Boolean = true,
    val notifyReactions: Boolean = true,
    /** False when the server can't send mail: senders get no replies at all. */
    val repliesAvailable: Boolean = true,
    val counts: EmailAddressCounts = EmailAddressCounts(),
    val lastReceivedAt: String? = null,
    val regeneratedAt: String? = null,
    val canManage: Boolean = false,
    val isOwner: Boolean = false,
    val createdAt: String? = null,
) {
    val isPaused: Boolean get() = status == EmailAddressStatus.PAUSED
}

/** `EmailAddressCreate`: everything optional; the server fills the slug and name from the target's name. */
@Serializable
data class EmailAddressCreate(
    val slug: String? = null,
    val name: String? = null,
    val anyoneMaySend: Boolean? = null,
    val sendConfirmations: Boolean? = null,
    val offerAppInvite: Boolean? = null,
    val notifyReactions: Boolean? = null,
)

/** `EmailAddressUpdate`: omitted fields are kept. */
@Serializable
data class EmailAddressUpdate(
    val name: String? = null,
    /** A new friendly part; the old address stops working at once. */
    val slug: String? = null,
    val paused: Boolean? = null,
    val anyoneMaySend: Boolean? = null,
    val sendConfirmations: Boolean? = null,
    val offerAppInvite: Boolean? = null,
    /** The owner's own switch. */
    val notifyReactions: Boolean? = null,
    val takeOver: Boolean? = null,
)

/** An allowed or blocked sender of one address (members of the target are allowed without being listed). */
@Serializable
data class EmailSender(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val state: String = EmailSenderState.ALLOWED,
    val origin: String = EmailSenderOrigin.MANUAL,
    val addedByName: String? = null,
    val lastMessageAt: String? = null,
    val createdAt: String? = null,
) {
    val isBlocked: Boolean get() = state == EmailSenderState.BLOCKED
}

@Serializable
data class EmailSenderCreate(val email: String, val displayName: String? = null, val blocked: Boolean = false)

/** `EmailSenderUpdate`: rename (an explicit null clears the name), block or unblock. */
data class EmailSenderUpdate(val displayName: String? = null, val clearName: Boolean = false, val blocked: Boolean? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        when {
            !displayName.isNullOrBlank() -> put("display_name", JsonPrimitive(displayName.trim().take(40)))
            clearName -> put("display_name", JsonNull)
        }
        blocked?.let { put("blocked", JsonPrimitive(it)) }
    }
}

@Serializable
data class EmailSenderApprove(val deliver: Boolean = true, val displayName: String? = null)

@Serializable
data class EmailSenderReject(val block: Boolean = false)

/** How a mail was checked (email-inbox.md §7). Only [authenticated] mail is matched, allowed or answered. */
@Serializable
data class EmailAuthResult(
    val authenticated: Boolean = false,
    val fromDomain: String? = null,
    val spf: String? = null,
    val dkim: String? = null,
    val dkimDomain: String? = null,
    val dmarc: String? = null,
    val dmarcPolicy: String? = null,
    val aligned: String? = null,
)

/** Files in a mail that were not used. */
@Serializable
data class EmailSkipCounts(
    val tooSmall: Int = 0,
    val duplicate: Int = 0,
    val unsupported: Int = 0,
    val tooLarge: Int = 0,
    val tooLong: Int = 0,
    val overLimit: Int = 0,
) {
    val total: Int get() = tooSmall + duplicate + unsupported + tooLarge + tooLong + overLimit
}

@Serializable
data class EmailHeldMessageSummary(
    val id: String,
    val reason: String = EmailHeldReason.UNKNOWN_SENDER,
    val subject: String? = null,
    val receivedAt: String? = null,
    val expiresAt: String? = null,
    val mediaCount: Int = 0,
    val authenticated: Boolean = false,
    /** The first thumbnails; short-lived signed URLs. */
    val thumbUrls: List<String> = emptyList(),
) {
    val isUnverified: Boolean get() = reason == EmailHeldReason.UNVERIFIED_SENDER || !authenticated
}

@Serializable
data class EmailHeldMedia(
    val kind: String = MediaKind.PHOTO,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val bytes: Long? = null,
    val filename: String? = null,
    /** Short-lived signed URL; null while processing. */
    val thumbUrl: String? = null,
)

/** One held mail with its photos and videos. [senderEmail] is trustworthy only when `auth.authenticated`. */
@Serializable
data class EmailHeldMessage(
    val id: String,
    val addressId: String = "",
    val senderId: String? = null,
    val senderEmail: String = "",
    val senderName: String? = null,
    val reason: String = EmailHeldReason.UNKNOWN_SENDER,
    val subject: String? = null,
    val caption: String? = null,
    val receivedAt: String? = null,
    val expiresAt: String? = null,
    val auth: EmailAuthResult = EmailAuthResult(),
    val media: List<EmailHeldMedia> = emptyList(),
    val skipped: EmailSkipCounts? = null,
) {
    val isUnverified: Boolean get() = reason == EmailHeldReason.UNVERIFIED_SENDER || !auth.authenticated
}

/** Someone waiting for approval, with their held mail (newest first). [email] is as claimed. */
@Serializable
data class EmailPendingSender(
    val id: String,
    val email: String,
    val displayName: String? = null,
    /** The latest held mail was authenticated. */
    val authenticated: Boolean = false,
    val firstHeldAt: String? = null,
    val lastHeldAt: String? = null,
    val messages: List<EmailHeldMessageSummary> = emptyList(),
) {
    val mediaCount: Int get() = messages.sumOf { it.mediaCount }
}

/** One mail of the last 30 days and what became of it. */
@Serializable
data class EmailMessage(
    val id: String,
    val receivedAt: String? = null,
    val senderEmail: String? = null,
    val senderName: String? = null,
    val authenticated: Boolean = false,
    val outcome: String = EmailOutcome.DELIVERED,
    /** Photos and videos delivered or held. */
    val items: Int = 0,
    val skipped: EmailSkipCounts? = null,
    val sendIds: List<String> = emptyList(),
    /** The reply the sender got, if any (`EmailReplyKind`). */
    val reply: String? = null,
)

@Serializable
data class EmailMessagePage(val items: List<EmailMessage> = emptyList(), val nextCursor: String? = null)
