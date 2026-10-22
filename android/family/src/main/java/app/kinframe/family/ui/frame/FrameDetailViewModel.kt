package app.kinframe.family.ui.frame

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.CommandType
import app.kinframe.core.api.Frame
import app.kinframe.core.api.FrameCommand
import app.kinframe.core.api.FrameHealth
import app.kinframe.core.api.Invite
import app.kinframe.core.api.InviteCreate
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Member
import app.kinframe.core.api.Permissions
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

data class FrameDetailState(
    val frame: Load<Frame> = Load.Loading,
    val health: FrameHealth? = null,
    val members: List<Member> = emptyList(),
    val requests: List<JoinRequest> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val busyCommand: String? = null,
    val creatingInvite: Boolean = false,
    val busy: Boolean = false,
)

/** Messages for the snackbar; the screen turns them into localized text. */
sealed interface FrameEvent {
    data class Command(val type: String) : FrameEvent
    data class Error(@StringRes val message: Int) : FrameEvent
    data class Message(@StringRes val message: Int, val arg: String? = null) : FrameEvent
    data class ShareInvite(val invite: Invite) : FrameEvent
    data object Gone : FrameEvent
}

/** Frame overview and people: status, health, remote control, members, join requests, invites, leave/remove. */
class FrameDetailViewModel(
    private val api: KinApi,
    private val frameId: String,
    private val myUserId: () -> String?,
    live: Flow<LiveEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow(FrameDetailState())
    val state: StateFlow<FrameDetailState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<FrameEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FrameEvent> = _events.asSharedFlow()

    val me: String? get() = myUserId()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.FrameStatus -> if (e.frameId == frameId) _state.update { s ->
                        val f = (s.frame as? Load.Ready)?.value ?: return@update s
                        s.copy(frame = Load.Ready(f.copy(online = e.online, lastSeenAt = e.lastSeenAt ?: f.lastSeenAt)))
                    }
                    is LiveEvent.JoinRequested -> if (e.frameId == frameId) loadPeople()
                    is LiveEvent.MembershipUpdated -> if (e.frameId == frameId) load()
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            val f = api.frame(frameId)
            _state.update { it.copy(frame = Load.Ready(f)) }
            loadPeople()
            if (f.can { it.changeSettings }) runCatching { api.health(frameId) }.onSuccess { h -> _state.update { it.copy(health = h) } }
        } catch (e: Exception) {
            if (state.value.frame !is Load.Ready) _state.update { it.copy(frame = Load.Error(Problems.message(e))) }
        }
    }

    private fun frame(): Frame? = (state.value.frame as? Load.Ready)?.value

    private suspend fun loadPeople() {
        val f = frame() ?: return
        runCatching { api.members(frameId) }.onSuccess { m -> _state.update { it.copy(members = sortMembers(m)) } }
        if (f.isManager) runCatching { api.joinRequests(frameId) }.onSuccess { r -> _state.update { it.copy(requests = r) } }
        if (f.can { it.invite }) runCatching { api.invites(frameId) }.onSuccess { i -> _state.update { it.copy(invites = i) } }
    }

    private fun sortMembers(m: List<Member>) = m.sortedWith(compareBy({ roleRank(it.role) }, { it.displayName.lowercase() }))
    private fun roleRank(r: String) = when (r) { "owner" -> 0; "admin" -> 1; else -> 2 }

    fun command(type: String) {
        if (state.value.busyCommand != null) return
        _state.update { it.copy(busyCommand = type) }
        viewModelScope.launch {
            try {
                val r = api.command(frameId, FrameCommand(type))
                if (r.ok) _events.tryEmit(FrameEvent.Command(type)) else _events.tryEmit(FrameEvent.Error(R.string.cmd_failed))
            } catch (e: Exception) {
                _events.tryEmit(FrameEvent.Error(Problems.message(e)))
            } finally {
                _state.update { it.copy(busyCommand = null) }
            }
        }
    }

    fun rename(name: String, location: String) = mutate {
        val f = api.updateFrame(frameId, buildJsonObject {
            put("name", JsonPrimitive(name.trim()))
            put("location", JsonPrimitive(location.trim()))
        })
        _state.update { it.copy(frame = Load.Ready(f)) }
        _events.tryEmit(FrameEvent.Message(R.string.overview_renamed))
    }

    fun setFrameFlag(key: String, value: Boolean) = mutate {
        val f = api.updateFrame(frameId, buildJsonObject { put(key, JsonPrimitive(value)) })
        _state.update { it.copy(frame = Load.Ready(f)) }
    }

    fun leave(removeMine: Boolean) = mutate {
        api.leaveFrame(frameId, removeMine)
        _events.tryEmit(FrameEvent.Message(R.string.overview_left, frame()?.name))
        _events.tryEmit(FrameEvent.Gone)
    }

    fun remove() = mutate {
        api.removeFrame(frameId)
        _events.tryEmit(FrameEvent.Message(R.string.overview_removed, frame()?.name))
        _events.tryEmit(FrameEvent.Gone)
    }

    fun decide(request: JoinRequest, approve: Boolean) = mutate {
        api.decideJoinRequest(frameId, request.id, approve)
        _state.update { s -> s.copy(requests = s.requests.filterNot { it.id == request.id }) }
        _events.tryEmit(FrameEvent.Message(if (approve) R.string.people_approved else R.string.people_denied, request.user.displayName))
        if (approve) loadPeople()
    }

    fun updateMember(member: Member, role: String?, permissions: Permissions?) = mutate {
        val m = api.updateMember(frameId, member.userId, role, permissions)
        _state.update { s -> s.copy(members = sortMembers(s.members.map { if (it.userId == m.userId) m else it })) }
    }

    fun removeMember(member: Member, removeItems: Boolean) = mutate {
        api.removeMember(frameId, member.userId, removeItems)
        _state.update { s -> s.copy(members = s.members.filterNot { it.userId == member.userId }) }
        _events.tryEmit(FrameEvent.Message(R.string.people_removed, member.displayName))
    }

    fun createInvite(singleUse: Boolean, expiresHours: Int, note: String) {
        if (state.value.creatingInvite) return
        _state.update { it.copy(creatingInvite = true) }
        viewModelScope.launch {
            try {
                val inv = api.createInvite(frameId, InviteCreate(if (singleUse) 1 else null, expiresHours, note.trim().ifEmpty { null }), UUID.randomUUID().toString())
                _state.update { it.copy(invites = listOf(inv) + it.invites) }
                _events.tryEmit(FrameEvent.ShareInvite(inv))
            } catch (e: Exception) {
                _events.tryEmit(FrameEvent.Error(Problems.message(e)))
            } finally {
                _state.update { it.copy(creatingInvite = false) }
            }
        }
    }

    fun revokeInvite(invite: Invite) = mutate {
        api.revokeInvite(frameId, invite.id)
        _state.update { s -> s.copy(invites = s.invites.filterNot { it.id == invite.id }) }
        _events.tryEmit(FrameEvent.Message(R.string.people_revoked))
    }

    private fun mutate(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _events.tryEmit(FrameEvent.Error(Problems.message(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val COMMANDS = listOf(CommandType.PREVIOUS, CommandType.NEXT, CommandType.SLEEP, CommandType.WAKE, CommandType.IDENTIFY, CommandType.SYNC_NOW)
    }
}
