package app.kinframe.family.ui.share

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.BulkAction
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementStatus
import app.kinframe.core.api.Send
import app.kinframe.core.api.ShareSkip
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

/** Photos on one frame to share to others: the frame they are on and their placements there. */
data class ShareSource(val frameId: String, val placementIds: List<String>)

/** How much of the selection a frame already has. */
enum class Coverage { None, Some, All }

data class ShareTarget(val frame: Frame, val coverage: Coverage)

data class ShareState(
    val source: ShareSource? = null,
    val targets: Load<List<ShareTarget>> = Load.Loading,
    val chosen: Set<String> = emptySet(),
    val busy: Boolean = false,
) {
    val open: Boolean get() = source != null
}

sealed interface ShareEvent {
    /** Shared to [frames] (names, in list order); [undo] removes what was created. */
    data class Shared(val frames: List<String>, val count: Int, val undo: () -> Unit) : ShareEvent
    data object NothingNew : ShareEvent
    data object Undone : ShareEvent
    data class Error(@StringRes val message: Int) : ShareEvent
}

/** Pure parts of sharing, unit-tested in ShareTest. */
object Sharing {
    /** Per frame, how much of a [selectionSize]-item selection it already holds, from a dry run's skips. */
    fun coverage(skipped: List<ShareSkip>, frameIds: List<String>, selectionSize: Int): Map<String, Coverage> {
        val have = skipped.groupBy { it.frameId }.mapValues { (_, v) -> v.map { it.placementId }.toSet().size }
        return frameIds.associateWith { id ->
            val n = have[id] ?: 0
            when {
                n == 0 -> Coverage.None
                n >= selectionSize -> Coverage.All
                else -> Coverage.Some
            }
        }
    }

    /** Frames you can send to, except the source. */
    fun targets(frames: List<Frame>, sourceFrameId: String): List<Frame> =
        frames.filter { it.id != sourceFrameId && it.can { p -> p.send } }

    /** The first frame of a send that still holds every one of [itemIds], with those placements. */
    fun source(send: Send, itemIds: List<String>): ShareSource? {
        if (itemIds.isEmpty()) return null
        for (f in send.frames) {
            val ids = itemIds.map { item ->
                f.placements.firstOrNull { it.itemId == item && it.status != PlacementStatus.REMOVED && it.status != PlacementStatus.FAILED }?.placementId
            }
            if (ids.all { it != null }) return ShareSource(f.frameId, ids.filterNotNull())
        }
        return null
    }
}

/**
 * "Share to another frame" (README §5.3): pick frames you can send to (a dry run marks the ones that already have
 * the photos), share without uploading again, and undo by purging exactly the placements that were created.
 */
class ShareViewModel(private val api: KinApi) : ViewModel() {
    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShareEvent> = _events.asSharedFlow()

    fun open(source: ShareSource) {
        if (source.placementIds.isEmpty()) return
        _state.value = ShareState(source = source)
        viewModelScope.launch {
            try {
                val targets = Sharing.targets(api.frames(), source.frameId).sortedBy { it.name.lowercase() }
                val coverage = if (targets.isEmpty()) emptyMap() else runCatching {
                    Sharing.coverage(api.sharePlacements(source.frameId, source.placementIds, targets.map { it.id }, dryRun = true).skipped,
                        targets.map { it.id }, source.placementIds.size)
                }.getOrDefault(emptyMap())
                if (state.value.source == source) {
                    _state.update { it.copy(targets = Load.Ready(targets.map { f -> ShareTarget(f, coverage[f.id] ?: Coverage.None) })) }
                }
            } catch (e: Exception) {
                if (state.value.source == source) _state.update { it.copy(targets = Load.Error(Problems.message(e))) }
            }
        }
    }

    fun toggle(frameId: String) = _state.update { s ->
        val t = (s.targets as? Load.Ready)?.value?.firstOrNull { it.frame.id == frameId }
        if (t == null || t.coverage == Coverage.All) s
        else s.copy(chosen = if (frameId in s.chosen) s.chosen - frameId else s.chosen + frameId)
    }

    fun dismiss() { _state.value = ShareState() }

    fun retry() { state.value.source?.let { open(it) } }

    fun confirm() {
        val s = state.value
        val source = s.source ?: return
        if (s.chosen.isEmpty() || s.busy) return
        val targets = (s.targets as? Load.Ready)?.value.orEmpty()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = api.sharePlacements(source.frameId, source.placementIds, targets.map { it.frame.id }.filter { it in s.chosen },
                    idempotencyKey = UUID.randomUUID().toString())
                _state.value = ShareState()
                if (r.placements.isEmpty()) {
                    _events.tryEmit(ShareEvent.NothingNew)
                } else {
                    val names = targets.filter { t -> r.placements.any { it.frameId == t.frame.id } }.map { it.frame.name }
                    _events.tryEmit(ShareEvent.Shared(names, r.placements.size) { undo(r.placements) })
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                _events.tryEmit(ShareEvent.Error(Problems.message(e)))
            }
        }
    }

    /** The sharer may purge the placements they created. */
    private fun undo(created: List<Placement>) {
        viewModelScope.launch {
            try {
                created.groupBy { it.frameId.orEmpty() }.forEach { (frame, ps) -> api.bulkPlacements(frame, ps.map { it.id }, BulkAction.PURGE) }
                _events.tryEmit(ShareEvent.Undone)
            } catch (e: Exception) {
                _events.tryEmit(ShareEvent.Error(Problems.message(e)))
            }
        }
    }
}
