package app.kinframe.family.ui.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.social.Circle
import app.kinframe.core.social.CircleCreate
import app.kinframe.core.social.CircleRole
import app.kinframe.core.social.CircleUpdate
import app.kinframe.core.social.SocialApi
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

data class CirclesState(
    val feed: Feed? = null,
    val circles: Load<List<Circle>> = Load.Loading,
    val members: List<FeedMember> = emptyList(),
    /** Frames with an active subscription: the extra frames a circle can reach. */
    val frames: List<Subscription> = emptyList(),
    val busy: Boolean = false,
) {
    /** Owner/admins, or anyone when the family lets members create circles (README §5.18). */
    val canCreate: Boolean get() = feed?.let { it.isAdmin || it.membersCanCreateCircles } == true

    fun canManage(c: Circle): Boolean = c.isAdmin || feed?.isAdmin == true
}

sealed interface CircleNotice {
    data class Message(@StringRes val message: Int, val arg: String? = null) : CircleNotice
    data class Created(val circle: Circle) : CircleNotice
    data object Deleted : CircleNotice
}

/** A family's circles: list, create, rename, members and roles, extra frames, delete, leave. */
class CirclesViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val feedId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(CirclesState())
    val state: StateFlow<CirclesState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<CircleNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<CircleNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is SocialLive.CircleUpdated -> if (e.feedId == feedId) upsert(e.circle)
                    is SocialLive.CircleRemoved -> if (e.feedId == feedId) list { l -> l.filterNot { it.id == e.circleId } }
                    is LiveEvent.FeedMembershipUpdated -> if (e.feedId == feedId) load()
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        launch { runCatching { kin.feed(feedId) }.onSuccess { f -> _state.update { it.copy(feed = f) } } }
        launch { runCatching { kin.feedMembers(feedId) }.onSuccess { m -> _state.update { it.copy(members = m.sortedBy { x -> x.displayName.lowercase() }) } } }
        launch { runCatching { kin.feedSubscriptions(feedId, SubscriptionStatus.ACTIVE) }.onSuccess { s -> _state.update { it.copy(frames = s) } } }
        try {
            _state.update { it.copy(circles = Load.Ready(api.circles(feedId).sortedBy { c -> c.name.lowercase() })) }
        } catch (e: Exception) {
            if (state.value.circles !is Load.Ready) _state.update { it.copy(circles = Load.Error(SocialProblems.message(e))) }
        }
    }

    private fun list(f: (List<Circle>) -> List<Circle>) = _state.update { s -> (s.circles as? Load.Ready)?.let { s.copy(circles = Load.Ready(f(it.value))) } ?: s }

    private fun upsert(c: Circle) = list { l -> (l.filterNot { it.id == c.id } + c).sortedBy { it.name.lowercase() } }

    fun circle(id: String): Circle? = (state.value.circles as? Load.Ready)?.value?.firstOrNull { it.id == id }

    private fun act(@StringRes done: Int?, arg: String? = null, block: suspend () -> Circle?) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()?.let(::upsert)
            done?.let { _events.tryEmit(CircleNotice.Message(it, arg)) }
        } catch (e: Exception) {
            _events.tryEmit(CircleNotice.Message(SocialProblems.message(e)))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun create(name: String, memberIds: List<String>, frameIds: List<String>) {
        val n = name.trim().take(NAME_MAX)
        if (n.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val c = api.createCircle(feedId, CircleCreate(n, memberIds.filter { it != me() }, frameIds.takeIf { it.isNotEmpty() }), newKey())
                upsert(c)
                _events.tryEmit(CircleNotice.Created(c))
            } catch (e: Exception) {
                _events.tryEmit(CircleNotice.Message(SocialProblems.message(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun rename(c: Circle, name: String) {
        val n = name.trim().take(NAME_MAX)
        if (n.isEmpty() || n == c.name) return
        act(R.string.common_saved) { api.updateCircle(feedId, c.id, CircleUpdate(name = n)) }
    }

    fun setFrames(c: Circle, frameIds: List<String>) = act(R.string.common_saved) { api.updateCircle(feedId, c.id, CircleUpdate(frameIds = frameIds)) }

    fun addMembers(c: Circle, userIds: List<String>) {
        if (userIds.isEmpty()) return
        act(R.string.social_circle_members_added) { api.addCircleMembers(feedId, c.id, userIds) }
    }

    fun removeMember(c: Circle, userId: String) = act(R.string.social_circle_member_removed) {
        api.removeCircleMember(feedId, c.id, userId); api.circle(feedId, c.id)
    }

    fun setAdmin(c: Circle, userId: String, admin: Boolean) =
        act(R.string.common_saved) { api.updateCircleMember(feedId, c.id, userId, if (admin) CircleRole.ADMIN else CircleRole.MEMBER) }

    fun leave(c: Circle) = viewModelScope.launch {
        val id = me() ?: return@launch
        try {
            api.removeCircleMember(feedId, c.id, id)
            if (state.value.feed?.isAdmin == true) runCatching { api.circle(feedId, c.id) }.onSuccess(::upsert) else list { l -> l.filterNot { it.id == c.id } }
            _events.tryEmit(CircleNotice.Message(R.string.social_circle_left, c.name))
            _events.tryEmit(CircleNotice.Deleted)
        } catch (e: Exception) {
            _events.tryEmit(CircleNotice.Message(SocialProblems.message(e)))
        }
    }

    fun delete(c: Circle) = viewModelScope.launch {
        try {
            api.deleteCircle(feedId, c.id)
            list { l -> l.filterNot { it.id == c.id } }
            _events.tryEmit(CircleNotice.Message(R.string.social_circle_deleted, c.name))
            _events.tryEmit(CircleNotice.Deleted)
        } catch (e: Exception) {
            _events.tryEmit(CircleNotice.Message(SocialProblems.message(e)))
        }
    }

    companion object {
        const val NAME_MAX = 40
    }
}
