package app.kinframe.family.ui.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Send
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.history.Delivery
import app.kinframe.family.history.Stage
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class HistoryFilter(val query: String) { All("all"), Ongoing("ongoing"), Delivered("delivered") }

data class HistoryState(
    val filter: HistoryFilter = HistoryFilter.All,
    val sends: Load<List<Send>> = Load.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
)

/** The sender's history across devices, newest first, kept live by `send.updated` events (README §6.2). */
class HistoryViewModel(private val api: KinApi, live: Flow<LiveEvent>, sent: Flow<String>) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.SendUpdated -> upsert(e.send)
                    is LiveEvent.Reaction -> mapSends { Delivery.applyReaction(it, e.placementId, e.reaction) }
                    is LiveEvent.Reply -> mapSends { Delivery.applyReply(it, e.placementId, e.preset) }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { sent.collect { refresh() } }
    }

    private fun mapSends(f: (Send) -> Send?) = _state.update { s ->
        val list = (s.sends as? Load.Ready)?.value ?: return@update s
        s.copy(sends = Load.Ready(list.map { f(it) ?: it }))
    }

    private fun upsert(send: Send) = _state.update { s ->
        val list = (s.sends as? Load.Ready)?.value ?: return@update s
        val matches = when (s.filter) {
            HistoryFilter.All -> true
            HistoryFilter.Delivered -> Delivery.sendStage(send).rank >= Stage.Delivered.rank
            HistoryFilter.Ongoing -> Delivery.sendStage(send).rank < Stage.Delivered.rank
        }
        val updated = if (list.any { it.id == send.id }) list.mapNotNull { if (it.id == send.id) send.takeIf { matches } else it }
        else if (matches) listOf(send) + list else list
        s.copy(sends = Load.Ready(updated.sortedByDescending { it.createdAt }))
    }

    fun setFilter(f: HistoryFilter) {
        if (f == state.value.filter) return
        _state.update { HistoryState(filter = f) }
        load()
    }

    private fun load() = viewModelScope.launch {
        val f = state.value.filter
        try {
            val page = api.sends(state = f.query)
            if (state.value.filter == f) _state.update { it.copy(sends = Load.Ready(page.sends), nextCursor = page.nextCursor) }
        } catch (e: Exception) {
            if (state.value.sends !is Load.Ready) _state.update { it.copy(sends = Load.Error(Problems.message(e))) }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(refreshing = true, sends = if (it.sends is Load.Error) Load.Loading else it.sends) }
        try {
            val page = api.sends(state = state.value.filter.query)
            _state.update { it.copy(sends = Load.Ready(page.sends), nextCursor = page.nextCursor) }
        } catch (e: Exception) {
            if (state.value.sends !is Load.Ready) _state.update { it.copy(sends = Load.Error(Problems.message(e))) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun loadMore() {
        val s = state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.sends(cursor = cursor, state = s.filter.query)
                _state.update { st ->
                    val list = (st.sends as? Load.Ready)?.value.orEmpty()
                    st.copy(sends = Load.Ready(list + page.sends.filterNot { n -> list.any { it.id == n.id } }), nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (_: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** For a reaction push without a send id: the send holding [placementId], from what is loaded. */
    fun sendWithPlacement(placementId: String): String? =
        (state.value.sends as? Load.Ready)?.value?.firstOrNull { s -> s.frames.any { f -> f.placements.any { it.placementId == placementId } } }?.id

    fun removeLocally(sendId: String) = _state.update { s ->
        val list = (s.sends as? Load.Ready)?.value ?: return@update s
        s.copy(sends = Load.Ready(list.filterNot { it.id == sendId }))
    }
}

data class SendDetailState(
    val send: Load<Send> = Load.Loading,
    val editingItem: String? = null,
    val captionDraft: String = "",
    val saving: Boolean = false,
    val unsending: Boolean = false,
    val unsent: Boolean = false,
    @StringRes val error: Int? = null,
)

sealed interface SendDetailEvent {
    data object CaptionSaved : SendDetailEvent
    data object Unsent : SendDetailEvent
}

/** One send: per-frame delivery, reactions and replies, caption editing and unsend. */
class SendDetailViewModel(private val api: KinApi, private val sendId: String, live: Flow<LiveEvent>) : ViewModel() {
    private val _state = MutableStateFlow(SendDetailState())
    val state: StateFlow<SendDetailState> = _state.asStateFlow()
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<SendDetailEvent>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<SendDetailEvent> = _events

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                val cur = (state.value.send as? Load.Ready)?.value ?: return@collect
                val next = when (e) {
                    is LiveEvent.SendUpdated -> e.send.takeIf { it.id == sendId }
                    is LiveEvent.Reaction -> Delivery.applyReaction(cur, e.placementId, e.reaction)
                    is LiveEvent.Reply -> Delivery.applyReply(cur, e.placementId, e.preset)
                    else -> null
                }
                if (next != null) _state.update { it.copy(send = Load.Ready(next)) }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(send = Load.Ready(api.send(sendId))) }
        } catch (e: Exception) {
            if (state.value.send !is Load.Ready) _state.update { it.copy(send = Load.Error(Problems.message(e))) }
        }
    }

    fun startEdit(itemId: String) {
        val item = (state.value.send as? Load.Ready)?.value?.items?.firstOrNull { it.id == itemId } ?: return
        _state.update { it.copy(editingItem = itemId, captionDraft = item.caption.orEmpty(), error = null) }
    }

    fun onCaption(v: String) = _state.update { it.copy(captionDraft = v.take(280)) }

    fun cancelEdit() = _state.update { it.copy(editingItem = null) }

    fun saveCaption() {
        val s = state.value
        val id = s.editingItem ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val text = s.captionDraft.trim()
                val item = api.updateItem(id, buildJsonObject { put("caption", if (text.isEmpty()) JsonNull else JsonPrimitive(text)) })
                _state.update { st ->
                    val send = (st.send as? Load.Ready)?.value
                    st.copy(
                        saving = false, editingItem = null,
                        send = send?.let { Load.Ready(it.copy(items = it.items.map { i -> if (i.id == id) item else i })) } ?: st.send,
                    )
                }
                _events.tryEmit(SendDetailEvent.CaptionSaved)
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = Problems.message(e)) }
            }
        }
    }

    fun unsend() {
        if (state.value.unsending) return
        _state.update { it.copy(unsending = true, error = null) }
        viewModelScope.launch {
            try {
                api.unsend(sendId)
                _state.update { it.copy(unsending = false, unsent = true) }
                _events.tryEmit(SendDetailEvent.Unsent)
            } catch (e: Exception) {
                _state.update { it.copy(unsending = false, error = Problems.message(e)) }
            }
        }
    }
}
