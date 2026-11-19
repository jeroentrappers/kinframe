package app.kinframe.family.ui.chats

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.ConversationCreate
import app.kinframe.core.social.ConversationKind
import app.kinframe.core.social.Person
import app.kinframe.core.social.SocialApi
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.social.SocialProblems
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

data class ChatsState(
    val conversations: Load<List<Conversation>> = Load.Loading,
    val showArchived: Boolean = false,
    val archived: List<Conversation> = emptyList(),
    val contacts: Load<List<Person>> = Load.Loading,
    val creating: Boolean = false,
)

sealed interface ChatsNotice {
    data class Message(@StringRes val message: Int) : ChatsNotice
    data class Open(val conversationId: String) : ChatsNotice
}

object ChatList {
    /** By latest activity, newest first (README §5.19: `last_activity_at`). */
    fun sorted(list: List<Conversation>): List<Conversation> = list.sortedByDescending { it.lastActivityAt }

    fun upsert(list: List<Conversation>, c: Conversation): List<Conversation> = sorted(list.filterNot { it.id == c.id } + c)
}

/** The Chats tab: conversations by latest message with unread counts, archive, and starting a chat or a group. */
class ChatsViewModel(
    private val api: SocialApi,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    /** The conversation on screen: its new messages don't count as unread here. */
    private val open: () -> String? = { null },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    /** Whether a user id is the person (any of their accounts: Chats merges every server's conversations). */
    private val isMe: (String?) -> Boolean = { it != null && it == me() },
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsState())
    val state: StateFlow<ChatsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChatsNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatsNotice> = _events.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch { live.collect(::onLive) }
    }

    fun refresh() = viewModelScope.launch {
        try {
            _state.update { it.copy(conversations = Load.Ready(ChatList.sorted(api.conversations()))) }
        } catch (e: Exception) {
            if (state.value.conversations !is Load.Ready) _state.update { it.copy(conversations = Load.Error(SocialProblems.message(e))) }
        }
        if (state.value.showArchived) loadArchived()
    }

    fun toggleArchived() {
        _state.update { it.copy(showArchived = !it.showArchived) }
        if (state.value.showArchived) viewModelScope.launch { loadArchived() }
    }

    private suspend fun loadArchived() {
        runCatching { api.conversations(archived = true) }.onSuccess { l -> _state.update { it.copy(archived = ChatList.sorted(l.filter { c -> c.archived })) } }
    }

    fun loadContacts() = viewModelScope.launch {
        try {
            _state.update { it.copy(contacts = Load.Ready(api.contacts().filter { p -> !p.blocked && !isMe(p.person.userId) }.sortedBy { p -> p.person.displayName.lowercase() })) }
        } catch (e: Exception) {
            _state.update { it.copy(contacts = Load.Error(SocialProblems.message(e))) }
        }
    }

    /** A direct chat (one person) is idempotent per pair; a group needs a name and at least two others. */
    fun start(memberIds: List<String>, groupName: String?) {
        if (memberIds.isEmpty() || state.value.creating) return
        val group = groupName != null
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val c = api.createConversation(
                    ConversationCreate(if (group) ConversationKind.GROUP else ConversationKind.DIRECT, memberIds, groupName?.trim()?.take(NAME_MAX)),
                    newKey(),
                )
                _state.update { s -> s.copy(creating = false, conversations = Load.Ready(ChatList.upsert((s.conversations as? Load.Ready)?.value.orEmpty(), c))) }
                _events.tryEmit(ChatsNotice.Open(c.id))
            } catch (e: Exception) {
                _state.update { it.copy(creating = false) }
                _events.tryEmit(ChatsNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    fun archive(c: Conversation, archived: Boolean) = viewModelScope.launch {
        try {
            val updated = api.updateConversationPreferences(c.id, archived = archived)
            _state.update { s ->
                val list = (s.conversations as? Load.Ready)?.value.orEmpty()
                s.copy(
                    conversations = Load.Ready(if (archived) list.filterNot { it.id == c.id } else ChatList.upsert(list, updated)),
                    archived = if (archived) ChatList.upsert(s.archived, updated) else s.archived.filterNot { it.id == c.id },
                )
            }
            _events.tryEmit(ChatsNotice.Message(if (archived) R.string.chat_archived_done else R.string.chat_unarchived_done))
        } catch (e: Exception) {
            _events.tryEmit(ChatsNotice.Message(SocialProblems.message(e)))
        }
    }

    private fun list(f: (List<Conversation>) -> List<Conversation>) = _state.update { s ->
        (s.conversations as? Load.Ready)?.let { s.copy(conversations = Load.Ready(f(it.value))) } ?: s
    }

    private fun onLive(e: LiveEvent) {
        when (e) {
            is SocialLive.ConversationUpdated -> list { l -> if (e.conversation.archived) l.filterNot { it.id == e.conversation.id } else ChatList.upsert(l, e.conversation) }
            is SocialLive.ConversationRemoved -> list { l -> l.filterNot { it.id == e.conversationId } }
            is SocialLive.MessageCreated -> {
                val known = (state.value.conversations as? Load.Ready)?.value?.firstOrNull { it.id == e.conversationId }
                if (known == null) { refresh(); return }
                val mine = isMe(e.message.author?.userId)
                val counts = !mine && !e.message.isSystem && open() != e.conversationId
                list { l ->
                    ChatList.upsert(l, known.copy(
                        lastMessage = e.message, lastActivityAt = e.message.createdAt.ifBlank { known.lastActivityAt },
                        unreadCount = if (counts) known.unreadCount + 1 else if (mine || open() == e.conversationId) 0 else known.unreadCount,
                        archived = false,
                    ))
                }
            }
            is SocialLive.MessageUpdated -> list { l -> l.map { if (it.id == e.conversationId && it.lastMessage?.id == e.message.id) it.copy(lastMessage = e.message) else it } }
            is SocialLive.ReadUpdated -> if (isMe(e.userId)) list { l -> l.map { if (it.id == e.conversationId) it.copy(unreadCount = 0, unreadMentions = 0, readUpTo = e.lastReadMessageId) else it } }
            // Family and circle chats follow the family's and circle's members (README §5.18).
            is LiveEvent.FeedMembershipUpdated, is SocialLive.CircleUpdated, is SocialLive.CircleRemoved -> refresh()
            else -> Unit
        }
    }

    companion object {
        const val NAME_MAX = 60
    }
}
