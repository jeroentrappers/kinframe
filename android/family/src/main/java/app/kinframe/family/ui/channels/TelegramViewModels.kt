package app.kinframe.family.ui.channels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.TelegramChat
import app.kinframe.core.api.TelegramChatKind
import app.kinframe.core.api.TelegramChatUpdate
import app.kinframe.core.api.TelegramCode
import app.kinframe.core.api.TelegramCodeStatus
import app.kinframe.core.api.TelegramConnectCodeCreate
import app.kinframe.core.api.TelegramContributor
import app.kinframe.core.api.TelegramLink
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** The three ways to connect Telegram to a frame or feed (README §5.17). */
enum class ConnectKind {
    /** "Connect a Telegram group". */
    Group,
    /** "Send from my Telegram": the caller's own private chat with the bot, linking their account. */
    Mine,
    /** "Invite someone to send by Telegram": a private-chat link to share with a relative. */
    Invite;

    val request: TelegramConnectCodeCreate
        get() = when (this) {
            Group -> TelegramConnectCodeCreate(TelegramChatKind.GROUP)
            Mine -> TelegramConnectCodeCreate(TelegramChatKind.PRIVATE, linkAccount = true)
            Invite -> TelegramConnectCodeCreate(TelegramChatKind.PRIVATE)
        }
}

/** The connect sheet: a code being created, waiting to be used in Telegram, or done. */
data class ConnectState(
    val kind: ConnectKind,
    val creating: Boolean = true,
    val code: TelegramCode? = null,
    /** The link from the create response (later reads of the code don't carry it). */
    val url: String? = null,
    /** The chat the code created, once used. */
    val chat: TelegramChat? = null,
    @StringRes val error: Int? = null,
) {
    val waiting: Boolean get() = code?.status == TelegramCodeStatus.OPEN && chat == null
    val connected: Boolean get() = chat != null || code?.status == TelegramCodeStatus.USED
}

data class TelegramChatsState(
    val chats: Load<List<TelegramChat>> = Load.Loading,
    val connect: ConnectState? = null,
    val busy: Set<String> = emptySet(),
)

/** A frame's or feed's Telegram chats and the connect flow, kept current by `telegram_chat.*` / `telegram_code.updated`. */
class TelegramChatsViewModel(
    private val api: KinApi,
    val scope: ChannelScope,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    /** How often the open code is re-read while the sheet waits (the websocket usually tells first). */
    private val pollMs: Long = 4_000,
) : ViewModel() {
    private val _state = MutableStateFlow(TelegramChatsState())
    val state: StateFlow<TelegramChatsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ChannelNotice> = _events.asSharedFlow()
    private var poll: Job? = null

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.TelegramChatUpdated -> if (scope.holds(e.chat.target)) upsert(e.chat)
                    is LiveEvent.TelegramChatRemoved -> drop(e.chatId)
                    is LiveEvent.TelegramCodeUpdated -> onCode(e.code)
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            val list = if (scope.isFeed) api.feedTelegramChats(scope.id) else api.frameTelegramChats(scope.id)
            _state.update { it.copy(chats = Load.Ready(list)) }
            state.value.connect?.code?.chatId?.let { id -> list.firstOrNull { it.id == id }?.let { c -> _state.update { st -> st.copy(connect = st.connect?.copy(chat = c)) } } }
        } catch (e: Exception) {
            if (state.value.chats !is Load.Ready) _state.update { it.copy(chats = Load.Error(telegramProblem(e))) }
        }
    }

    /** Live chats first (as the server lists them), then disconnected ones. */
    fun upsert(c: TelegramChat) = _state.update { st ->
        val list = (st.chats as? Load.Ready)?.value.orEmpty()
        val next = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else listOf(c) + list
        val connect = st.connect?.let { k -> if (k.code?.chatId == c.id) k.copy(chat = c) else k }
        st.copy(chats = Load.Ready(next.sortedBy { if (it.isLive) 0 else 1 }), connect = connect)
    }

    private fun drop(id: String) = _state.update { st ->
        (st.chats as? Load.Ready)?.let { st.copy(chats = Load.Ready(it.value.filterNot { c -> c.id == id })) } ?: st
    }

    /** Creates a code; a group or "my Telegram" opens Telegram at once, an invite link is shared. */
    fun connect(kind: ConnectKind) {
        if (state.value.connect?.creating == true) return
        poll?.cancel()
        _state.update { it.copy(connect = ConnectState(kind)) }
        viewModelScope.launch {
            try {
                val code = if (scope.isFeed) api.createFeedTelegramCode(scope.id, kind.request, newKey()) else api.createFrameTelegramCode(scope.id, kind.request, newKey())
                _state.update { it.copy(connect = ConnectState(kind, creating = false, code = code, url = code.url)) }
                code.url?.let { url -> _events.tryEmit(if (kind == ConnectKind.Invite) ChannelNotice.ShareTelegram(url) else ChannelNotice.OpenTelegram(url)) }
                follow(code.id)
            } catch (e: Exception) {
                _state.update { it.copy(connect = ConnectState(kind, creating = false, error = telegramProblem(e))) }
            }
        }
    }

    /** Opens the link again (the person came back without finishing). */
    fun reopen() {
        val c = state.value.connect ?: return
        c.url?.let { _events.tryEmit(if (c.kind == ConnectKind.Invite) ChannelNotice.ShareTelegram(it) else ChannelNotice.OpenTelegram(it)) }
    }

    private fun follow(codeId: String) {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(pollMs)
                val c = state.value.connect?.code ?: break
                if (c.id != codeId || c.status != TelegramCodeStatus.OPEN) break
                runCatching { api.telegramCode(codeId) }.onSuccess { onCode(it) }
            }
        }
    }

    private fun onCode(code: TelegramCode) {
        val k = state.value.connect ?: return
        if (k.code?.id != code.id) return
        // Later reads don't carry the link; keep the one from the create response.
        _state.update { it.copy(connect = k.copy(code = code, error = TelegramLogic.codeError(code.errorCode).takeIf { code.status != TelegramCodeStatus.USED })) }
        when (code.status) {
            TelegramCodeStatus.USED -> {
                poll?.cancel()
                val chat = (state.value.chats as? Load.Ready)?.value?.firstOrNull { it.id == code.chatId }
                if (chat != null) _state.update { it.copy(connect = it.connect?.copy(chat = chat)) }
                else code.chatId?.let { id ->
                    viewModelScope.launch { runCatching { api.telegramChat(id) }.onSuccess { upsert(it) } }
                }
            }
            TelegramCodeStatus.EXPIRED, TelegramCodeStatus.REVOKED -> {
                poll?.cancel()
                if (k.error == null) _state.update { it.copy(connect = it.connect?.copy(error = R.string.tg_code_expired)) }
            }
            else -> Unit
        }
    }

    /** Closes the sheet; an unused code is revoked so the link stops working. */
    fun closeConnect() {
        poll?.cancel()
        val code = state.value.connect?.code
        _state.update { it.copy(connect = null) }
        if (code != null && code.status == TelegramCodeStatus.OPEN) viewModelScope.launch { runCatching { api.revokeTelegramCode(code.id) } }
    }

    fun setPaused(c: TelegramChat, paused: Boolean) {
        if (c.id in state.value.busy) return
        _state.update { it.copy(busy = it.busy + c.id) }
        viewModelScope.launch {
            try {
                upsert(api.updateTelegramChat(c.id, TelegramChatUpdate(paused = paused)))
                _events.tryEmit(ChannelNotice.Message(if (paused) R.string.tg_paused_done else R.string.tg_resumed_done, listOf(c.title)))
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(telegramProblem(e)))
            } finally {
                _state.update { it.copy(busy = it.busy - c.id) }
            }
        }
    }

    override fun onCleared() {
        poll?.cancel()
    }
}

// ------------------------------------------------------------------------------------------------------ one chat

data class TelegramChatState(
    val chat: Load<TelegramChat> = Load.Loading,
    val contributors: Load<List<TelegramContributor>> = Load.Loading,
    val busy: Set<String> = emptySet(),
) {
    val chatOrNull: TelegramChat? get() = (chat as? Load.Ready)?.value
}

/** One Telegram chat: status, settings, contributors, disconnect. */
class TelegramChatViewModel(
    private val api: KinApi,
    val chatId: String,
    live: Flow<LiveEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow(TelegramChatState())
    val state: StateFlow<TelegramChatState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ChannelNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.TelegramChatUpdated -> if (e.chat.id == chatId) _state.update { it.copy(chat = Load.Ready(e.chat)) }
                    is LiveEvent.TelegramChatRemoved -> if (e.chatId == chatId) _events.tryEmit(ChannelNotice.Gone)
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(chat = Load.Ready(api.telegramChat(chatId))) }
        } catch (e: Exception) {
            if (e is ApiException && e.status == 404) _events.tryEmit(ChannelNotice.Gone)
            else if (state.value.chat !is Load.Ready) _state.update { it.copy(chat = Load.Error(telegramProblem(e))) }
        }
        runCatching { api.telegramContributors(chatId) }
            .onSuccess { v -> _state.update { it.copy(contributors = Load.Ready(v)) } }
            .onFailure { e -> if (state.value.contributors !is Load.Ready) _state.update { it.copy(contributors = Load.Error(telegramProblem(e))) } }
    }

    private fun <T> run(key: String, @StringRes done: Int?, args: List<String> = emptyList(), call: suspend () -> T, onDone: (T) -> Unit) {
        if (key in state.value.busy) return
        _state.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                onDone(call())
                done?.let { _events.tryEmit(ChannelNotice.Message(it, args)) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(telegramProblem(e)))
            } finally {
                _state.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private val title: String get() = state.value.chatOrNull?.title.orEmpty()

    fun update(update: TelegramChatUpdate, @StringRes done: Int? = R.string.common_saved) {
        if (update.isEmpty) return
        run(chatId, done, listOf(title), call = { api.updateTelegramChat(chatId, update) }, onDone = { c -> _state.update { it.copy(chat = Load.Ready(c)) } })
    }

    fun setPaused(paused: Boolean) = update(TelegramChatUpdate(paused = paused), if (paused) R.string.tg_paused_done else R.string.tg_resumed_done)

    fun takeOver() = update(TelegramChatUpdate(takeOver = true), R.string.tg_taken_over)

    /** Forum groups: only [topicId] counts; null = every topic. */
    fun setTopic(topicId: Long?) = update(if (topicId == null) TelegramChatUpdate(allTopics = true) else TelegramChatUpdate(topicId = topicId))

    /** The bot says goodbye and leaves; photos already sent stay. */
    fun disconnect() = run(chatId, R.string.tg_disconnected_done, listOf(title), call = { api.disconnectTelegramChat(chatId) }, onDone = {
        viewModelScope.launch { runCatching { api.telegramChat(chatId) }.onSuccess { c -> _state.update { it.copy(chat = Load.Ready(c)) } } }
    })

    fun setBlocked(p: TelegramContributor, blocked: Boolean) =
        run(p.id, if (blocked) R.string.tg_contributor_blocked else R.string.tg_contributor_unblocked, listOf(p.displayName),
            call = { api.updateTelegramContributor(chatId, p.id, blocked) }, onDone = { u ->
                _state.update { st -> (st.contributors as? Load.Ready)?.let { st.copy(contributors = Load.Ready(it.value.map { x -> if (x.id == u.id) u else x })) } ?: st }
            })
}

// ------------------------------------------------------------------------------------------------------ my account

data class TelegramLinkState(
    val link: Load<TelegramLink?> = Load.Loading,
    /** A link code waiting to be used in Telegram. */
    val code: TelegramCode? = null,
    val url: String? = null,
    val busy: Boolean = false,
)

/** Settings → "Link my Telegram" / unlink, kept current by `telegram_link.updated`. */
class TelegramLinkViewModel(
    private val api: KinApi,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(TelegramLinkState())
    val state: StateFlow<TelegramLinkState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ChannelNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.TelegramLinkUpdated -> {
                        val wasWaiting = state.value.code != null
                        _state.update { it.copy(link = Load.Ready(e.link), code = if (e.link != null) null else it.code) }
                        if (e.link != null && wasWaiting) _events.tryEmit(ChannelNotice.Message(R.string.tg_linked_done, listOf(e.link.displayName)))
                    }
                    is LiveEvent.TelegramCodeUpdated -> if (e.code.id == state.value.code?.id) {
                        if (e.code.status == TelegramCodeStatus.USED) { _state.update { it.copy(code = null) }; load() }
                        else TelegramLogic.codeError(e.code.errorCode)?.let { _events.tryEmit(ChannelNotice.Error(it)) }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Also on returning to the app from Telegram. */
    fun load() = viewModelScope.launch {
        try {
            val l = api.telegramLink()
            _state.update { it.copy(link = Load.Ready(l), code = if (l != null) null else it.code) }
        } catch (e: Exception) {
            if (state.value.link !is Load.Ready) _state.update { it.copy(link = Load.Error(telegramProblem(e))) }
        }
    }

    fun link() {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val c = api.createTelegramLinkCode(newKey())
                _state.update { it.copy(code = c, url = c.url) }
                c.url?.let { _events.tryEmit(ChannelNotice.OpenTelegram(it)) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(telegramProblem(e)))
                if ((e as? ApiException)?.status == 409) load()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun unlink() {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                api.unlinkTelegram()
                _state.update { it.copy(link = Load.Ready(null)) }
                _events.tryEmit(ChannelNotice.Message(R.string.tg_unlinked_done))
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(telegramProblem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
