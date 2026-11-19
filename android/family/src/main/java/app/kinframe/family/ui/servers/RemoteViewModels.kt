package app.kinframe.family.ui.servers

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInviteCreate
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.servers.BlockedServer
import app.kinframe.core.servers.BlockedServerCreate
import app.kinframe.core.servers.RemoteApi
import app.kinframe.core.servers.RemoteInvitePreview
import app.kinframe.core.servers.RemoteOnEnd
import app.kinframe.core.servers.RemoteSubscription
import app.kinframe.core.servers.RemoteSubscriptionCreate
import app.kinframe.core.servers.RemoteSubscriptionUpdate
import app.kinframe.family.R
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ServersNotice {
    data class Message(@StringRes val message: Int, val args: List<Any> = emptyList()) : ServersNotice
    data class ShareInvite(val invite: FeedInvite) : ServersNotice
}

private fun problem(e: Throwable): Int = when (Problems.code(e)) {
    "already_subscribed" -> R.string.servers_problem_already
    "invite_invalid", "invite_expired" -> R.string.servers_problem_invite
    "upstream_unavailable" -> R.string.servers_problem_unreachable
    "forbidden" -> R.string.servers_problem_forbidden
    else -> Problems.message(e)
}

/**
 * A frame's families on other servers (multi-server.md §3.1, B's side): "Add a family from another server" (invite
 * URL → preview → confirm with history), the list with status, on-end choice and unsubscribe.
 */
data class RemoteFollowsState(
    val follows: Load<List<RemoteSubscription>> = Load.Loading,
    val inviteUrl: String = "",
    val previewing: Boolean = false,
    val preview: RemoteInvitePreview? = null,
    val history: String = SubscriptionHistory.NONE,
    val historyCount: Int = 50,
    val onEnd: String = RemoteOnEnd.REMOVE,
    val busy: Boolean = false,
)

class RemoteFollowsViewModel(
    private val api: RemoteApi,
    private val frameId: String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteFollowsState())
    val state: StateFlow<RemoteFollowsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ServersNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<ServersNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(follows = Load.Ready(api.remoteSubscriptions(frameId).sortedByDescending { s -> s.createdAt })) }
        } catch (e: Exception) {
            _state.update { it.copy(follows = Load.Error(problem(e))) }
        }
    }

    fun onInviteUrl(v: String) = _state.update { it.copy(inviteUrl = v.trim().take(2000), preview = null) }

    /** Opened from an invite link of another server: preview at once. */
    fun start(url: String) { onInviteUrl(url); preview() }

    fun preview() {
        val url = state.value.inviteUrl
        if (url.isBlank() || state.value.previewing) return
        _state.update { it.copy(previewing = true) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(preview = api.previewInvite(url)) }
            } catch (e: Exception) {
                _events.emit(ServersNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(previewing = false) }
            }
        }
    }

    fun cancelPreview() = _state.update { it.copy(preview = null) }
    fun setHistory(h: String) = _state.update { it.copy(history = h) }
    fun setHistoryCount(n: Int) = _state.update { it.copy(historyCount = n.coerceIn(1, 1000)) }
    fun setOnEnd(v: String) = _state.update { it.copy(onEnd = v) }

    fun confirm() {
        val s = state.value
        val p = s.preview ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val created = api.createRemoteSubscription(frameId, RemoteSubscriptionCreate(
                    s.inviteUrl, s.history, s.historyCount.takeIf { s.history == SubscriptionHistory.LAST_N }, onEnd = s.onEnd,
                ), newKey())
                _events.emit(ServersNotice.Message(
                    if (created.status == "pending") R.string.servers_follow_pending else R.string.servers_follow_active, listOf(p.feedName),
                ))
                _state.update { it.copy(preview = null, inviteUrl = "") }
                load()
            } catch (e: Exception) {
                _events.emit(ServersNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun setFollowOnEnd(f: RemoteSubscription, onEnd: String) = act {
        val u = api.updateRemoteSubscription(frameId, f.id, RemoteSubscriptionUpdate(onEnd = onEnd))
        replace(u)
    }

    fun unsubscribe(f: RemoteSubscription, keep: Boolean) = act {
        api.deleteRemoteSubscription(frameId, f.id, if (keep) RemoteOnEnd.KEEP else RemoteOnEnd.REMOVE)
        _events.emit(ServersNotice.Message(R.string.servers_follow_removed, listOf(f.feedName)))
        load()
    }

    /** `remote_subscription.updated` on the websocket. */
    fun replace(f: RemoteSubscription) = _state.update { s ->
        if (f.frameId != frameId) return@update s
        val list = (s.follows as? Load.Ready)?.value.orEmpty().filterNot { it.id == f.id } + f
        s.copy(follows = Load.Ready(list.sortedByDescending { it.createdAt }))
    }

    private fun act(block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { _events.emit(ServersNotice.Message(problem(e))) } finally { _state.update { it.copy(busy = false) } }
        }
    }
}

/**
 * A family's frames on other servers (multi-server.md §3.4, A's side): "Allow frames on other servers", an invite
 * link frames on other servers may use, and blocked servers.
 */
data class FeedRemoteState(
    val allow: Boolean = false,
    val blocked: Load<List<BlockedServer>> = Load.Loading,
    val blockHost: String = "",
    val busy: Boolean = false,
)

class FeedRemoteViewModel(
    private val api: RemoteApi,
    private val feedId: String,
    allow: Boolean,
    private val onFeed: (Feed) -> Unit = {},
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(FeedRemoteState(allow = allow))
    val state: StateFlow<FeedRemoteState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ServersNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<ServersNotice> = _events.asSharedFlow()

    init { loadBlocked() }

    fun loadBlocked() = viewModelScope.launch {
        try {
            _state.update { it.copy(blocked = Load.Ready(api.blockedServers(feedId))) }
        } catch (e: Exception) {
            _state.update { it.copy(blocked = Load.Error(problem(e))) }
        }
    }

    fun setAllow(on: Boolean) = act {
        val f = api.setAllowRemoteFrames(feedId, on)
        _state.update { it.copy(allow = f.allowRemoteFrames) }
        onFeed(f)
    }

    /** A `subscribe` invite with "Frames on other servers may use this link". */
    fun createInvite() = act {
        val inv = api.createFeedInvite(feedId, FeedInviteCreate(FeedInvitePurpose.SUBSCRIBE, allowRemote = true), newKey())
        _events.emit(ServersNotice.ShareInvite(inv))
    }

    fun onBlockHost(v: String) = _state.update { it.copy(blockHost = v.trim().take(253)) }

    fun block() {
        val h = state.value.blockHost
        if (h.isBlank()) return
        act {
            val req = if (h.startsWith("http")) BlockedServerCreate(serverUrl = h) else BlockedServerCreate(host = h)
            val b = api.blockServer(feedId, req)
            _state.update { s -> s.copy(blockHost = "", blocked = Load.Ready((s.blocked as? Load.Ready)?.value.orEmpty() + b)) }
            _events.emit(ServersNotice.Message(R.string.servers_blocked_done, listOf(b.serverName ?: b.host)))
        }
    }

    fun unblock(b: BlockedServer) = act {
        api.unblockServer(feedId, b.id)
        _state.update { s -> s.copy(blocked = Load.Ready((s.blocked as? Load.Ready)?.value.orEmpty().filterNot { it.id == b.id })) }
    }

    private fun act(block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { _events.emit(ServersNotice.Message(problem(e))) } finally { _state.update { it.copy(busy = false) } }
        }
    }
}
