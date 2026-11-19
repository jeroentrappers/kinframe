package app.kinframe.family.ui.chats

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.social.Circle
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.LinkPreview
import app.kinframe.core.social.Mention
import app.kinframe.core.social.MentionText
import app.kinframe.core.social.Message
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.MessageUpdate
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.PutOnFrameRequest
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.ReportCreate
import app.kinframe.core.social.ReportTargetKind
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.TypingActivity
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.social.MessageRules
import app.kinframe.family.ui.social.SocialProblems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID

/** Puts photos, videos and recordings of a chat message through the outbox (`SocialGraph`). */
interface ChatMedia {
    suspend fun queueMedia(conversationId: String, name: String, uris: List<Uri>, body: String?, mentions: List<Mention>, replyToId: String?): String?
    suspend fun queueVoice(conversationId: String, name: String, file: File, durationMs: Long, replyToId: String?): String
}

/** A text message on its way (shown at once, replaced by the server's when it arrives). */
data class PendingText(val clientId: String, val body: String, val mentions: List<Mention>, val replyTo: Message?, val failed: Boolean = false)

/** Someone typing or recording right now. */
data class TypingPerson(val userId: String, val name: String, val activity: String, val until: Long)

/** Where "Put on a frame" can go: frames the caller may send to, families they may post to, their circles. */
data class PutOnFrameTargets(val frames: List<Frame> = emptyList(), val feeds: List<Feed> = emptyList(), val circles: Map<String, List<Circle>> = emptyMap())

data class ConversationState(
    val conversation: Load<Conversation> = Load.Loading,
    /** Oldest first. */
    val messages: List<Message> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val loadingOlder: Boolean = false,
    val draft: MentionText = MentionText(""),
    val replyTo: Message? = null,
    val editing: Message? = null,
    val pending: List<PendingText> = emptyList(),
    /** Photo, video and voice messages still uploading (the outbox). */
    val uploads: List<OutboxSend> = emptyList(),
    val typing: List<TypingPerson> = emptyList(),
    val preview: LinkPreview? = null,
    val targets: PutOnFrameTargets? = null,
    @StringRes val error: Int? = null,
) {
    val conversationOrNull: Conversation? get() = (conversation as? Load.Ready)?.value

    val people: List<PersonRef> get() = conversationOrNull?.members?.map { it.person }.orEmpty()
}

sealed interface ConversationNotice {
    data class Message(@StringRes val message: Int, val arg: String? = null) : ConversationNotice
    data object Gone : ConversationNotice
}

object ReadReceipts {
    /**
     * Who has seen [message] (README §5.19): members other than the author whose read position is at or after it.
     * Only where the conversation shows receipts; ids are UUIDv7, so they compare in time order.
     */
    fun seenBy(c: Conversation, message: Message, me: String?): List<PersonRef> {
        if (!c.readReceipts) return emptyList()
        return c.members.filter { m -> m.person.userId != me && m.person.userId != message.author?.userId && (m.lastReadMessageId ?: "") >= message.id }.map { it.person }
    }
}

/** One conversation: messages oldest first, sending (text now, media through the outbox), receipts and typing. */
class ConversationViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val conversationId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val media: ChatMedia,
    uploads: Flow<List<OutboxSend>> = emptyFlow(),
    private val maxChars: Int = 4000,
    private val linkPreviews: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ConversationNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ConversationNotice> = _events.asSharedFlow()

    private var lastTyping = 0L
    private var previewJob: Job? = null
    private var previewUrl: String? = null
    private val typingJobs = mutableMapOf<String, Job>()

    init {
        load()
        viewModelScope.launch { live.collect(::onLive) }
        viewModelScope.launch { uploads.collect { l -> _state.update { it.copy(uploads = l.filter { s -> s.chat?.conversationId == conversationId }) } } }
    }

    fun load() = viewModelScope.launch {
        try {
            val c = api.conversation(conversationId)
            _state.update { it.copy(conversation = Load.Ready(c)) }
            val page = api.messages(conversationId)
            _state.update { it.copy(messages = page.messages, hasMoreBefore = page.hasMoreBefore) }
            markRead()
        } catch (e: Exception) {
            if (state.value.conversation !is Load.Ready) _state.update { it.copy(conversation = Load.Error(SocialProblems.message(e))) }
        }
    }

    /** After a reconnect or coming back: what arrived meanwhile (`after` = the newest we have). */
    fun catchUp() = viewModelScope.launch {
        val last = state.value.messages.lastOrNull()?.id ?: run { load(); return@launch }
        runCatching { api.messages(conversationId, after = last, limit = 200) }.onSuccess { page ->
            page.messages.forEach { upsert(it) }
            markRead()
        }
        runCatching { api.conversation(conversationId) }.onSuccess { c -> _state.update { it.copy(conversation = Load.Ready(c)) } }
    }

    fun loadOlder() {
        val s = state.value
        val first = s.messages.firstOrNull()?.id ?: return
        if (!s.hasMoreBefore || s.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val page = api.messages(conversationId, before = first)
                _state.update { st -> st.copy(messages = page.messages.filter { m -> st.messages.none { it.id == m.id } } + st.messages, hasMoreBefore = page.hasMoreBefore, loadingOlder = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false) }
                _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    private fun markRead() {
        val c = state.value.conversationOrNull ?: return
        val newest = state.value.messages.lastOrNull { it.author?.userId != me() } ?: return
        if ((c.readUpTo ?: "") >= newest.id && c.unreadCount == 0) return
        viewModelScope.launch {
            runCatching { api.markConversationRead(conversationId, newest.id) }.onSuccess {
                _state.update { s -> s.conversationOrNull?.let { s.copy(conversation = Load.Ready(it.copy(readUpTo = newest.id, unreadCount = 0, unreadMentions = 0))) } ?: s }
            }
        }
    }

    private fun upsert(m: Message) = _state.update { s ->
        val list = if (s.messages.any { it.id == m.id }) s.messages.map { if (it.id == m.id) m else it }
        else (s.messages + m).sortedBy { it.id }
        s.copy(messages = list, pending = s.pending.filterNot { it.clientId == m.clientId && m.clientId != null })
    }

    // ------------------------------------------------------------------ composing

    fun onDraft(text: String) {
        _state.update { it.copy(draft = it.draft.edited(text), error = null) }
        if (text.isNotBlank() && now() - lastTyping > TYPING_EVERY_MS && state.value.editing == null) {
            lastTyping = now()
            viewModelScope.launch { runCatching { api.sendTyping(conversationId, TypingActivity.TYPING) } }
        }
        if (linkPreviews) updatePreview(text)
    }

    fun setDraft(m: MentionText) = _state.update { it.copy(draft = m, error = null) }

    /** Recording a voice message counts as "recording…" for the others. */
    fun recording() {
        if (now() - lastTyping > TYPING_EVERY_MS) {
            lastTyping = now()
            viewModelScope.launch { runCatching { api.sendTyping(conversationId, TypingActivity.RECORDING) } }
        }
    }

    private fun updatePreview(text: String) {
        val url = MessageRules.firstLink(text)
        if (url == previewUrl) return
        previewUrl = url
        previewJob?.cancel()
        _state.update { it.copy(preview = null) }
        if (url == null) return
        previewJob = viewModelScope.launch {
            delay(600)
            runCatching { api.linkPreview(url) }.onSuccess { p -> if (previewUrl == url) _state.update { it.copy(preview = p) } }
        }
    }

    fun dismissPreview() {
        previewJob?.cancel()
        _state.update { it.copy(preview = null) }
    }

    fun reply(m: Message) = _state.update { it.copy(replyTo = m, editing = null) }

    fun edit(m: Message) = _state.update { it.copy(editing = m, replyTo = null, draft = MentionText(m.body.orEmpty(), m.mentions)) }

    fun cancel() = _state.update { it.copy(replyTo = null, editing = null, draft = if (it.editing != null) MentionText("") else it.draft) }

    fun send() {
        val s = state.value
        val draft = s.draft.trimmed()
        if (draft.text.isEmpty()) return
        if (MentionText.codePoints(draft.text) > maxChars) { _state.update { it.copy(error = R.string.chat_message_too_long) }; return }
        s.editing?.let { m -> saveEdit(m, draft); return }
        val clientId = newKey()
        val pending = PendingText(clientId, draft.text, draft.mentions, s.replyTo)
        val withPreview = s.preview != null || !linkPreviews
        _state.update { it.copy(draft = MentionText(""), replyTo = null, pending = it.pending + pending, preview = null) }
        previewUrl = null
        post(pending, linkPreview = if (linkPreviews && !withPreview) false else null)
    }

    private fun post(p: PendingText, linkPreview: Boolean? = null) = viewModelScope.launch {
        try {
            val m = api.sendMessage(conversationId, MessageCreate(
                clientId = p.clientId, body = p.body, mentions = p.mentions.takeIf { it.isNotEmpty() }, replyToId = p.replyTo?.id, linkPreview = linkPreview,
            ), idempotencyKey = p.clientId)
            upsert(m)
        } catch (e: Exception) {
            _state.update { s -> s.copy(pending = s.pending.map { if (it.clientId == p.clientId) it.copy(failed = true) else it }) }
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun retry(clientId: String) {
        val p = state.value.pending.firstOrNull { it.clientId == clientId } ?: return
        _state.update { s -> s.copy(pending = s.pending.map { if (it.clientId == clientId) it.copy(failed = false) else it }) }
        post(p)
    }

    fun discard(clientId: String) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.clientId == clientId }) }

    private fun saveEdit(m: Message, draft: MentionText) = viewModelScope.launch {
        _state.update { it.copy(editing = null, draft = MentionText("")) }
        try {
            upsert(api.updateMessage(conversationId, m.id, MessageUpdate(draft.text, draft.mentions)))
        } catch (e: Exception) {
            _state.update { it.copy(editing = m, draft = draft) }
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    /** Photos and videos (with the draft as their text) go through the outbox. */
    fun sendMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val c = state.value.conversationOrNull ?: return
        val draft = state.value.draft.trimmed()
        val reply = state.value.replyTo
        _state.update { it.copy(draft = MentionText(""), replyTo = null) }
        viewModelScope.launch {
            try {
                val id = media.queueMedia(conversationId, c.name, uris.take(MAX_ITEMS), draft.text.ifEmpty { null }, draft.mentions, reply?.id)
                if (id == null) _events.tryEmit(ConversationNotice.Message(R.string.chat_media_unreadable))
            } catch (e: Exception) {
                _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    fun sendVoice(file: File, durationMs: Long) {
        val c = state.value.conversationOrNull ?: return
        val reply = state.value.replyTo
        _state.update { it.copy(replyTo = null) }
        viewModelScope.launch {
            try {
                media.queueVoice(conversationId, c.name, file, durationMs, reply?.id)
            } catch (e: Exception) {
                _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    // ------------------------------------------------------------------ message actions

    fun delete(m: Message) = viewModelScope.launch {
        try {
            api.deleteMessage(conversationId, m.id)
            upsert(m.copy(deleted = true, body = null, items = emptyList(), voice = null, linkPreview = null, removedByAdmin = m.author?.userId != me()))
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun react(m: Message, emoji: String?) = viewModelScope.launch {
        upsert(m.copy(reactions = m.reactions.withMine(emoji)))
        try {
            val summary = api.setReaction(ReactionTarget.MESSAGE, m.id, emoji)
            _state.update { s -> s.copy(messages = s.messages.map { if (it.id == m.id) it.copy(reactions = summary) else it }) }
        } catch (e: Exception) {
            upsert(m)
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun loadTargets() = viewModelScope.launch {
        val frames = runCatching { kin.frames() }.getOrDefault(emptyList()).filter { it.can { p -> p.send } }.sortedBy { it.name.lowercase() }
        val feeds = runCatching { kin.feeds() }.getOrDefault(emptyList()).filter { it.canPost }.sortedBy { it.name.lowercase() }
        val circles = feeds.filter { it.circleCount > 0 }.associate { f -> f.id to runCatching { api.circles(f.id) }.getOrDefault(emptyList()).filter { it.isMember } }
        _state.update { it.copy(targets = PutOnFrameTargets(frames, feeds, circles)) }
    }

    /** "Put on a frame": a normal send of the message's photos and videos without uploading again. */
    fun putOnFrame(m: Message, frameIds: List<String>, feedIds: List<String>, circleIds: List<String>, names: List<String>) = viewModelScope.launch {
        if (frameIds.isEmpty() && feedIds.isEmpty()) return@launch
        try {
            api.putMessageOnFrame(conversationId, m.id, PutOnFrameRequest(frameIds, feedIds.takeIf { it.isNotEmpty() }, circleIds.takeIf { it.isNotEmpty() }), newKey())
            _events.tryEmit(ConversationNotice.Message(R.string.chat_put_on_frame_done, names.joinToString(", ")))
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun report(m: Message, reason: String, note: String?) = viewModelScope.launch {
        try {
            api.report(ReportCreate(ReportTargetKind.MESSAGE, m.id, reason, note?.takeIf { it.isNotBlank() }))
            _events.tryEmit(ConversationNotice.Message(R.string.chat_reported))
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun block(userId: String) = viewModelScope.launch {
        try {
            api.block(userId)
            _events.tryEmit(ConversationNotice.Message(R.string.chat_blocked))
            load()
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    // ------------------------------------------------------------------ the conversation

    fun mute(value: String) = viewModelScope.launch {
        try {
            val c = api.updateConversationPreferences(conversationId, mute = value)
            _state.update { it.copy(conversation = Load.Ready(c)) }
            _events.tryEmit(ConversationNotice.Message(if (c.muted) R.string.chat_muted_done else R.string.chat_unmuted_done))
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    fun archive() = viewModelScope.launch {
        try {
            api.updateConversationPreferences(conversationId, archived = true)
            _events.tryEmit(ConversationNotice.Message(R.string.chat_archived_done))
            _events.tryEmit(ConversationNotice.Gone)
        } catch (e: Exception) {
            _events.tryEmit(ConversationNotice.Message(SocialProblems.message(e)))
        }
    }

    // ------------------------------------------------------------------ live

    private fun onLive(e: LiveEvent) {
        when (e) {
            is SocialLive.MessageCreated -> if (e.conversationId == conversationId) {
                upsert(e.message)
                e.message.author?.userId?.let { a -> _state.update { s -> s.copy(typing = s.typing.filterNot { it.userId == a }) } }
                markRead()
            }
            is SocialLive.MessageUpdated -> if (e.conversationId == conversationId) upsert(e.message)
            is SocialLive.ReactionUpdated -> if (e.conversationId == conversationId && e.targetKind == ReactionTarget.MESSAGE) {
                _state.update { s -> s.copy(messages = s.messages.map { if (it.id == e.targetId) it.copy(reactions = e.summary) else it }) }
            }
            is SocialLive.ReadUpdated -> if (e.conversationId == conversationId) _state.update { s ->
                val c = s.conversationOrNull ?: return@update s
                val members = c.members.map { if (it.person.userId == e.userId) it.copy(lastReadMessageId = e.lastReadMessageId) else it }
                val mine = e.userId == me()
                s.copy(conversation = Load.Ready(c.copy(members = members, readUpTo = if (mine) e.lastReadMessageId else c.readUpTo, unreadCount = if (mine) 0 else c.unreadCount)))
            }
            is SocialLive.Typing -> if (e.conversationId == conversationId && e.userId != me()) onTyping(e)
            is SocialLive.ConversationUpdated -> if (e.conversation.id == conversationId) _state.update { it.copy(conversation = Load.Ready(e.conversation)) }
            is SocialLive.ConversationRemoved -> if (e.conversationId == conversationId) _events.tryEmit(ConversationNotice.Gone)
            else -> Unit
        }
    }

    private fun onTyping(e: SocialLive.Typing) {
        val until = e.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (now() + TYPING_SHOWN_MS)
        // The phone's clock may differ from the server's: never show it longer than the server's 6 s.
        val shownUntil = minOf(until, now() + TYPING_SHOWN_MS).coerceAtLeast(now() + 1000)
        _state.update { s -> s.copy(typing = s.typing.filterNot { it.userId == e.userId } + TypingPerson(e.userId, e.displayName, e.activity, shownUntil)) }
        typingJobs[e.userId]?.cancel()
        typingJobs[e.userId] = viewModelScope.launch {
            delay((shownUntil - now()).coerceAtLeast(0))
            _state.update { s -> s.copy(typing = s.typing.filterNot { it.userId == e.userId && it.until <= shownUntil }) }
        }
    }

    companion object {
        const val TYPING_EVERY_MS = 3000L
        const val TYPING_SHOWN_MS = 6000L
        const val MAX_ITEMS = 30
    }
}
