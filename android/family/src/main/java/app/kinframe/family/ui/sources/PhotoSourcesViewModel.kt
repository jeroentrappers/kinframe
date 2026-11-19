package app.kinframe.family.ui.sources

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceUpdate
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
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

/** Which sources a list shows. */
sealed interface SourceScope {
    /** An album's sources (album managers). */
    data class Album(val frameId: String, val albumId: String) : SourceScope
    /** A feed's sources (feed owner/admins). */
    data class Feed(val feedId: String) : SourceScope
    /** Settings → My photo sources: the sources the caller set up. */
    data object Mine : SourceScope

    fun holds(s: PhotoSource): Boolean = when (this) {
        is Album -> s.target.albumId == albumId
        is Feed -> s.target.feedId == feedId
        Mine -> s.isOwner
    }
}

data class SourcesState(
    val sources: Load<List<PhotoSource>> = Load.Loading,
    /** Sources with a call in flight. */
    val busy: Set<String> = emptySet(),
)

sealed interface SourceNotice {
    data class Message(@StringRes val message: Int, val args: List<String> = emptyList()) : SourceNotice
    data class Error(@StringRes val message: Int) : SourceNotice
}

/** A list of photo sources with their status, kept current by `photo_source.updated` / `photo_source.removed`. */
class PhotoSourcesViewModel(
    private val api: KinApi,
    val scope: SourceScope,
    live: Flow<LiveEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SourceNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<SourceNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.PhotoSourceUpdated -> if (scope.holds(e.source) || has(e.source.id)) upsert(e.source)
                    is LiveEvent.PhotoSourceRemoved -> drop(e.sourceId)
                    else -> Unit
                }
            }
        }
    }

    private fun has(id: String) = (state.value.sources as? Load.Ready)?.value?.any { it.id == id } == true

    fun load() = viewModelScope.launch {
        try {
            val list = when (val s = scope) {
                is SourceScope.Album -> api.albumPhotoSources(s.frameId, s.albumId)
                is SourceScope.Feed -> api.feedPhotoSources(s.feedId)
                SourceScope.Mine -> api.myPhotoSources()
            }
            _state.update { it.copy(sources = Load.Ready(list)) }
        } catch (e: Exception) {
            if (state.value.sources !is Load.Ready) _state.update { it.copy(sources = Load.Error(sourceProblem(e))) }
        }
    }

    /** A new or changed source; new ones go where the server lists them (oldest first, newest first for Mine). */
    fun upsert(s: PhotoSource) = _state.update { st ->
        val list = (st.sources as? Load.Ready)?.value.orEmpty()
        val next = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it }
        else if (scope == SourceScope.Mine) listOf(s) + list else list + s
        st.copy(sources = Load.Ready(next))
    }

    private fun drop(id: String) = _state.update { st ->
        (st.sources as? Load.Ready)?.let { st.copy(sources = Load.Ready(it.value.filterNot { s -> s.id == id })) } ?: st
    }

    private fun run(s: PhotoSource, @StringRes done: Int?, call: suspend () -> PhotoSource?) {
        if (s.id in state.value.busy) return
        _state.update { it.copy(busy = it.busy + s.id) }
        viewModelScope.launch {
            try {
                call()?.let { upsert(it) }
                done?.let { _events.tryEmit(SourceNotice.Message(it, listOf(s.name))) }
            } catch (e: Exception) {
                _events.tryEmit(SourceNotice.Error(sourceProblem(e)))
            } finally {
                _state.update { it.copy(busy = it.busy - s.id) }
            }
        }
    }

    fun syncNow(s: PhotoSource) = run(s, R.string.source_sync_started) { api.syncPhotoSource(s.id) }

    /** "342 photos disappeared. Remove them here too?" → yes. */
    fun confirmRemovals(s: PhotoSource) = run(s, R.string.source_removals_confirmed) { api.syncPhotoSource(s.id, confirmRemovals = true) }

    fun setPaused(s: PhotoSource, paused: Boolean) =
        run(s, if (paused) R.string.source_paused_done else R.string.source_resumed_done) { api.updatePhotoSource(s.id, PhotoSourceUpdate(paused = paused)) }

    /** Become the owner (after `owner_lost_access`); an Immich server source needs a new [apiKey]. */
    fun takeOver(s: PhotoSource, apiKey: String? = null) =
        run(s, R.string.source_taken_over) { api.updatePhotoSource(s.id, PhotoSourceUpdate(takeOver = true, apiKey = apiKey)) }

    /** Settings or new credentials; connection changes are tested by the server and then synced at once. */
    fun update(s: PhotoSource, update: PhotoSourceUpdate) {
        if (update.isEmpty) return
        run(s, R.string.common_saved) { api.updatePhotoSource(s.id, update) }
    }

    /** Stop syncing; [removeItems] also removes the photos it imported. */
    fun delete(s: PhotoSource, removeItems: Boolean) {
        if (s.id in state.value.busy) return
        _state.update { it.copy(busy = it.busy + s.id) }
        viewModelScope.launch {
            try {
                api.deletePhotoSource(s.id, removeItems)
                drop(s.id)
                _events.tryEmit(SourceNotice.Message(if (removeItems) R.string.source_deleted_removed else R.string.source_deleted_kept, listOf(s.name)))
            } catch (e: Exception) {
                _events.tryEmit(SourceNotice.Error(sourceProblem(e)))
            } finally {
                _state.update { it.copy(busy = it.busy - s.id) }
            }
        }
    }
}
