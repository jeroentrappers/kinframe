package app.kinframe.family.ui.frames

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FramesState(val frames: Load<List<Frame>> = Load.Loading, val refreshing: Boolean = false) {
    val online: Int get() = (frames as? Load.Ready)?.value?.count { it.online } ?: 0
}

/** The Frames tab: everything the person is a member of, with live online state. */
class FramesViewModel(private val api: KinApi, live: Flow<LiveEvent>) : ViewModel() {
    private val _state = MutableStateFlow(FramesState())
    val state: StateFlow<FramesState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.FrameStatus -> _state.update { s ->
                        val list = (s.frames as? Load.Ready)?.value ?: return@update s
                        s.copy(frames = Load.Ready(list.map { if (it.id == e.frameId) it.copy(online = e.online, lastSeenAt = e.lastSeenAt ?: it.lastSeenAt) else it }))
                    }
                    is LiveEvent.MembershipUpdated -> refresh()
                    else -> Unit
                }
            }
        }
    }

    private fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(frames = Load.Ready(api.frames().sortedWith(order))) }
        } catch (e: Exception) {
            if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, frames = if (it.frames is Load.Error) Load.Loading else it.frames) }
            try {
                _state.update { it.copy(frames = Load.Ready(api.frames().sortedWith(order))) }
            } catch (e: Exception) {
                if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** A new or renamed frame from another screen. */
    fun upsert(frame: Frame) = _state.update { s ->
        val list = (s.frames as? Load.Ready)?.value.orEmpty()
        s.copy(frames = Load.Ready((list.filterNot { it.id == frame.id } + frame).sortedWith(order)))
    }

    fun remove(frameId: String) = _state.update { s ->
        val list = (s.frames as? Load.Ready)?.value ?: return@update s
        s.copy(frames = Load.Ready(list.filterNot { it.id == frameId }))
    }

    private companion object {
        val order = compareBy<Frame>({ it.name.lowercase() }, { it.id })
    }
}
