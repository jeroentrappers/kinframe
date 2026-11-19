package app.kinframe.family.ui.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.KinApi
import app.kinframe.core.social.Comment
import app.kinframe.core.social.CommentCreate
import app.kinframe.core.social.CommentUpdate
import app.kinframe.core.social.MentionText
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.ReportCreate
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.ThreadState
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.ui.Load
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PostState(
    val post: Load<TimelinePost> = Load.Loading,
    /** Flat, oldest first (README §5.18); the screen nests them. */
    val comments: List<Comment> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val draft: MentionText = MentionText(""),
    val replyTo: ReplyTarget? = null,
    val editing: Comment? = null,
    val sending: Boolean = false,
    @StringRes val error: Int? = null,
    /** People who may see the post, for @mentions. */
    val people: List<PersonRef> = emptyList(),
) {
    val groups: List<CommentGroup> get() = ThreadLogic.nest(comments)
    val postOrNull: TimelinePost? get() = (post as? Load.Ready)?.value
}

sealed interface PostNotice {
    data class Message(@StringRes val message: Int) : PostNotice
    data object Gone : PostNotice
}

/** One post and its thread: comments, replies, reactions, mentions, edit and delete, close and mute. */
class PostViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val postId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val maxChars: Int = 2000,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PostNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<PostNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch { live.collect(::onLive) }
    }

    fun load() = viewModelScope.launch {
        try {
            val p = api.post(postId)
            _state.update { it.copy(post = Load.Ready(p)) }
            val page = api.comments(postId)
            _state.update { it.copy(comments = page.comments, hasMore = page.hasMore) }
            markRead()
            launch { loadPeople(p) }
        } catch (e: Exception) {
            if (state.value.post !is Load.Ready) _state.update { it.copy(post = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun loadMore() {
        val s = state.value
        if (!s.hasMore || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.comments(postId, after = s.comments.lastOrNull()?.id)
                _state.update { st -> st.copy(comments = page.comments.fold(st.comments) { acc, c -> ThreadLogic.upsert(acc, c) }, hasMore = page.hasMore, loadingMore = false) }
                markRead()
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
                _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    /** People who can see the post (the mention picker): the circle, the family, or the frame's library viewers. */
    private suspend fun loadPeople(p: TimelinePost) {
        val people = runCatching {
            when {
                p.circleId != null && p.feedId != null -> api.circle(p.feedId!!, p.circleId!!).members.map { PersonRef(it.userId, it.displayName, it.avatarUrl) }
                p.feedId != null -> kin.feedMembers(p.feedId!!).map { PersonRef(it.userId, it.displayName, it.avatarUrl) }
                p.frameId != null -> kin.members(p.frameId!!).filter { it.role == app.kinframe.core.api.Role.OWNER || it.role == app.kinframe.core.api.Role.ADMIN || it.permissions.seeLibrary }.map { PersonRef(it.userId, it.displayName, it.avatarUrl) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        _state.update { it.copy(people = people.filter { r -> r.userId != me() }.sortedBy { r -> r.displayName.lowercase() }) }
    }

    private fun markRead() {
        val newest = ThreadLogic.newestPerson(state.value.comments) ?: return
        val t = state.value.postOrNull?.thread
        if (t != null && t.readUpTo != null && t.readUpTo!! >= newest.id) return
        viewModelScope.launch {
            runCatching { api.markThreadRead(postId, newest.id) }.onSuccess {
                updateThread { it.copy(readUpTo = newest.id, unreadCount = 0, unreadMentions = 0) }
            }
        }
    }

    private fun updateThread(f: (app.kinframe.core.social.ThreadSummary) -> app.kinframe.core.social.ThreadSummary) = _state.update { s ->
        val p = s.postOrNull ?: return@update s
        s.copy(post = Load.Ready(p.copy(thread = f(p.thread))))
    }

    fun onDraft(text: String) = _state.update { it.copy(draft = it.draft.edited(text), error = null) }

    fun setDraft(m: MentionText) = _state.update { it.copy(draft = m, error = null) }

    fun reply(c: Comment) = _state.update { it.copy(replyTo = ReplyTarget.of(c, me()), editing = null) }

    fun edit(c: Comment) = _state.update { it.copy(editing = c, replyTo = null, draft = MentionText(c.body.orEmpty(), c.mentions)) }

    fun cancel() = _state.update { it.copy(editing = null, replyTo = null, draft = if (it.editing != null) MentionText("") else it.draft) }

    fun send() {
        val s = state.value
        val draft = s.draft.trimmed()
        if (draft.text.isEmpty() || s.sending) return
        if (MentionText.codePoints(draft.text) > maxChars) { _state.update { it.copy(error = R.string.social_comment_too_long) }; return }
        _state.update { it.copy(sending = true, error = null) }
        val editing = s.editing
        val reply = s.replyTo
        viewModelScope.launch {
            try {
                val c = if (editing != null) api.updateComment(postId, editing.id, CommentUpdate(draft.text, draft.mentions))
                else api.createComment(postId, CommentCreate(draft.text, reply?.parentId, reply?.quotedUserId, draft.mentions.takeIf { it.isNotEmpty() }), newKey())
                _state.update { st ->
                    st.copy(comments = ThreadLogic.upsert(st.comments, c), sending = false, draft = MentionText(""), editing = null, replyTo = null)
                }
                if (editing == null) updateThread { ThreadLogic.withComment(it, c, mine = true) }
                markRead()
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, error = SocialProblems.message(e)) }
            }
        }
    }

    fun delete(c: Comment) = viewModelScope.launch {
        val before = state.value.comments
        val mineWithReplies = c.author?.userId == me() && c.parentId == null && before.any { it.parentId == c.id }
        _state.update { s ->
            s.copy(comments = if (mineWithReplies) s.comments.map { if (it.id == c.id) it.copy(deleted = true, body = null) else it } else ThreadLogic.remove(s.comments, c.id))
        }
        try {
            api.deleteComment(postId, c.id)
            _events.tryEmit(PostNotice.Message(if (c.author?.userId == me()) R.string.social_comment_deleted else R.string.social_comment_removed))
        } catch (e: Exception) {
            _state.update { it.copy(comments = before) }
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
        }
    }

    /** React to the post, one of its items ([itemId]) or a comment; null removes the caller's reaction. */
    fun react(targetKind: String, targetId: String, emoji: String?) = viewModelScope.launch {
        val itemPost = if (targetKind == ReactionTarget.POST_ITEM) postId else null
        when (targetKind) {
            ReactionTarget.POST -> updateThread { it.copy(reactions = it.reactions.withMine(emoji)) }
            ReactionTarget.COMMENT -> _state.update { s -> s.copy(comments = s.comments.map { if (it.id == targetId) it.copy(reactions = it.reactions.withMine(emoji)) else it }) }
        }
        try {
            val summary = api.setReaction(targetKind, targetId, emoji, itemPost)
            applyReaction(targetKind, targetId, summary)
        } catch (e: Exception) {
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
            load()
        }
    }

    private fun applyReaction(kind: String, id: String, summary: app.kinframe.core.social.ReactionSummary) {
        when (kind) {
            ReactionTarget.POST -> if (id == postId) updateThread { it.copy(reactions = summary) }
            ReactionTarget.COMMENT -> _state.update { s -> s.copy(comments = s.comments.map { if (it.id == id) it.copy(reactions = summary) else it }) }
        }
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch {
        updateThread { it.copy(muted = muted) }
        try {
            api.setThreadMute(postId, muted)
            _events.tryEmit(PostNotice.Message(if (muted) R.string.social_thread_muted else R.string.social_thread_unmuted))
        } catch (e: Exception) {
            updateThread { it.copy(muted = !muted) }
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
        }
    }

    fun setClosed(closed: Boolean) = viewModelScope.launch {
        try {
            val t = api.setThreadState(postId, if (closed) ThreadState.CLOSED else ThreadState.OPEN)
            updateThread { t }
            _events.tryEmit(PostNotice.Message(if (closed) R.string.social_thread_closed_done else R.string.social_thread_opened_done))
        } catch (e: Exception) {
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
        }
    }

    fun report(targetKind: String, targetId: String, reason: String, note: String?) = viewModelScope.launch {
        try {
            api.report(ReportCreate(targetKind, targetId, reason, note?.takeIf { it.isNotBlank() }))
            _events.tryEmit(PostNotice.Message(R.string.chat_reported))
        } catch (e: Exception) {
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
        }
    }

    fun block(userId: String) = viewModelScope.launch {
        try {
            api.block(userId)
            _state.update { s -> s.copy(comments = s.comments.filterNot { it.author?.userId == userId }) }
            _events.tryEmit(PostNotice.Message(R.string.chat_blocked))
        } catch (e: Exception) {
            _events.tryEmit(PostNotice.Message(SocialProblems.message(e)))
        }
    }

    private fun onLive(e: LiveEvent) {
        when (e) {
            is SocialLive.CommentCreated -> if (e.postId == postId) {
                val known = state.value.comments.any { it.id == e.comment.id }
                _state.update { it.copy(comments = ThreadLogic.upsert(it.comments, e.comment)) }
                if (!known) updateThread { ThreadLogic.withComment(it, e.comment, mine = e.comment.author?.userId == me()) }
                markRead()
            }
            is SocialLive.CommentUpdated -> if (e.postId == postId) _state.update { it.copy(comments = ThreadLogic.upsert(it.comments, e.comment)) }
            is SocialLive.CommentDeleted -> if (e.postId == postId) _state.update { it.copy(comments = ThreadLogic.remove(it.comments, e.commentId)) }
            is SocialLive.PostUpdated -> if (e.postId == postId) e.thread?.let { t -> updateThread { t } }
            is SocialLive.PostRemoved -> if (e.postId == postId) _events.tryEmit(PostNotice.Gone)
            is SocialLive.ReactionUpdated -> if (e.postId == postId || e.targetId == postId) applyReaction(e.targetKind, e.targetId, e.summary)
            is SocialLive.ThreadRead -> if (e.postId == postId) updateThread { it.copy(readUpTo = e.readUpTo, unreadCount = 0, unreadMentions = 0) }
            else -> Unit
        }
    }
}
