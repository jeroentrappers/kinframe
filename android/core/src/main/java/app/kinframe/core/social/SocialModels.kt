package app.kinframe.core.social

import app.kinframe.core.api.Item
import app.kinframe.core.api.ItemVia
import kotlinx.serialization.Serializable

/*
 * Hand-written models for family social (openapi tags `social` and `circles`, README §5.18,
 * docs/spec/family-social.md). Class names follow the schema names; fields map to snake_case through KinJson's naming
 * strategy. Enum-like values stay strings so a newer server never breaks parsing (README §7).
 */

/** `PersonReaction`: the frame's four plus two for people. */
object PersonReaction {
    const val LOVE = "love"
    const val LAUGH = "laugh"
    const val WOW = "wow"
    const val SAD = "sad"
    const val THUMBS_UP = "thumbs_up"
    const val PRAY = "pray"

    val ALL = listOf(LOVE, LAUGH, WOW, SAD, THUMBS_UP, PRAY)

    val EMOJI = mapOf(LOVE to "❤️", LAUGH to "😂", WOW to "😮", SAD to "😢", THUMBS_UP to "👍", PRAY to "🙏")

    fun emoji(key: String?): String = EMOJI[key].orEmpty()
}

/** `ReactionTargetKind`. */
object ReactionTarget {
    const val POST = "post"
    const val POST_ITEM = "post_item"
    const val COMMENT = "comment"
    const val MESSAGE = "message"
}

object ThreadState {
    const val OPEN = "open"
    const val CLOSED = "closed"
}

object PostKind {
    const val FEED = "feed"
    const val FRAME = "frame"
}

object CommentKind {
    const val PERSON = "person"
    const val FRAME = "frame"
}

/** `FeedMute`: the caller's mute for one family. */
object FeedMute {
    const val NONE = "none"
    const val POSTS = "posts"
    const val CHAT = "chat"
    const val ALL = "all"

    val VALUES = listOf(NONE, POSTS, CHAT, ALL)
}

object CircleRole {
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

/** A person next to a comment, message or reaction; [formerMember] for deleted accounts ("Former member"). */
@Serializable
data class PersonRef(
    val userId: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val formerMember: Boolean = false,
)

/** A mention span in a body: [start] and [length] in UTF-16 code units (what Kotlin's String counts). */
@Serializable
data class Mention(val userId: String, val start: Int, val length: Int)

@Serializable
data class ReactionCount(val emoji: String, val count: Int)

@Serializable
data class PersonReactionRef(val person: PersonRef, val emoji: String, val at: String? = null)

@Serializable
data class FrameReactionRef(
    val frameId: String,
    val frameName: String,
    val reaction: String,
    val itemId: String? = null,
    val at: String? = null,
    /** A frame on another server: "Oma's frame (kinframe.appmire.be)" (README §5.22). */
    val serverName: String? = null,
)

/** The reaction row of a post, post item, comment or message: people and frames together. */
@Serializable
data class ReactionSummary(
    val total: Int = 0,
    val counts: List<ReactionCount> = emptyList(),
    val people: List<PersonReactionRef> = emptyList(),
    val frames: List<FrameReactionRef> = emptyList(),
    /** The caller's own reaction. */
    val mine: String? = null,
) {
    /**
     * The row after the caller changed their own reaction from [mine] to [emoji] (null removes it), so the screen
     * answers at once; the server's summary replaces it when it arrives.
     */
    fun withMine(emoji: String?): ReactionSummary {
        if (emoji == mine) return this
        val m = counts.associate { it.emoji to it.count }.toMutableMap()
        mine?.let { old -> m[old] = (m[old] ?: 1) - 1 }
        emoji?.let { m[it] = (m[it] ?: 0) + 1 }
        val newCounts = m.filterValues { it > 0 }.map { ReactionCount(it.key, it.value) }.sortedByDescending { it.count }
        val delta = (if (emoji != null) 1 else 0) - (if (mine != null) 1 else 0)
        return copy(total = (total + delta).coerceAtLeast(0), counts = newCounts, mine = emoji)
    }

    companion object {
        val EMPTY = ReactionSummary()
    }
}

@Serializable
data class ReactionList(
    val people: List<PersonReactionRef> = emptyList(),
    val frames: List<FrameReactionRef> = emptyList(),
)

/** A frame entry's details ("Oma's frame loved this", "Oma's frame: Call me!"). */
@Serializable
data class CommentFrame(
    val frameId: String,
    val frameName: String,
    val itemId: String? = null,
    val reaction: String? = null,
    val reply: String? = null,
    /** A frame on another server: "Oma's frame (kinframe.appmire.be)" (README §5.22). */
    val serverName: String? = null,
)

@Serializable
data class Comment(
    val id: String,
    val postId: String,
    val kind: String = CommentKind.PERSON,
    /** Replies only: the top-level comment. */
    val parentId: String? = null,
    val author: PersonRef? = null,
    /** A reply to a reply quotes that person. */
    val quoted: PersonRef? = null,
    val frame: CommentFrame? = null,
    val body: String? = null,
    val mentions: List<Mention> = emptyList(),
    val reactions: ReactionSummary = ReactionSummary.EMPTY,
    val replyCount: Int = 0,
    val createdAt: String = "",
    val editedAt: String? = null,
    /** Deleted by its author but kept because it has replies ("Comment deleted"). */
    val deleted: Boolean = false,
    /** Only for the author: "Removed by a family admin". */
    val removedByAdmin: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
) {
    val isFrameEntry: Boolean get() = kind == CommentKind.FRAME
}

@Serializable
data class CommentPage(val comments: List<Comment> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class CommentCreate(
    val body: String,
    val parentId: String? = null,
    val quotedUserId: String? = null,
    val mentions: List<Mention>? = null,
)

@Serializable
data class CommentUpdate(val body: String, val mentions: List<Mention>? = null)

/** The thread of a post as shown on the timeline. */
@Serializable
data class ThreadSummary(
    val postId: String,
    val state: String = ThreadState.OPEN,
    val commentCount: Int = 0,
    val latestComments: List<Comment> = emptyList(),
    val unreadCount: Int = 0,
    val unreadMentions: Int = 0,
    val readUpTo: String? = null,
    val muted: Boolean = false,
    val reactions: ReactionSummary = ReactionSummary.EMPTY,
    val canComment: Boolean = true,
    val canModerate: Boolean = false,
) {
    val closed: Boolean get() = state == ThreadState.CLOSED
}

/** "Seen on 4 frames". */
@Serializable
data class PostSeen(val frames: Int = 0, val seen: Int = 0)

@Serializable
data class PostPrompt(val id: String, val title: String)

@Serializable
data class TimelinePost(
    val id: String,
    val kind: String = PostKind.FEED,
    val feedId: String? = null,
    val feedName: String? = null,
    val circleId: String? = null,
    val circleName: String? = null,
    val frameId: String? = null,
    val frameName: String? = null,
    val poster: PersonRef,
    val sendId: String? = null,
    val createdAt: String = "",
    val via: ItemVia? = null,
    val items: List<Item> = emptyList(),
    val prompt: PostPrompt? = null,
    val seen: PostSeen? = null,
    val thread: ThreadSummary,
    val canRemove: Boolean = false,
) {
    val isFramePost: Boolean get() = kind == PostKind.FRAME

    /** The first caption of the post's items (the post's caption on the timeline). */
    val caption: String? get() = items.firstOrNull { !it.caption.isNullOrBlank() }?.caption
}

@Serializable
data class TimelinePage(val posts: List<TimelinePost> = emptyList(), val nextBefore: String? = null)

// ---------------------------------------------------------------------------------------------------------- circles

@Serializable
data class CircleMember(
    val userId: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val role: String = CircleRole.MEMBER,
    val addedAt: String? = null,
)

@Serializable
data class CircleFrameRef(val frameId: String, val frameName: String)

/** A smaller group inside a family: its own chat and an audience for posts. */
@Serializable
data class Circle(
    val id: String,
    val feedId: String,
    val name: String,
    /** Null when the caller is a feed owner/admin who is not in the circle. */
    val myRole: String? = null,
    val members: List<CircleMember> = emptyList(),
    val memberCount: Int = 0,
    val frameIds: List<String> = emptyList(),
    val framesReached: List<CircleFrameRef> = emptyList(),
    val conversationId: String? = null,
    val createdAt: String? = null,
) {
    val isMember: Boolean get() = myRole != null
    val isAdmin: Boolean get() = myRole == CircleRole.ADMIN
}

@Serializable
data class CircleCreate(val name: String, val memberIds: List<String>, val frameIds: List<String>? = null)

@Serializable
data class CircleUpdate(val name: String? = null, val frameIds: List<String>? = null)
