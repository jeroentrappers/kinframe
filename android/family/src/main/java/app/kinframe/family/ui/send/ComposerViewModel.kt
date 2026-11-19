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

/** One recipient's server limits (discovery `limits` there); [server] is its label with several accounts, else null. */
data class ServerLimits(val server: String?, val limits: Limits)

/**
 * The limits of a send whose frames and families are on several servers (multi-server.md §2.2): each one the strictest
 * of the chosen recipients' servers, with that server's label when the servers differ on it (null otherwise), so the
 * Send tab can say which server holds the selection back.
 */
data class SendLimits(
    val limits: Limits,
    val itemsServer: String? = null,
    val videoServer: String? = null,
    val uploadServer: String? = null,
) {
    companion object {
        /** [base] (the primary account's limits) while nothing is chosen yet. */
        fun of(base: Limits, targets: List<ServerLimits>): SendLimits {
            if (targets.isEmpty()) return SendLimits(base)
            fun <T : Comparable<T>> strictest(f: (Limits) -> T): Pair<T, String?> {
                val min = targets.minBy { f(it.limits) }
                val differ = targets.map { f(it.limits) }.distinct().size > 1
                return f(min.limits) to min.server.takeIf { differ }
            }
            val (items, itemsServer) = strictest { it.maxItemsPerSend }
            val (video, videoServer) = strictest { it.maxVideoSeconds }
            val (upload, uploadServer) = strictest { it.maxUploadBytes }
            return SendLimits(base.copy(maxItemsPerSend = items, maxVideoSeconds = video, maxUploadBytes = upload), itemsServer, videoServer, uploadServer)
        }
    }
}

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
    /** Several accounts: each frame's and family's server label (multi-server.md §2.2); empty with one account. */
    val serverOf: Map<String, String> = emptyMap(),
    /** Each frame's and family's server limits; missing ones use [limits] (the primary account's). */
    val limitsOf: Map<String, ServerLimits> = emptyMap(),
) {
    /**
     * What applies to this send: the strictest limits of the chosen recipients' servers (multi-server.md §2.2), else
     * [limits]. Validation, trimming and the per-send count all use these.
     */
    val sendLimits: SendLimits get() =
        SendLimits.of(limits, (selected + selectedFeeds).map { id -> limitsOf[id] ?: ServerLimits(serverOf[id], limits) })

    /** More items than a chosen recipient's server takes in one send. */
    val tooMany: Boolean get() = items.size > sendLimits.limits.maxItemsPerSend

    val sendableFrames: List<Frame> get() = (frames as? Load.Ready)?.value?.filter { it.can { p -> p.send } }.orEmpty()

    /**
     * Frames and families grouped per server, in list order of first appearance; one group without a name when they
     * are all on one server.
     */
    val recipientGroups: List<RecipientGroup> get() {
        val labels = (sendableFrames.map { serverOf[it.id] } + feeds.map { serverOf[it.id] }).distinct()
        if (labels.size <= 1) return listOf(RecipientGroup(null, sendableFrames, feeds))
        return labels.map { l -> RecipientGroup(l, sendableFrames.filter { serverOf[it.id] == l }, feeds.filter { serverOf[it.id] == l }) }
    }

    /** At least one frame or feed (feeds.md §6.1). */
    val hasRecipients: Boolean get() = Recipients.valid(selected, selectedFeeds)

    /** Nothing to send to at all: no frame you may send to and no feed. */
    val noRecipients: Boolean get() = frames is Load.Ready && sendableFrames.isEmpty() && feeds.isEmpty()

    fun issue(item: DraftItem): ItemIssue? = when {
        item.info.size > sendLimits.limits.maxUploadBytes -> ItemIssue.TooLarge
        item.isVideo && (item.sendDurationMs ?: 0) > sendLimits.limits.maxVideoSeconds * 1000L -> ItemIssue.TooLong
        item.isVideo && item.info.durationMs != null && item.info.durationMs >= Framing.MIN_VIDEO_MS && (item.sendDurationMs ?: 0) < Framing.MIN_VIDEO_MS -> ItemIssue.TooShort
        else -> null
    }

    val issues: Int get() = items.count { issue(it) != null }
    val canSend: Boolean get() = items.isNotEmpty() && issues == 0 && !tooMany && hasRecipients && !preparing
}

/** One server's recipients in the Send tab; [server] is null when everything is on one server. */
data class RecipientGroup(val server: String?, val frames: List<Frame>, val feeds: List<Feed>)

/** Who a send goes to: frames and feeds; together they must not be empty (feeds.md §6.1). */
object Recipients {
    fun valid(frameIds: Collection<String>, feedIds: Collection<String>): Boolean = frameIds.isNotEmpty() || feedIds.isNotEmpty()

    /** The families to preselect for a frame you're not on: those reaching it that you can post to, in your order. */
    fun familiesToPreselect(reaching: Collection<String>, postable: List<String>): List<String> = postable.filter { it in reaching }

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
    /** A frame's or family's server label when signed in to several servers, else null. */
    private val serverLabel: (String) -> String? = { null },
    /** A frame's or family's server limits (its own server's discovery); null uses [limits]. */
    private val limitsFor: (String) -> Limits? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(ComposerState(limits = limits()))
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ComposerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ComposerEvent> = _events.asSharedFlow()

    private var preselect: String? = null
    private var preselectFeed: String? = null
    /** The photo request a preselected frame asked with (a frame you're not on: its families are preselected). */
    private var preselectRequest: String? = null

    init { loadFrames() }

    fun loadFrames() = viewModelScope.launch {
        val a = api() ?: return@launch
        _state.update { it.copy(limits = limits()) }
        familiesReady = kotlinx.coroutines.CompletableDeferred()
        if (feedsEnabled()) launch { loadFeeds(a) }
        else _state.update { it.copy(feeds = emptyList(), selectedFeeds = emptySet()) }
        try {
            val frames = a.frames().sortedBy { it.name.lowercase() }
            _state.update { s ->
                val sendable = frames.filter { it.can { p -> p.send } }
                val keep = s.selected.filter { id -> sendable.any { it.id == id } }.toSet()
                val sel = when {
                    preselect != null && sendable.any { it.id == preselect } -> setOf(preselect!!)
                    // A frame you can't send to: its families are preselected instead (below).
                    preselect != null -> emptySet()
                    keep.isNotEmpty() -> keep
                    // "Post to this feed": no frame on top of it.
                    preselectFeed != null || s.selectedFeeds.isNotEmpty() -> emptySet()
                    sendable.size == 1 -> setOf(sendable.single().id)
                    else -> emptySet()
                }
                s.copy(frames = Load.Ready(frames), selected = sel, serverOf = s.serverOf + labels(frames.map { it.id }),
                    limitsOf = s.limitsOf + serverLimits(frames.map { it.id }))
            }
            // A frame you're not on asked for photos (or went quiet) and reached you through a family: preselect the
            // families whose posts reach it (README §5.20).
            val wanted = preselect
            if (wanted != null && frames.none { it.id == wanted && it.can { p -> p.send } }) launch { preselectFamiliesOf(a, wanted, preselectRequest) }
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
            s.copy(feeds = feeds, selectedFeeds = if (pre != null) setOf(pre) else Recipients.keep(s.selectedFeeds, feeds.map { it.id }),
                serverOf = s.serverOf + labels(feeds.map { it.id }), limitsOf = s.limitsOf + serverLimits(feeds.map { it.id }))
        }
        familiesReady?.complete(Unit)
    }

    private fun labels(ids: List<String>): Map<String, String> = ids.mapNotNull { id -> serverLabel(id)?.let { id to it } }.toMap()

    private fun serverLimits(ids: List<String>): Map<String, ServerLimits> =
        ids.mapNotNull { id -> limitsFor(id)?.let { id to ServerLimits(serverLabel(id), it) } }.toMap()

    private var familiesReady: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private suspend fun preselectFamiliesOf(a: KinApi, frameId: String, requestId: String?) {
        if (!feedsEnabled()) return
        // The families are loaded alongside the frames: wait for them.
        if (state.value.feeds.isEmpty()) familiesReady?.let { kotlinx.coroutines.withTimeoutOrNull(10_000) { it.await() } }
        val postable = state.value.feeds.map { it.id }
        if (postable.isEmpty()) return
        var reaching: List<String> = emptyList()
        if (requestId != null) {
            reaching = social()?.let { sa -> runCatching { sa.photoRequest(requestId) }.getOrNull() }
                ?.takeIf { it.frameId == frameId }?.feedIds.orEmpty()
        }
        if (reaching.isEmpty()) {
            reaching = postable.filter { id ->
                runCatching { a.feedSubscriptions(id, app.kinframe.core.api.SubscriptionStatus.ACTIVE) }.getOrNull()
                    ?.any { it.frame.id == frameId } == true
            }
        }
        val ids = Recipients.familiesToPreselect(reaching, postable)
        if (preselect != frameId) return
        // Done: later visits of the tab keep what the person picks.
        preselect = null
        preselectRequest = null
        _state.update { s -> if (ids.isEmpty() || s.hasRecipients) s else s.copy(selectedFeeds = ids.toSet()) }
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

    /**
     * "Send to this frame" from a frame card, a fresh join, a frame request or a quiet-frame reminder. For a frame
     * you're not on, the families that reach it are preselected instead ([requestId]: the request names them).
     */
    fun preselectFrame(frameId: String, requestId: String? = null) {
        preselectFeed = null
        preselectRequest = requestId
        _state.update { it.copy(selectedFeeds = emptySet()) }
        preselect = frameId
        _state.update { s ->
            val sendable = s.sendableFrames.any { it.id == frameId }
            if (sendable) s.copy(selected = setOf(frameId)) else s.copy(selected = emptySet())
        }
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
                val room = (s.sendLimits.limits.maxItemsPerSend - s.items.size).coerceAtLeast(0)
                val maxMs = s.sendLimits.limits.maxVideoSeconds * 1000L
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
        val (s, e) = Framing.clampTrim(start, end, d, state.value.sendLimits.limits.maxVideoSeconds * 1000L, moved)
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
