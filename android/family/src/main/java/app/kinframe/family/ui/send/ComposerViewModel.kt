package app.kinframe.family.ui.send

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Limits
import app.kinframe.core.api.MediaKind
import app.kinframe.family.send.Framing
import app.kinframe.family.send.MediaInfo
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.TrimHandle
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

/** One photo or video being prepared for sending. */
data class DraftItem(
    val id: String,
    val info: MediaInfo,
    val caption: String = "",
    val fill: Boolean = true,
    val focalX: Float = 0.5f,
    val focalY: Float = 0.5f,
    /** Null = the whole video. */
    val trim: Pair<Long, Long>? = null,
    val includeAudio: Boolean = true,
    /** A Motion Photo goes out live unless the person turns that off ("send as still only"). */
    val sendLive: Boolean = true,
) {
    val isVideo: Boolean get() = info.kind == MediaKind.VIDEO
    val isLive: Boolean get() = info.motionPhoto && sendLive
    val aspect: Float? get() = info.width?.let { w -> info.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h } }
    val sendDurationMs: Long? get() = trim?.let { it.second - it.first } ?: info.durationMs
}

enum class ItemIssue { TooLarge, TooLong, TooShort }

data class ComposerState(
    val items: List<DraftItem> = emptyList(),
    val frames: Load<List<Frame>> = Load.Loading,
    val selected: Set<String> = emptySet(),
    val limits: Limits = Limits(),
    val editing: Int? = null,
    val adding: Boolean = false,
    val preparing: Boolean = false,
    /** Files skipped because they aren't photos or videos. */
    val skipped: Int = 0,
    /** Files left out because of the per-send limit. */
    val overLimit: Int = 0,
    @StringRes val error: Int? = null,
) {
    val sendableFrames: List<Frame> get() = (frames as? Load.Ready)?.value?.filter { it.can { p -> p.send } }.orEmpty()

    fun issue(item: DraftItem): ItemIssue? = when {
        item.info.size > limits.maxUploadBytes -> ItemIssue.TooLarge
        item.isVideo && (item.sendDurationMs ?: 0) > limits.maxVideoSeconds * 1000L -> ItemIssue.TooLong
        item.isVideo && item.info.durationMs != null && item.info.durationMs >= Framing.MIN_VIDEO_MS && (item.sendDurationMs ?: 0) < Framing.MIN_VIDEO_MS -> ItemIssue.TooShort
        else -> null
    }

    val issues: Int get() = items.count { issue(it) != null }
    val canSend: Boolean get() = items.isNotEmpty() && issues == 0 && selected.isNotEmpty() && !preparing
}

sealed interface ComposerEvent {
    data class Queued(val outboxId: String) : ComposerEvent
    data class CaptionApplied(val count: Int) : ComposerEvent
}

/**
 * The Send tab: photos and videos from the picker or the share sheet, captions and framing per item, target frames,
 * then hand-off to the outbox (uploads continue in the background).
 */
class ComposerViewModel(
    private val api: () -> KinApi?,
    private val limits: () -> Limits,
    private val server: () -> String,
    private val inspect: suspend (Uri) -> MediaInfo?,
    /** Makes the files readable after the screen closes (persist grants or copy) and queues the send. */
    private val enqueue: suspend (OutboxSend) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val _state = MutableStateFlow(ComposerState(limits = limits()))
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ComposerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ComposerEvent> = _events.asSharedFlow()

    private var preselect: String? = null

    init { loadFrames() }

    fun loadFrames() = viewModelScope.launch {
        val a = api() ?: return@launch
        _state.update { it.copy(limits = limits()) }
        try {
            val frames = a.frames().sortedBy { it.name.lowercase() }
            _state.update { s ->
                val sendable = frames.filter { it.can { p -> p.send } }
                val keep = s.selected.filter { id -> sendable.any { it.id == id } }.toSet()
                val sel = when {
                    preselect != null && sendable.any { it.id == preselect } -> setOf(preselect!!)
                    keep.isNotEmpty() -> keep
                    sendable.size == 1 -> setOf(sendable.single().id)
                    else -> emptySet()
                }
                s.copy(frames = Load.Ready(frames), selected = sel)
            }
        } catch (e: Exception) {
            if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
        }
    }

    /** "Send to this frame" from a frame card or a fresh join. */
    fun preselectFrame(frameId: String) {
        preselect = frameId
        _state.update { s -> if ((s.frames as? Load.Ready)?.value?.any { it.id == frameId } == true) s.copy(selected = setOf(frameId)) else s }
        loadFrames()
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(adding = true, skipped = 0, overLimit = 0) }
        viewModelScope.launch {
            val existing = state.value.items.map { it.info.uri }.toSet()
            val infos = uris.distinct().filterNot { it in existing }.map { it to runCatching { inspect(it) }.getOrNull() }
            val media = infos.mapNotNull { it.second }
            val skipped = infos.count { it.second == null }
            _state.update { s ->
                val room = (s.limits.maxItemsPerSend - s.items.size).coerceAtLeast(0)
                val maxMs = s.limits.maxVideoSeconds * 1000L
                val added = media.take(room).map { info ->
                    DraftItem(newId(), info, trim = if (info.kind == MediaKind.VIDEO) Framing.defaultTrim(info.durationMs, maxMs) else null)
                }
                s.copy(items = s.items + added, adding = false, skipped = skipped, overLimit = (media.size - room).coerceAtLeast(0))
            }
        }
    }

    fun remove(id: String) = _state.update { s ->
        val items = s.items.filterNot { it.id == id }
        s.copy(items = items, editing = s.editing?.let { e -> if (items.isEmpty()) null else e.coerceAtMost(items.lastIndex) })
    }

    fun clear() = _state.update { it.copy(items = emptyList(), editing = null, skipped = 0, overLimit = 0) }

    fun edit(index: Int?) = _state.update { it.copy(editing = index?.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))) }

    private fun change(id: String, f: (DraftItem) -> DraftItem) = _state.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    fun setCaption(id: String, text: String) = change(id) { it.copy(caption = text.take(CAPTION_MAX)) }

    fun applyCaptionToAll(id: String) {
        val caption = state.value.items.firstOrNull { it.id == id }?.caption ?: return
        _state.update { s -> s.copy(items = s.items.map { it.copy(caption = caption) }) }
        _events.tryEmit(ComposerEvent.CaptionApplied(state.value.items.size))
    }

    fun setFill(id: String, fill: Boolean) = change(id) { it.copy(fill = fill) }

    fun setFocal(id: String, x: Float, y: Float) {
        val (fx, fy) = Framing.clampFocal(x, y)
        change(id) { it.copy(focalX = fx, focalY = fy) }
    }

    fun setTrim(id: String, start: Long, end: Long, moved: TrimHandle) = change(id) { item ->
        val d = item.info.durationMs ?: return@change item
        val (s, e) = Framing.clampTrim(start, end, d, state.value.limits.maxVideoSeconds * 1000L, moved)
        item.copy(trim = if (s == 0L && e == d) null else s to e)
    }

    fun setIncludeAudio(id: String, on: Boolean) = change(id) { it.copy(includeAudio = on) }

    fun setSendLive(id: String, live: Boolean) = change(id) { it.copy(sendLive = live) }

    fun toggleFrame(frameId: String) = _state.update { s ->
        s.copy(selected = if (frameId in s.selected) s.selected - frameId else s.selected + frameId, error = null)
    }

    fun send() {
        val s = state.value
        if (!s.canSend) return
        val frames = s.sendableFrames.filter { it.id in s.selected }
        val outbox = OutboxSend(
            id = newId(),
            server = server(),
            frameIds = frames.map { it.id },
            frameNames = frames.map { it.name },
            createdAt = now(),
            items = s.items.map { d ->
                OutboxItem(
                    id = d.id,
                    uri = d.info.uri.toString(),
                    mime = d.info.mime,
                    name = d.info.name,
                    size = d.info.size,
                    kind = d.info.kind,
                    caption = d.caption.trim().ifEmpty { null },
                    fit = if (d.fill) "fill" else "fit",
                    focalX = d.focalX.toDouble(),
                    focalY = d.focalY.toDouble(),
                    trimStartMs = d.trim?.first,
                    trimEndMs = d.trim?.second,
                    includeAudio = d.includeAudio,
                    motionPhoto = d.info.motionPhoto,
                    live = d.sendLive,
                )
            },
        )
        _state.update { it.copy(preparing = true, error = null) }
        viewModelScope.launch {
            try {
                enqueue(outbox)
                _state.update { it.copy(preparing = false, items = emptyList(), editing = null, skipped = 0, overLimit = 0) }
                _events.tryEmit(ComposerEvent.Queued(outbox.id))
            } catch (e: Exception) {
                _state.update { it.copy(preparing = false, error = Problems.message(e)) }
            }
        }
    }

    companion object {
        const val CAPTION_MAX = 280
    }
}
