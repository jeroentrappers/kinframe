package app.kinframe.core.social

import app.kinframe.core.api.Item
import kotlinx.serialization.Serializable

/*
 * Hand-written models for chats and safety (openapi tags `chats`, `safety`, README §5.19). Enum-like values stay
 * strings (README §7).
 */

object ConversationKind {
    const val DIRECT = "direct"
    const val GROUP = "group"
    const val FAMILY = "family"
    const val CIRCLE = "circle"
}

object ConversationRole {
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

/** `MuteFor`: how long to mute a conversation. */
object MuteFor {
    const val OFF = "off"
    const val HOUR = "1h"
    const val EIGHT_HOURS = "8h"
    const val WEEK = "1w"
    const val ALWAYS = "always"

    val VALUES = listOf(HOUR, EIGHT_HOURS, WEEK, ALWAYS)
}

object MessageKind {
    const val PERSON = "person"
    const val SYSTEM = "system"
}

object SystemMessageType {
    const val PUT_ON_FRAME = "put_on_frame"
    const val STREAK_MILESTONE = "streak_milestone"
    const val MONTHLY_RECAP = "monthly_recap"
    const val PROMPT = "prompt"
    const val MEMBER_JOINED = "member_joined"
    const val MEMBER_LEFT = "member_left"
}

object VoiceStatus {
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val FAILED = "failed"
}

object LinkPreviewStatus {
    const val PENDING = "pending"
    const val OK = "ok"
    const val FAILED = "failed"
}

/** `typing` activity. */
object TypingActivity {
    const val TYPING = "typing"
    const val RECORDING = "recording"
}

@Serializable
data class ConversationMember(
    val person: PersonRef,
    val role: String = ConversationRole.MEMBER,
    val joinedAt: String? = null,
    /** Only when read receipts are shown in this conversation and this member shows them too. */
    val lastReadMessageId: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val kind: String,
    val name: String = "",
    val photoUrl: String? = null,
    val feedId: String? = null,
    val circleId: String? = null,
    /** `direct` only: the other person. */
    val other: PersonRef? = null,
    val members: List<ConversationMember> = emptyList(),
    val memberCount: Int = 0,
    val myRole: String = ConversationRole.MEMBER,
    val unreadCount: Int = 0,
    val unreadMentions: Int = 0,
    val readUpTo: String? = null,
    val lastMessage: Message? = null,
    val lastActivityAt: String = "",
    val muted: Boolean = false,
    /** Null with [muted] means always. */
    val mutedUntil: String? = null,
    val archived: Boolean = false,
    val readOnly: Boolean = false,
    /** Why [readOnly] ([app.kinframe.core.leaving.ReadOnlyReason]); `no_longer_contacts`: "You can no longer message Lies". */
    val readOnlyReason: String? = null,
    /** "Seen" is shown here: direct chats and groups of up to 10 people. */
    val readReceipts: Boolean = false,
    val createdAt: String? = null,
) {
    val isGroup: Boolean get() = kind == ConversationKind.GROUP
    val isDirect: Boolean get() = kind == ConversationKind.DIRECT
    val isFamily: Boolean get() = kind == ConversationKind.FAMILY
    val isCircle: Boolean get() = kind == ConversationKind.CIRCLE
    val isAdmin: Boolean get() = myRole == ConversationRole.ADMIN
}

@Serializable
data class VoiceClip(
    val itemId: String,
    val status: String = VoiceStatus.PROCESSING,
    val durationMs: Long? = null,
    /** 64 loudness values 0–100 (empty while processing). */
    val waveform: List<Int> = emptyList(),
)

/** The message replied to, as quoted above the reply. */
@Serializable
data class MessageQuote(
    val id: String,
    val author: PersonRef? = null,
    val excerpt: String? = null,
    val mediaKind: String? = null,
    val voice: Boolean = false,
    val thumbUrl: String? = null,
    val deleted: Boolean = false,
)

/** A post shared in a chat ("Share in chat"). */
@Serializable
data class PostCard(
    val postId: String,
    val available: Boolean = false,
    val kind: String? = null,
    val feedName: String? = null,
    val frameName: String? = null,
    val poster: PersonRef? = null,
    val createdAt: String? = null,
    val itemCount: Int = 0,
    val thumbUrls: List<String> = emptyList(),
    val caption: String? = null,
)

@Serializable
data class LinkPreview(
    val url: String,
    val status: String = LinkPreviewStatus.PENDING,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    /** Served by the server: clients never load remote images. */
    val imageUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

/** The content of a `system` message; rendered in the viewer's language from these fields. */
@Serializable
data class SystemMessage(
    val type: String,
    val person: PersonRef? = null,
    val sendId: String? = null,
    val sourceMessageId: String? = null,
    val itemCount: Int? = null,
    val frameNames: List<String> = emptyList(),
    val feedNames: List<String> = emptyList(),
    val weeks: Int? = null,
    val month: String? = null,
    val recap: MonthlyRecap? = null,
    val promptId: String? = null,
    val promptTitle: String? = null,
)

/** Frame reactions and quick replies on placements made from a message by "Put on a frame". */
@Serializable
data class FrameActivity(
    val frameId: String,
    val frameName: String,
    val itemId: String,
    val reaction: String? = null,
    val replies: List<String> = emptyList(),
)

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val kind: String = MessageKind.PERSON,
    val author: PersonRef? = null,
    val clientId: String? = null,
    val body: String? = null,
    val mentions: List<Mention> = emptyList(),
    val items: List<Item> = emptyList(),
    val voice: VoiceClip? = null,
    val replyTo: MessageQuote? = null,
    val sharedPost: PostCard? = null,
    val linkPreview: LinkPreview? = null,
    val system: SystemMessage? = null,
    val reactions: ReactionSummary = ReactionSummary.EMPTY,
    val frameActivity: List<FrameActivity> = emptyList(),
    val createdAt: String = "",
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val removedByAdmin: Boolean = false,
) {
    val isSystem: Boolean get() = kind == MessageKind.SYSTEM
}

@Serializable
data class MessagePage(
    /** Oldest first. */
    val messages: List<Message> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val hasMoreAfter: Boolean = false,
)

@Serializable
data class MessageTrim(val startMs: Long? = null, val endMs: Long? = null)

/** One photo or video of a message, as in `SendCreate.items`. */
@Serializable
data class MessageItemCreate(
    val uploadId: String,
    val caption: String? = null,
    val capturedAt: String? = null,
    val trim: MessageTrim? = null,
    val includeAudio: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val liveVideoUploadId: String? = null,
    val live: Boolean? = null,
)

@Serializable
data class VoiceCreate(val uploadId: String, val durationMs: Long? = null)

@Serializable
data class MessageCreate(
    val clientId: String? = null,
    val body: String? = null,
    val mentions: List<Mention>? = null,
    val items: List<MessageItemCreate>? = null,
    val voice: VoiceCreate? = null,
    val replyToId: String? = null,
    val sharedPostId: String? = null,
    val linkPreview: Boolean? = null,
)

@Serializable
data class MessageUpdate(val body: String, val mentions: List<Mention>? = null)

@Serializable
data class PutOnFrameRequest(
    val frameIds: List<String>,
    val feedIds: List<String>? = null,
    val circleIds: List<String>? = null,
    val itemIds: List<String>? = null,
)

@Serializable
data class ConversationCreate(val kind: String, val memberIds: List<String>, val name: String? = null)

@Serializable
data class FeedRef(val feedId: String, val feedName: String)

@Serializable
data class FrameRef(val frameId: String, val frameName: String)

/** A contact: someone the caller shares a family or a frame with. */
@Serializable
data class Person(
    val person: PersonRef,
    val feeds: List<FeedRef> = emptyList(),
    val frames: List<FrameRef> = emptyList(),
    val blocked: Boolean = false,
    val directConversationId: String? = null,
    val milestones: List<String> = emptyList(),
)

/** The app badge: unread conversations (not muted) plus threads with unread mentions or replies. */
@Serializable
data class Badge(val total: Int = 0, val conversations: Int = 0, val threads: Int = 0)

@Serializable
data class Block(val person: PersonRef, val createdAt: String? = null)

object ReportTargetKind {
    const val COMMENT = "comment"
    const val MESSAGE = "message"
    const val POST = "post"
}

object ReportReason {
    const val UNKIND = "unkind"
    const val SPAM = "spam"
    const val INAPPROPRIATE = "inappropriate"
    const val OTHER = "other"

    val ALL = listOf(UNKIND, SPAM, INAPPROPRIATE, OTHER)
}

object ReportStatus {
    const val OPEN = "open"
    const val REMOVED = "removed"
    const val DISMISSED = "dismissed"
}

@Serializable
data class ReportCreate(val targetKind: String, val targetId: String, val reason: String, val note: String? = null)

@Serializable
data class Report(
    val id: String,
    val targetKind: String,
    val targetId: String,
    val postId: String? = null,
    val conversationId: String? = null,
    val feedId: String? = null,
    val reason: String = ReportReason.OTHER,
    val note: String? = null,
    val author: PersonRef? = null,
    val excerpt: String? = null,
    val status: String = ReportStatus.OPEN,
    val mine: Boolean = false,
    val createdAt: String = "",
    val resolvedAt: String? = null,
) {
    val isOpen: Boolean get() = status == ReportStatus.OPEN
}
