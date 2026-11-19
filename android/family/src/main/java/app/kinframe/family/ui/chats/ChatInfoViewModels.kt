package app.kinframe.family.ui.chats

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.ConversationCreate
import app.kinframe.core.social.ConversationKind
import app.kinframe.core.social.ConversationRole
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

data class ConversationInfoState(
    val conversation: Load<Conversation> = Load.Loading,
    val contacts: List<Person> = emptyList(),
    val busy: Boolean = false,
)

sealed interface InfoNotice {
    data class Message(@StringRes val message: Int) : InfoNotice
    /** Left the group: back to the list. */
    data object Left : InfoNotice
}

/** A conversation's details: members, and for groups name, photo, add, remove, make admin, leave. */
class ConversationInfoViewModel(
    private val api: SocialApi,
    private val conversationId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val readPhoto: suspend (android.net.Uri) -> ByteArray? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationInfoState())
    val state: StateFlow<ConversationInfoState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<InfoNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<InfoNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                if (e is SocialLive.ConversationUpdated && e.conversation.id == conversationId) _state.update { it.copy(conversation = Load.Ready(e.conversation)) }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(conversation = Load.Ready(api.conversation(conversationId))) }
        } catch (e: Exception) {
            _state.update { it.copy(conversation = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun loadContacts() = viewModelScope.launch {
        runCatching { api.contacts() }.onSuccess { l -> _state.update { it.copy(contacts = l.filter { p -> !p.blocked }.sortedBy { p -> p.person.displayName.lowercase() }) } }
    }

    private fun act(@StringRes done: Int?, block: suspend () -> Conversation?) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()?.let { c -> _state.update { it.copy(conversation = Load.Ready(c)) } }
            done?.let { _events.tryEmit(InfoNotice.Message(it)) }
        } catch (e: Exception) {
            _events.tryEmit(InfoNotice.Message(SocialProblems.message(e)))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun rename(name: String) {
        val n = name.trim().take(ChatsViewModel.NAME_MAX)
        if (n.isEmpty()) return
        act(R.string.common_saved) { api.renameConversation(conversationId, n) }
    }

    fun setPhoto(uri: android.net.Uri) = act(R.string.common_saved) {
        val bytes = readPhoto(uri) ?: throw java.io.IOException("unreadable")
        api.putConversationPhoto(conversationId, bytes, "image/jpeg")
    }

    fun removePhoto() = act(R.string.common_saved) { api.deleteConversationPhoto(conversationId); api.conversation(conversationId) }

    fun add(userIds: List<String>) = act(R.string.chat_members_added) { api.addConversationMembers(conversationId, userIds) }

    fun remove(userId: String) = act(R.string.chat_member_removed) { api.removeConversationMember(conversationId, userId); api.conversation(conversationId) }

    fun makeAdmin(userId: String, admin: Boolean) =
        act(R.string.common_saved) { api.updateConversationMember(conversationId, userId, if (admin) ConversationRole.ADMIN else ConversationRole.MEMBER) }

    fun leave() = viewModelScope.launch {
        val id = me() ?: return@launch
        try {
            api.removeConversationMember(conversationId, id)
            _events.tryEmit(InfoNotice.Message(R.string.chat_left))
            _events.tryEmit(InfoNotice.Left)
        } catch (e: Exception) {
            _events.tryEmit(InfoNotice.Message(SocialProblems.message(e)))
        }
    }

    fun mute(value: String) = act(null) { api.updateConversationPreferences(conversationId, mute = value) }
}

data class PersonState(val person: Load<Person> = Load.Loading, val busy: Boolean = false)

sealed interface PersonNotice {
    data class Message(@StringRes val message: Int) : PersonNotice
    data class OpenChat(val conversationId: String) : PersonNotice
}

/** A contact's profile: shared families and frames, start a chat, block, shown milestones. */
class PersonViewModel(
    private val api: SocialApi,
    private val userId: String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PersonNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<PersonNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(person = Load.Ready(api.person(userId))) }
        } catch (e: Exception) {
            _state.update { it.copy(person = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun chat() = viewModelScope.launch {
        val p = (state.value.person as? Load.Ready)?.value
        p?.directConversationId?.let { _events.tryEmit(PersonNotice.OpenChat(it)); return@launch }
        _state.update { it.copy(busy = true) }
        try {
            val c = api.createConversation(ConversationCreate(ConversationKind.DIRECT, listOf(userId)), newKey())
            _events.tryEmit(PersonNotice.OpenChat(c.id))
        } catch (e: Exception) {
            _events.tryEmit(PersonNotice.Message(SocialProblems.message(e)))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun setBlocked(blocked: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            if (blocked) api.block(userId) else api.unblock(userId)
            _state.update { s -> (s.person as? Load.Ready)?.let { s.copy(person = Load.Ready(it.value.copy(blocked = blocked))) } ?: s }
            _events.tryEmit(PersonNotice.Message(if (blocked) R.string.chat_blocked else R.string.chat_unblocked))
        } catch (e: Exception) {
            _events.tryEmit(PersonNotice.Message(SocialProblems.message(e)))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
