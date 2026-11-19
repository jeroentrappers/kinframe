package app.kinframe.family.ui.send

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
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
    /** Feeds you can post to (any role), when the server has feeds; shown in their own section next to frames. */
    val feeds: List<Feed> = emptyList(),
    val selectedFeeds: Set<String> = emptySet(),
    // ---- family social (README §5.18, §5.20)
    /** The caller's circles per feed: an audience inside that family ("Share with: whole family / The cousins"). */
    val circles: Map<String, List<app.kinframe.core.social.Circle>> = emptyMap(),
    /** Chosen circle per selected feed; absent = the whole family. */
    val audience: Map<String, String> = emptyMap(),
    /** This week's prompt per feed (families with prompts on). */
    val prompts: Map<String, app.kinframe.core.social.FeedPrompt> = emptyMap(),
    /** The prompt these photos answer (its feed must be selected). */
    val answering: String? = null,
) {
    val sendableFrames: List<Frame> get() = (frames as? Load.Ready)?.value?.filter { it.can { p -> p.send } }.orEmpty()

    /** At least one frame or feed (feeds.md §6.1). */
    val hasRecipients: Boolean get() = Recipients.valid(selected, selectedFeeds)

    /** Nothing to send to at all: no frame you may send to and no feed. */
    val noRecipients: Boolean get() = frames is Load.Ready && sendableFrames.isEmpty() && feeds.isEmpty()

    fun issue(item: DraftItem): ItemIssue? = when {
        item.info.size > limits.maxUploadBytes -> ItemIssue.TooLarge
        item.isVideo && (item.sendDurationMs ?: 0) > limits.maxVideoSeconds * 1000L -> ItemIssue.TooLong
        item.isVideo && item.info.durationMs != null && item.info.durationMs >= Framing.MIN_VIDEO_MS && (item.sendDurationMs ?: 0) < Framing.MIN_VIDEO_MS -> ItemIssue.TooShort
        else -> null
    }

    val issues: Int get() = items.count { issue(it) != null }
    val canSend: Boolean get() = items.isNotEmpty() && issues == 0 && hasRecipients && !preparing
}

/** Who a send goes to: frames and feeds; together they must not be empty (feeds.md §6.1). */
object Recipients {
    fun valid(frameIds: Collection<String>, feedIds: Collection<String>): Boolean = frameIds.isNotEmpty() || feedIds.isNotEmpty()

    /** Keep only what can still be sent to (a feed you left, a frame whose send permission went). */
    fun keep(selected: Set<String>, available: Collection<String>): Set<String> = selected.filter { it in available }.toSet()
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
    /** Discovery `feeds`: offer feeds as recipients. */
    private val feedsEnabled: () -> Boolean = { false },
    /** Family social: circles as audience and weekly prompts; null without it. */
    private val social: () -> app.kinframe.core.social.SocialApi? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(ComposerState(limits = limits()))
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ComposerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ComposerEvent> = _events.asSharedFlow()

    private var preselect: String? = null
    private var preselectFeed: String? = null

    init { loadFrames() }

    fun loadFrames() = viewModelScope.launch {
        val a = api() ?: return@launch
        _state.update { it.copy(limits = limits()) }
        if (feedsEnabled()) launch { loadFeeds(a) }
        else _state.update { it.copy(feeds = emptyList(), selectedFeeds = emptySet()) }
        try {
            val frames = a.frames().sortedBy { it.name.lowercase() }
            _state.update { s ->
                val sendable = frames.filter { it.can { p -> p.send } }
                val keep = s.selected.filter { id -> sendable.any { it.id == id } }.toSet()
                val sel = when {
                    preselect != null && sendable.any { it.id == preselect } -> setOf(preselect!!)
                    keep.isNotEmpty() -> keep
                    // "Post to this feed": no frame on top of it.
                    preselectFeed != null || s.selectedFeeds.isNotEmpty() -> emptySet()
                    sendable.size == 1 -> setOf(sendable.single().id)
                    else -> emptySet()
                }
                s.copy(frames = Load.Ready(frames), selected = sel)
            }
        } catch (e: Exception) {
            if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
        }
    }

    private suspend fun loadFeeds(a: KinApi) {
        // `member` reads and comments but doesn't post photos (README §5.18): those families aren't offered.
        val feeds = runCatching { a.feeds() }.getOrNull()?.filter { it.canPost }?.sortedBy { it.name.lowercase() } ?: return
        social()?.let { sa -> viewModelScope.launch { loadSocial(sa, feeds) } }
        _state.update { s ->
            val pre = preselectFeed?.takeIf { id -> feeds.any { it.id == id } }
            s.copy(feeds = feeds, selectedFeeds = if (pre != null) setOf(pre) else Recipients.keep(s.selectedFeeds, feeds.map { it.id }))
        }
    }

    private suspend fun loadSocial(sa: app.kinframe.core.social.SocialApi, feeds: List<Feed>) {
        val circles = feeds.filter { it.circleCount > 0 }.associate { f ->
            f.id to runCatching { sa.circles(f.id) }.getOrDefault(emptyList()).filter { it.isMember }
        }.filterValues { it.isNotEmpty() }
        val prompts = feeds.mapNotNull { f ->
            runCatching { sa.feedPrompts(f.id) }.getOrNull()?.firstOrNull { it.status == app.kinframe.core.social.PromptStatus.CURRENT && it.title.isNotBlank() }?.let { f.id to it }
        }.toMap()
        _state.update { it.copy(circles = circles, prompts = prompts) }
    }

    /** "Share with": [circleId] null is the whole family. */
    fun setAudience(feedId: String, circleId: String?) = _state.update { s ->
        s.copy(audience = if (circleId == null) s.audience - feedId else s.audience + (feedId to circleId))
    }

    /** Tag these photos as an answer to a weekly prompt, or (null) not. */
    fun answer(promptId: String?) = _state.update { it.copy(answering = promptId) }

    /** "Post to this feed" from a feed: the feed alone is selected. */
    fun preselectFeed(feedId: String) {
        preselectFeed = feedId
        preselect = null
        _state.update { s -> s.copy(selected = emptySet(), selectedFeeds = if (s.feeds.any { it.id == feedId }) setOf(feedId) else s.selectedFeeds) }
        loadFrames()
    }

    fun toggleFeed(feedId: String) = _state.update { s ->
        val on = feedId !in s.selectedFeeds
        s.copy(
            selectedFeeds = if (on) s.selectedFeeds + feedId else s.selectedFeeds - feedId, error = null,
            audience = if (on) s.audience else s.audience - feedId,
            answering = if (!on && s.prompts[feedId]?.id == s.answering) null else s.answering,
        )
    }

    /** "Send to this frame" from a frame card or a fresh join. */
    fun preselectFrame(frameId: String) {
        preselectFeed = null
        _state.update { it.copy(selectedFeeds = emptySet()) }
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
        val feeds = s.feeds.filter { it.id in s.selectedFeeds }
        if (!Recipients.valid(frames.map { it.id }, feeds.map { it.id })) return
        val outbox = OutboxSend(
            feedIds = feeds.map { it.id },
            feedNames = feeds.map { it.name },
            circleIds = feeds.mapNotNull { s.audience[it.id] },
            promptId = s.answering?.takeIf { p -> s.prompts.any { (fid, pr) -> pr.id == p && fid in s.selectedFeeds } },
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
                preselectFeed = null
                _state.update { it.copy(preparing = false, items = emptyList(), editing = null, skipped = 0, overLimit = 0, answering = null) }
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
