package app.kinframe.family.ui.social

import androidx.annotation.StringRes
import app.kinframe.core.api.ApiException
import app.kinframe.core.social.Comment
import app.kinframe.core.social.CommentKind
import app.kinframe.core.social.Message
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.ThreadSummary
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.util.Problems

/** Problem codes of family social (README §5.18–§5.19) on top of the app's usual ones. */
object SocialProblems {
    const val COMMENTS_CLOSED = "comments_closed"
    const val CANNOT_MESSAGE = "cannot_message"
    const val AUDIO_TOO_LONG = "audio_too_long"

    @StringRes
    fun message(e: Throwable): Int = when ((e as? ApiException)?.code) {
        COMMENTS_CLOSED -> R.string.social_problem_comments_closed
        CANNOT_MESSAGE -> R.string.chat_problem_cannot_message
        AUDIO_TOO_LONG -> R.string.chat_problem_audio_too_long
        else -> if ((e as? ApiException)?.status == 501) R.string.social_problem_not_ready else Problems.message(e)
    }
}

/** One top comment and its replies, as the thread shows them. */
data class CommentGroup(val top: Comment, val replies: List<Comment>)

object ThreadLogic {
    /**
     * Nests a flat, oldest-first thread (README §5.18: replies carry `parent_id` and come after their parent). Replies
     * whose parent isn't loaded (yet) hang under nothing and are shown as top-level so nothing is lost.
     */
    fun nest(comments: List<Comment>): List<CommentGroup> {
        val tops = LinkedHashMap<String, MutableList<Comment>>()
        val byId = comments.associateBy { it.id }
        val order = mutableListOf<Comment>()
        comments.forEach { c ->
            val parent = c.parentId?.let { byId[it] }
            if (parent == null) { order += c; tops.getOrPut(c.id) { mutableListOf() } }
            else tops.getOrPut(parent.id) { mutableListOf() } += c
        }
        return order.map { CommentGroup(it, tops[it.id].orEmpty()) }
    }

    /** Adds or replaces [c] in a flat thread, keeping it oldest first (ids are UUIDv7, so they sort by time). */
    fun upsert(comments: List<Comment>, c: Comment): List<Comment> =
        if (comments.any { it.id == c.id }) comments.map { if (it.id == c.id) c else it }
        else (comments + c).sortedBy { it.createdAt + it.id }

    /** A moderator's delete takes the replies too (README §5.18); an author's delete of a top comment with replies leaves a tombstone. */
    fun remove(comments: List<Comment>, id: String): List<Comment> = comments.filterNot { it.id == id || it.parentId == id }

    /** The newest person comment, for `markThreadRead`. */
    fun newestPerson(comments: List<Comment>): Comment? = comments.lastOrNull { it.kind == CommentKind.PERSON }

    /** A new comment as the timeline's summary shows it: count and the two newest. */
    fun withComment(t: ThreadSummary, c: Comment, mine: Boolean): ThreadSummary {
        if (c.kind != CommentKind.PERSON) return t
        if (t.latestComments.any { it.id == c.id }) return t.copy(latestComments = t.latestComments.map { if (it.id == c.id) c else it })
        return t.copy(
            commentCount = t.commentCount + 1,
            latestComments = (t.latestComments + c).takeLast(2),
            unreadCount = if (mine) t.unreadCount else t.unreadCount + 1,
        )
    }
}

/** What the "Reply" button sends: the top comment as parent and, for a reply to a reply, the person quoted. */
data class ReplyTarget(val parentId: String, val quotedUserId: String?, val name: String) {
    companion object {
        fun of(c: Comment, me: String?): ReplyTarget {
            val parent = c.parentId ?: c.id
            val quoted = if (c.parentId != null && c.author?.userId != me) c.author?.userId else null
            return ReplyTarget(parent, quoted, c.author?.displayName.orEmpty())
        }
    }
}

/** Timeline entries: posts, with a header over runs of answers to the same prompt ("6 answers to …"). */
sealed interface HomeEntry {
    data class Post(val post: TimelinePost) : HomeEntry
    data class PromptHeader(val promptId: String, val title: String, val count: Int) : HomeEntry
}

object TimelineLogic {
    /** Groups consecutive answers to the same prompt under one header (README §5.18: the server doesn't reorder). */
    fun entries(posts: List<TimelinePost>): List<HomeEntry> {
        val out = mutableListOf<HomeEntry>()
        var i = 0
        while (i < posts.size) {
            val p = posts[i].prompt
            var j = i + 1
            if (p != null) while (j < posts.size && posts[j].prompt?.id == p.id) j++
            if (p != null && j - i >= 2) out += HomeEntry.PromptHeader(p.id, p.title, j - i)
            for (k in i until j) out += HomeEntry.Post(posts[k])
            i = j
        }
        return out
    }

    fun upsertThread(posts: List<TimelinePost>, postId: String, f: (ThreadSummary) -> ThreadSummary): List<TimelinePost> =
        posts.map { if (it.id == postId) it.copy(thread = f(it.thread)) else it }
}

/** Reactions shown in words: "❤ Oma's frame, Anna and 3 others". */
object ReactionText {
    /** The names in the row, frames first (they are the reward), then people; [others] beyond them. */
    data class Row(val names: List<String>, val others: Int)

    fun row(s: ReactionSummary, frameLabel: (String) -> String, me: String?, youLabel: String): Row {
        val names = s.frames.map { frameLabel(it.frameName) } +
            s.people.map { if (it.person.userId == me) youLabel else it.person.displayName }
        val shown = names.take(2)
        return Row(shown, (s.total - shown.size).coerceAtLeast(0))
    }
}

/** Rules for messages (README §5.19); the server enforces them, the app only hides what won't work. */
object MessageRules {
    fun canEdit(m: Message, me: String?, editHours: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (m.isSystem || m.deleted || m.author?.userId != me || m.voice != null || m.body.isNullOrBlank()) return false
        val sent = runCatching { java.time.Instant.parse(m.createdAt).toEpochMilli() }.getOrNull() ?: return false
        return now - sent < editHours * 3_600_000L
    }

    fun canDelete(m: Message, me: String?, moderator: Boolean): Boolean =
        !m.isSystem && !m.deleted && (m.author?.userId == me || moderator)

    /** First `http(s)` link of a body, for the composer's preview. */
    fun firstLink(text: String): String? = Regex("""https?://[^\s<>"]+""").find(text)?.value?.trimEnd('.', ',', ')', '!', '?')
}
