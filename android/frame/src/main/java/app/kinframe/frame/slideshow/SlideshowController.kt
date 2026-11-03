package app.kinframe.frame.slideshow

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.kinframe.core.api.FrameSettings
import app.kinframe.frame.FrameController
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.schedule.ScreenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One prepared slide: the placement and its screen-sized bitmap (null if it could not be decoded). */
data class Slide(
    val placement: PlacementEntity,
    val image: ImageBitmap?,
    val key: String,
    /** Videos, live photos and [captionSeparate] slides: the caption as a bottom strip, drawn above the slide. */
    val captionStrip: ImageBitmap? = null,
    /** Collages and smart pairs: every photo and its cell ([placement] is the first one). Empty for one photo. */
    val tiles: List<Tile> = emptyList(),
    /** Ken Burns motion while this slide is on screen, or null. */
    val kenBurns: KenBurns.Params? = null,
    /** The caption is not in [image] but only in [captionStrip], so Ken Burns zooms the photo and not the text. */
    val captionSeparate: Boolean = false,
    /** The split layout's two photos: [image] leaves the dashboard's quarter of the screen empty. */
    val split: Boolean = false,
) {
    val ids: List<String> get() = if (tiles.isEmpty()) listOf(placement.id) else tiles.map { it.placement.id }
}

data class Tile(val placement: PlacementEntity, val cell: Collage.Cell)

enum class TransitionKind { Crossfade, Slide, Cut }

/** What the screen shows: [incoming] animates in over [outgoing]. A new [seq] starts a new transition. */
data class Stage(
    val outgoing: Slide? = null,
    val incoming: Slide? = null,
    val seq: Long = 0,
    val kind: TransitionKind = TransitionKind.Crossfade,
    val durationMs: Int = 800,
    /** +1: incoming from the right (next), -1: from the left (previous). */
    val direction: Int = 1,
    /** How long the incoming slide is expected to stay (drives the Ken Burns motion). */
    val holdMs: Long = 30_000,
    /** Where a slide transition starts (0..1): a released swipe continues from the finger instead of from the edge. */
    val startProgress: Float = 0f,
)

/**
 * Immediate feedback for rotate / fill on the frame: the slide on screen turns or rescales right away while the
 * sharp version renders (seconds on the RK3126), then cross-fades into it.
 */
data class AdjustPreview(val slideKey: String, val rotation: Float, val scale: Float)

sealed interface EmptyState {
    data object NoPhotos : EmptyState
    data object AllHidden : EmptyState
    data class Downloading(val total: Int) : EmptyState
}

/** What the player shows: a video slide, or ([live]) the clip of a Live/Motion Photo over its still. */
data class VideoState(
    val placementId: String, val file: File, val visible: Boolean = false, val live: Boolean = false,
    /** Paused and hidden while a swipe drags the slide: its still moves with the finger, not a playing texture. */
    val held: Boolean = false,
)

/**
 * Drives the slideshow: playlist, timing, pre-decoding the next slide at screen size, video playback (Media3),
 * seen-acks on first display, and the new-photo experience.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlideshowController(private val c: FrameController) {
    private val g = c.g
    private val renderer = SlideRenderer()
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cache = LinkedHashMap<String, Slide>(4, 0.75f, true)
    private val navLock = Mutex()

    val playlist = Playlist(g.prefs.playlistSeed.takeIf { it != 0L } ?: System.nanoTime().also { g.prefs.playlistSeed = it })

    private val _stage = MutableStateFlow(Stage())
    val stage: StateFlow<Stage> = _stage
    private val _empty = MutableStateFlow<EmptyState?>(null)
    val empty: StateFlow<EmptyState?> = _empty
    private val _newCount = MutableStateFlow(0)
    val newCount: StateFlow<Int> = _newCount
    private val _video = MutableStateFlow<VideoState?>(null)
    val video: StateFlow<VideoState?> = _video
    private val _current = MutableStateFlow<PlacementEntity?>(null)
    val current: StateFlow<PlacementEntity?> = _current

    /** The small "Live" mark on the current live photo: null (hidden), false ("Live") or true ("Tap to play"). */
    private val _liveMark = MutableStateFlow<Boolean?>(null)
    val liveMark: StateFlow<Boolean?> = _liveMark
    /** The live photo whose clip is loaded (paused at its start) in the player, ready to play without a delay. */
    private var prebufferedId: String? = null

    /** Set by the UI: true while the quick menu, settings or a dialog is open (auto-advance pauses). */
    val paused = MutableStateFlow(false)

    private var playable: Map<String, PlacementEntity> = emptyMap()
    /** Visible items whose file was evicted to free space: in the playlist, downloaded again just before they show. */
    private var onDemand: Set<String> = emptySet()
    private var settings = FrameSettings.DEFAULT
    private var screenW = 0
    private var screenH = 0
    /** The split layout's side while it is on (docs/plugins.md §2.2), else null. */
    private var splitSide: app.kinframe.frame.plugins.BandSide? = null
    private var shownAt = 0L
    private var transitionRunning = false
    private var videoEnded = false
    private var started = false
    private var timerJob: Job? = null
    private var dataJob: Job? = null
    private var stopped = false

    var player: ExoPlayer? = null
        private set

    fun start() {
        if (started) return
        started = true
        stopped = false
        dataJob = c.scope.launch {
            combine(g.db.placements().observeAll(), g.settings.settings) { all, s -> all to s }.collect { (all, s) ->
                onData(all, s)
            }
        }
        timerJob = c.scope.launch { timerLoop() }
        c.scope.launch {
            c.display.mode.map { it == ScreenMode.Asleep }.distinctUntilChanged().collect { asleep ->
                if (asleep) player?.pause() else if (_video.value?.held == false && !videoEnded) player?.play()
            }
        }
    }

    /** The clock/weather corner as last measured by the UI (null until then: estimated from the settings). */
    @Volatile private var measuredCorner: CornerReserve? = null

    private fun corner(): CornerReserve = measuredCorner
        ?: CornerReserve.estimate(settings.widgets.clock || settings.widgets.date, settings.widgets.weather && settings.location != null)

    /**
     * Called by the UI with the size of the clock and weather block in the bottom-right corner (dp, margins included;
     * 0 × 0 when it isn't shown), so captions and pair/collage labels end before it. Re-renders the current slide when
     * that changes what it needs.
     */
    fun setCornerBlock(widthDp: Float, heightDp: Float) {
        val r = CornerReserve.quantized(widthDp, heightDp)
        if (r == measuredCorner) return
        val before = corner()
        measuredCorner = r
        if (r != before && screenW > 0 && settings.widgets.caption) c.scope.launch { rerenderCurrent(onlyIfChanged = true) }
    }

    /**
     * Called by the UI with the split layout's side, or null when it is off: two photos per slide around the
     * dashboard. Re-renders what is on screen.
     */
    fun setSplit(side: app.kinframe.frame.plugins.BandSide?) {
        if (side == splitSide) return
        splitSide = side
        cache.clear()
        if (screenW > 0) c.scope.launch { rerenderCurrent() }
    }

    private fun splitCells(): app.kinframe.frame.plugins.SplitGeometry.Cells? =
        splitSide?.let { app.kinframe.frame.plugins.SplitGeometry.cells(screenW >= screenH, it) }

    /** Called by the UI with the real drawing size; re-renders on rotation. */
    fun setScreenSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == screenW && h == screenH)) return
        val first = screenW == 0
        screenW = w
        screenH = h
        cache.clear()
        if (!first) c.scope.launch { rerenderCurrent() }
        else c.scope.launch { if (_stage.value.incoming == null) next(manual = false) }
    }

    private suspend fun onData(all: List<PlacementEntity>, s: FrameSettings) {
        val renderChanged = s.slideshow.fill != settings.slideshow.fill || s.slideshow.background != settings.slideshow.background ||
            s.slideshow.captionDate != settings.slideshow.captionDate || s.display.textScale != settings.display.textScale ||
            s.slideshow.collage != settings.slideshow.collage || s.slideshow.collageMax != settings.slideshow.collageMax ||
            s.slideshow.pairPortraits != settings.slideshow.pairPortraits || s.slideshow.transition != settings.slideshow.transition
        val widgetsChanged = s.widgets != settings.widgets
        settings = s
        val skipped = g.crashGuard.skippedItems()
        val allVisible = all.filter { it.isVisible }
        // Album mode (settings `album_id`): only that album; an empty or unknown album plays everything.
        val visible = s.slideshow.albumId?.let { album -> allVisible.filter { album in it.albumIdList } }?.takeIf { it.isNotEmpty() } ?: allVisible
        val today = java.time.Instant.ofEpochMilli(g.clock.nowMs()).atZone(g.clock.zone).toLocalDate()
        val onThisDay = if (s.slideshow.onThisDay) OnThisDay.ids(visible, today, g.clock.zone) else emptySet()
        playable = visible.filter { it.localFile != null && it.id !in skipped }.associateBy { it.id }
        val evicted = visible.filter { it.localFile == null && it.evictedAt != null && it.id !in skipped }
        onDemand = evicted.mapTo(HashSet()) { it.id }
        _empty.value = when {
            playable.isNotEmpty() -> null
            all.isEmpty() -> EmptyState.NoPhotos
            visible.isEmpty() -> EmptyState.AllHidden
            else -> EmptyState.Downloading(visible.size)
        }
        playlist.update(
            (playable.values + evicted).map { PlaylistEntry(it.id, it.receivedAt, it.capturedAt, it.isNew, featured = it.id in onThisDay) },
            PlayOrder.of(s.slideshow.order), s.slideshow.reverse,
        )
        if (playlist.current == null) playlist.restore(g.prefs.playlistCurrent)
        _newCount.value = playlist.pendingNew().size
        val cur = _stage.value.incoming?.placement
        // Caption, reaction or focal point of the current slide changed: re-render it in place.
        var refresh = false
        if (cur != null) playable[cur.id]?.let { fresh ->
            if (fresh.caption != cur.caption || fresh.reaction != cur.reaction || fresh.captionEdited != cur.captionEdited ||
                fresh.fit != cur.fit || fresh.focalX != cur.focalX || fresh.focalY != cur.focalY || fresh.rotation != cur.rotation
            ) refresh = true
            _current.value = fresh
        }
        when {
            (renderChanged || widgetsChanged) && cur != null -> { cache.clear(); rerenderCurrent() }
            refresh -> rerenderCurrent()
            cur != null && _stage.value.incoming!!.ids.any { it !in playable } -> next(manual = false) // removed, hidden or trashed
            cur == null && playable.isNotEmpty() && screenW > 0 -> next(manual = false)
            playable.isEmpty() && cur != null -> {
                stopVideo()
                _stage.value = Stage(seq = _stage.value.seq + 1)
                _current.value = null
            }
        }
    }

    private suspend fun timerLoop() {
        while (true) {
            delay(250)
            player?.let { pl -> playerVolume().let { v -> if (pl.volume != v) pl.volume = v } }
            val cur = _stage.value.incoming ?: continue
            if (paused.value || transitionRunning || c.display.mode.value == ScreenMode.Asleep ||
                c.display.mode.value == ScreenMode.FadingOut || c.settingsOpen.value
            ) {
                // Keep the full interval after an interruption (touch pauses advancing).
                if (paused.value || c.settingsOpen.value) shownAt = SystemClock.elapsedRealtime()
                continue
            }
            if (playable.size <= 1 && playlist.pendingNew().isEmpty()) continue
            val elapsed = SystemClock.elapsedRealtime() - shownAt
            val interval = if (cur.image == null) 6_000L else settings.slideshow.intervalS.coerceIn(2, 86_400) * 1000L
            val v = _video.value
            val due = when {
                // Live clip playing or fading back to the still: the interval starts once it is done.
                v != null && v.live -> LivePolicy.due(playing = true, elapsed, interval)
                v != null -> VideoPolicy.due(settings.video.playback, videoEnded, elapsed, interval)
                else -> elapsed >= interval
            }
            if (due) next(manual = false)
        }
    }

    fun next(manual: Boolean, fade: Boolean = false) = c.scope.launch { navigate(+1, manual, fade) }
    fun previous(fade: Boolean = false) = c.scope.launch { navigate(-1, true, fade) }

    /**
     * Finishes a swipe the finger has already moved [fromProgress] of the way. [previewKey] is the key of the slide that was
     * shown beside the current one while dragging; if the slide that actually comes next differs (e.g. it became a pair),
     * the transition starts from the edge instead of jumping.
     */
    fun swipe(direction: Int, fromProgress: Float, previewKey: String) =
        c.scope.launch { navigate(direction, manual = true, startProgress = fromProgress.coerceIn(0f, 0.95f), previewKey = previewKey) }

    /** The already-rendered single slide a swipe in [direction] would bring in, or null (not ready, collage, mid-transition). */
    fun neighbor(direction: Int): Slide? {
        val st = _stage.value
        if (st.outgoing != null || st.incoming == null || st.incoming.tiles.isNotEmpty()) return null
        val (slide, id) = if (direction > 0) _prepared.value to playlist.peekNext() else _preparedPrevious.value to playlist.peekPrevious()
        return slide?.takeIf { id != null && it.placement.id == id && it.tiles.isEmpty() && id in playable }
    }

    /** Shows [id] as soon as it is playable again (e.g. right after "Undo"). */
    suspend fun showWhenAvailable(id: String, timeoutMs: Long = 3_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (id !in playable && SystemClock.elapsedRealtime() < deadline) delay(100)
        show(id)
    }

    /** Remote `show_placement` / the "N new" card. */
    suspend fun show(id: String): Boolean {
        if (id !in playable) return false
        navLock.withLock {
            if (!playlist.jumpTo(id)) return false
            present(id, +1, TransitionKind.Crossfade)
        }
        return true
    }

    fun showNew() = c.scope.launch { playlist.pendingNew().firstOrNull()?.let { show(it) } }

    private suspend fun navigate(direction: Int, manual: Boolean, fade: Boolean = false, startProgress: Float = 0f, previewKey: String? = null) {
        if (screenW == 0 || stopped) return
        if (navLock.isLocked && !manual) return
        navLock.withLock {
            var id = (if (direction > 0) playlist.next() else playlist.previous()) ?: return
            // Evicted items are fetched again in the background; show the next one that is here meanwhile.
            var tries = 0
            while (id !in playable && tries++ < playlist.size) {
                if (id in onDemand) c.storage.requestOnDemand(id)
                id = (if (direction > 0) playlist.next() else playlist.previous()) ?: return
            }
            if (id !in playable) return
            val kind = when {
                fade -> TransitionKind.Crossfade
                manual -> TransitionKind.Slide
                settings.slideshow.transition == "slide" -> TransitionKind.Slide
                else -> TransitionKind.Crossfade
            }
            present(id, direction, kind, startProgress, previewKey)
        }
    }

    private suspend fun present(id: String, direction: Int, kind: TransitionKind, startProgress: Float = 0f, previewKey: String? = null) {
        val p = playable[id] ?: return
        g.crashGuard.currentItem = id
        val group = groupFor(p)
        group.drop(1).forEach { playlist.consume(it.id) }
        val base = if (group.size > 1) obtainGroup(group) else obtain(p)
        val kb = if (settings.slideshow.transition == "kenburns" && group.size == 1 && !p.isVideo && base.image != null)
            KenBurns.params(p.id, p.focalX, p.focalY) else null
        val slide = if (kb != null) base.copy(kenBurns = kb) else base
        stopVideo()
        val prev = _stage.value
        // Continue a released swipe from where the finger left it, but only if the slide shown while dragging is the one
        // that actually comes in.
        val start = if (kind == TransitionKind.Slide && previewKey != null && previewKey == slide.key) startProgress else 0f
        val duration = when (kind) {
            TransitionKind.Slide -> (450 * (1f - start)).toInt().coerceAtLeast(140)
            TransitionKind.Cut -> 0
            TransitionKind.Crossfade -> settings.slideshow.transitionMs.coerceIn(200, 3000)
        }
        transitionRunning = duration > 0
        val hold = settings.slideshow.intervalS.coerceIn(2, 86_400) * 1000L + duration
        _stage.value = Stage(prev.incoming, slide, prev.seq + 1, if (prev.incoming == null) TransitionKind.Crossfade else kind, duration, direction, hold, start)
        _current.value = p
        shownAt = SystemClock.elapsedRealtime()
        g.prefs.playlistCurrent = id
        if (playlist.seed != g.prefs.playlistSeed) g.prefs.playlistSeed = playlist.seed
        _newCount.value = playlist.pendingNew().size
        group.forEach { markShown(it) }
        // Neighbours are pre-rendered when the transition ends (onTransitionDone), not while it animates.
        if (duration == 0) onTransitionDone(_stage.value.seq)
    }

    /** The UI reports the end of a transition; videos start only then. */
    fun onTransitionDone(seq: Long) {
        if (seq != _stage.value.seq) return
        transitionRunning = false
        shownAt = SystemClock.elapsedRealtime()
        val st = _stage.value
        if (st.outgoing != null) _stage.value = st.copy(outgoing = null)
        if (_preview.value != null && _preview.value?.slideKey != st.incoming?.key) _preview.value = null
        prefetch()
        val p = st.incoming?.placement ?: return
        if (p.isVideo && settings.video.autoplay && st.incoming.image != null) startVideo(p)
        if (p.isLive && st.incoming.image != null) {
            val alone = st.incoming.tiles.isEmpty()
            if (LivePolicy.autoPlay(settings.slideshow.livePhotos, liveClip(p) != null, alone)) startLive(p) else updateLiveMark()
        }
    }

    /** The downloaded clip of a live photo, or null. */
    private fun liveClip(p: PlacementEntity): File? =
        p.motionLocalFile?.takeIf { p.isLive }?.let { g.media.file(it) }?.takeIf { it.exists() }

    private fun updateLiveMark() {
        val inc = _stage.value.incoming
        val p = inc?.placement?.let { playable[it.id] ?: it }
        val ls = settings.slideshow.livePhotos
        _liveMark.value = if (p != null && LivePolicy.showMark(ls, liveClip(p) != null, inc.tiles.isEmpty(), _video.value?.live == true)) LivePolicy.tapHint(ls) else null
    }

    /** A tap on the photo: replays the clip of a live photo. Returns true when it started. */
    fun replayLive(): Boolean {
        val inc = _stage.value.incoming ?: return false
        val p = playable[inc.placement.id] ?: return false
        if (transitionRunning || !LivePolicy.playOnTap(settings.slideshow.livePhotos, liveClip(p) != null, inc.tiles.isEmpty(), _video.value != null)) return false
        startLive(p)
        return true
    }

    /** Aspect ratio as displayed (rotation applied), or null when unknown. */
    private fun aspect(p: PlacementEntity): Float? {
        val w = p.width ?: return null
        val h = p.height ?: return null
        if (w <= 0 || h <= 0) return null
        return if (p.rotation % 180 != 0) h.toFloat() / w else w.toFloat() / h
    }

    /**
     * What goes on screen with [p]: a collage of the next photos (setting `collage`), a smart pair of two
     * same-orientation photos that would otherwise be cropped awkwardly (`pair_portraits`), or just [p].
     * Videos always play alone.
     */
    private fun groupFor(p: PlacementEntity): List<PlacementEntity> {
        if (p.isVideo || screenW == 0) return listOf(p)
        // A live photo whose clip will play stays alone, like a video.
        if (p.isLive && p.motionLocalFile != null && settings.slideshow.livePhotos != app.kinframe.core.api.LivePhotos.OFF) return listOf(p)
        val ss = settings.slideshow
        val photos = playlist.upcoming(12).mapNotNull { playable[it] }.filter { !it.isVideo }
        splitCells()?.let { cells ->
            // Split layout: always two photos, each in the cell its shape fits (a portrait in the tall one).
            val partner = Collage.splitPartner(aspect(p) ?: 1f, listOf(cells.big, cells.small), photos.map { it.id to aspect(it) }, screenW, screenH)
            return photos.firstOrNull { it.id == partner }?.let { listOf(p, it) } ?: listOf(p)
        }
        if (ss.collage) {
            val n = Collage.count(ss.collageMax, photos.size + 1)
            if (n >= 2) return listOf(p) + photos.take(n - 1)
        } else if (ss.pairPortraits) {
            val a = aspect(p) ?: return listOf(p)
            // Only when fill would crop it hard or it would sit in wide bars; an explicit "fit" per photo stays alone.
            if (p.fit != "fit" && Collage.wantsPair(a, screenW, screenH)) {
                val partner = Collage.partner(photos.take(3).filter { it.fit != "fit" }.map { it.id to aspect(it) }, screenW, screenH)
                photos.firstOrNull { it.id == partner }?.let { return listOf(p, it) }
            }
        }
        return listOf(p)
    }

    private suspend fun obtainGroup(group: List<PlacementEntity>): Slide {
        val aspects = group.map { aspect(it) ?: 1f }
        val split = splitCells()?.takeIf { group.size == 2 }
        val placed = if (split != null) Collage.placeInto(listOf(split.big, split.small), aspects, screenW, screenH) else Collage.place(aspects, screenW, screenH)
        val withLabels = settings.widgets.caption
        val ctx = app.kinframe.frame.Locales.wrap(g.app)
        val key = "group|" + group.joinToString(",") { "${it.id}:${it.localFile}:${it.focalX}:${it.focalY}:${it.rotation}:${it.caption}:${it.sharedByName}" } +
            "|$withLabels|${screenW}x$screenH|${if (withLabels) corner() else ""}|split=${split != null}:$splitSide"
        cache[key]?.let { return it }
        val tiles = placed.cells.mapIndexed { i, cell -> Tile(group[placed.order[i]], cell) }
        val requests = tiles.map { t ->
            val q = t.placement
            val dateMs = when (settings.slideshow.captionDate) { "taken" -> q.capturedAt ?: q.receivedAt; "none" -> null; else -> q.receivedAt }
            val meta = listOfNotNull(Credit.of(ctx, q), dateMs?.let { app.kinframe.frame.ui.Format.relativeDate(ctx, it, g.clock.nowMs(), g.clock.zone) })
                .joinToString(" · ").ifEmpty { null }
            SlideRenderer.TileRequest(
                g.media.file(q.localFile ?: ""), q.rotation, q.focalX, q.focalY, t.cell,
                label = meta.takeIf { withLabels }, caption = q.caption?.takeIf { withLabels && it.isNotBlank() },
            )
        }
        val slide = withContext(renderDispatcher) {
            val bmp = try {
                renderer.renderTiles(requests, screenW, screenH, g.app.resources.displayMetrics.density, corner())
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering collage"); cache.clear(); System.gc(); null
            }
            Slide(group.first(), bmp?.asImageBitmap()?.also { it.prepareToDraw() }, key, tiles = tiles, split = split != null)
        }
        cache[key] = slide
        // Previous, current and next (for swiping) plus the slide fading out: ~4 MB each at 1280×800.
        while (cache.size > 4) cache.remove(cache.keys.first())
        return slide
    }

    /** A tap on a collage tile makes that photo the one the quick menu acts on. */
    fun selectAt(xFraction: Float, yFraction: Float) {
        val tiles = _stage.value.incoming?.tiles ?: return
        tiles.firstOrNull { it.cell.contains(xFraction, yFraction) }?.let { _current.value = playable[it.placement.id] ?: it.placement }
    }

    private suspend fun markShown(p: PlacementEntity) {
        val first = p.firstShownAt == null
        withContext(Dispatchers.IO) {
            g.db.placements().markShown(p.id, g.clock.nowMs())
            if (first) c.session.value?.acks?.enqueueSeen(p.id)
        }
    }

    private suspend fun captionSpec(p: PlacementEntity): SlideRenderer.CaptionSpec? {
        val w = settings.widgets
        if (!w.caption) return null
        val name = p.senderName
        val ctx = app.kinframe.frame.Locales.wrap(g.app)
        val dateMs = when (settings.slideshow.captionDate) { "taken" -> p.capturedAt ?: p.receivedAt; "none" -> null; else -> p.receivedAt }
        val today = java.time.Instant.ofEpochMilli(g.clock.nowMs()).atZone(g.clock.zone).toLocalDate()
        val memory = if (settings.slideshow.onThisDay) OnThisDay.yearsAgo(p.capturedAt, today, g.clock.zone) else null
        val meta = buildList {
            memory?.let { add(ctx.resources.getQuantityString(app.kinframe.frame.R.plurals.on_this_day_years, it, it)) }
            Credit.of(ctx, p)?.let { add(it) }
            dateMs?.let { add(app.kinframe.frame.ui.Format.relativeDate(ctx, it, g.clock.nowMs(), g.clock.zone)) }
            if (p.captionEdited) add(ctx.getString(app.kinframe.frame.R.string.caption_edited))
        }.joinToString(" · ") + (p.reaction?.let { "   " + reactionEmoji(it) } ?: "")
        if (name == null && p.caption.isNullOrBlank()) return null
        val avatar = if (w.avatar && p.senderId != null && p.importedFrom == null) runCatching { Avatars.load(g, p.senderId, p.id) }.getOrNull() else null
        return SlideRenderer.CaptionSpec(
            text = p.caption?.takeIf { it.isNotBlank() },
            meta = meta,
            avatar = avatar,
            initials = if (w.avatar && name != null) Avatars.initials(name) else null,
            avatarColor = Avatars.color(p.senderId ?: name ?: ""),
            textScale = settings.display.textScale.toFloat().coerceIn(1f, 1.3f),
            corner = corner(),
        )
    }

    private suspend fun obtain(p: PlacementEntity): Slide {
        val caption = captionSpec(p)
        val separate = caption != null && !p.isVideo && settings.slideshow.transition == "kenburns"
        val key = renderKey(p) + "|$separate|" + caption?.let { "${it.text}|${it.meta}|${it.avatar != null}|${it.initials}|${it.textScale}|${it.corner}" }
        cache[key]?.let { return it }
        val slide = withContext(renderDispatcher) {
            val file = g.media.file(p.localFile ?: "")
            val request = SlideRenderer.Request(
                file, p.isVideo, screenW, screenH,
                fill = (p.fit ?: if (settings.slideshow.fill) "fill" else "fit") == "fill",
                focalX = p.focalX, focalY = p.focalY, rotation = p.rotation,
                blurBars = settings.slideshow.background == "blur",
                caption = caption,
                density = g.app.resources.displayMetrics.density,
                fillChosen = p.fit == "fill",
            )
            // Videos and live photos: the caption stays readable above the playing clip. Ken Burns: it stays still.
            val strip = if (p.isVideo || p.isLive || separate) runCatching { renderer.renderCaptionStrip(request) }.getOrNull() else null
            val bmp: Bitmap? = try {
                renderer.render(if (separate) request.copy(caption = null) else request)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering ${p.id}")
                cache.clear(); System.gc(); null
            } catch (e: Exception) {
                Log.w(TAG, "cannot render ${p.id}: ${e.message}")
                null
            }
            Slide(p, bmp?.asImageBitmap()?.also { it.prepareToDraw() }, key, strip?.asImageBitmap(), captionSeparate = separate && strip != null)
        }
        cache[key] = slide
        // Previous, current and next (for swiping) plus the slide fading out: ~4 MB each at 1280×800.
        while (cache.size > 4) cache.remove(cache.keys.first())
        return slide
    }

    private fun renderKey(p: PlacementEntity) =
        "${p.id}|${p.localFile}|${p.fit}|${p.focalX}|${p.focalY}|${p.rotation}|${settings.slideshow.fill}|${settings.slideshow.background}|${screenW}x$screenH"

    /** Items whose files must not be evicted: on screen now, fading out, or up next. */
    fun protectedIds(): Set<String> = buildSet {
        val st = _stage.value
        st.incoming?.ids?.let(::addAll)
        st.outgoing?.ids?.let(::addAll)
        addAll(playlist.upcoming(6))
        _prepared.value?.placement?.id?.let(::add)
        playlist.peekNext()?.let(::add)
        playlist.current?.let(::add)
    }

    /** The pre-rendered next slide; the UI draws it into a 1-px box so its texture is uploaded before the fade. */
    private val _prepared = MutableStateFlow<Slide?>(null)
    val prepared: StateFlow<Slide?> = _prepared

    /** The pre-rendered previous slide, shown beside the current one while swiping back. */
    private val _preparedPrevious = MutableStateFlow<Slide?>(null)
    val preparedPrevious: StateFlow<Slide?> = _preparedPrevious

    /**
     * Renders both swipe neighbours as soon as a transition has ended. The previous one is usually the photo that was
     * just on screen (still in the cache), and after going back the next one is too, so repeated swipes find them ready.
     */
    private fun prefetch() {
        val nextId = playlist.peekNext()
        if (nextId != null && nextId in onDemand) c.scope.launch { c.storage.requestOnDemand(nextId) }
        val next = nextId?.let { playable[it] }
        val prev = playlist.peekPrevious()?.let { playable[it] }
        c.scope.launch {
            prev?.let { pp -> runCatching { obtain(pp) }.getOrNull()?.let { _preparedPrevious.value = it } }
            next?.let { np -> runCatching { obtain(np) }.getOrNull()?.let { _prepared.value = it } }
            next?.let { prebufferLive(it) }
        }
    }

    private suspend fun rerenderCurrent(onlyIfChanged: Boolean = false) {
        // What is on screen is read under the lock: a slide being put together meanwhile (the first one after a start,
        // while the layout changes) is waited for, and re-rendered, instead of missed.
        navLock.withLock {
            val inc = _stage.value.incoming ?: return
            val p = playable[inc.placement.id] ?: return
            val group = inc.tiles.mapNotNull { playable[it.placement.id] }
            val slide = if (group.size > 1 && group.size == inc.tiles.size) obtainGroup(group) else obtain(p)
            val st = _stage.value
            if (onlyIfChanged && slide.key == st.incoming?.key) return@withLock
            if (_preview.value?.slideKey == st.incoming?.key && slide.key != st.incoming?.key) {
                // The previewed slide fades into the sharp one (the preview is cleared when that is done).
                transitionRunning = true
                _stage.value = Stage(st.incoming, slide, st.seq + 1, TransitionKind.Crossfade, ADJUST_FADE_MS, 1, st.holdMs)
            } else {
                _stage.value = Stage(null, slide, st.seq + 1, TransitionKind.Cut, 0, 1)
                transitionRunning = false
            }
        }
    }

    private val _preview = MutableStateFlow<AdjustPreview?>(null)
    val preview: StateFlow<AdjustPreview?> = _preview

    /** A quarter turn clockwise, shown at once: the turned screen fits inside the screen until the render lands. */
    fun previewRotate() {
        val inc = _stage.value.incoming?.takeIf { it.tiles.isEmpty() } ?: return
        val prev = _preview.value?.takeIf { it.slideKey == inc.key }
        val rotation = (prev?.rotation ?: 0f) + 90f
        val quarter = ((rotation / 90f).toInt() % 2) != 0
        val turnFit = if (screenW > 0 && screenH > 0) minOf(screenW.toFloat() / screenH, screenH.toFloat() / screenW) else 1f
        val fitScale = (prev?.scale ?: 1f) / (if (((prev?.rotation ?: 0f) / 90f).toInt() % 2 != 0) turnFit else 1f)
        _preview.value = AdjustPreview(inc.key, rotation, fitScale * (if (quarter) turnFit else 1f))
    }

    /**
     * Fill / whole photo, shown at once: the slide scales to the size the photo will have, so filling pushes the
     * bands out and the whole photo shrinks the crop to its final size, before the sharp render fades in.
     */
    fun previewFit(fill: Boolean) {
        val inc = _stage.value.incoming?.takeIf { it.tiles.isEmpty() } ?: return
        val a = aspect(inc.placement) ?: return
        if (screenW <= 0 || screenH <= 0) return
        val big = maxOf(screenW / a, screenH.toFloat())
        val small = minOf(screenW / a, screenH.toFloat())
        val prev = _preview.value?.takeIf { it.slideKey == inc.key }
        _preview.value = AdjustPreview(inc.key, prev?.rotation ?: 0f, (prev?.scale ?: 1f) * if (fill) big / small else small / big)
    }

    // ------------------------------------------------------------------ video

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(g.app).build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    videoEnded = true
                    if (_video.value?.live == true) finishLive()
                }
            }

            override fun onRenderedFirstFrame() {
                _video.value = _video.value?.copy(visible = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "video error: ${error.errorCodeName}")
                videoEnded = true
                prebufferedId = null
                _video.value = _video.value?.copy(visible = false)
                if (_video.value?.live == true) finishLive()
            }
        })
    }

    private fun startVideo(p: PlacementEntity) {
        val file = g.media.file(p.localFile ?: return)
        val pl = ensurePlayer()
        videoEnded = false
        _video.value = VideoState(p.id, file)
        pl.repeatMode = if (settings.video.playback == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        pl.volume = videoVolume()
        pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        pl.prepare()
        pl.playWhenReady = c.display.mode.value != ScreenMode.Asleep
        if (settings.video.playback == "loop") {
            // In loop mode a video counts as "ended" once the interval passed after one full play.
            c.scope.launch {
                val d = p.durationMs ?: 0L
                delay(d.coerceAtLeast(1_000))
                if (_video.value?.placementId == p.id) videoEnded = true
            }
        }
    }

    /**
     * A swipe starts ([hold]) or springs back. Dragging a playing TextureView costs the RK3126 a frame each time, so
     * the clip pauses and the slide's still (its first frame) follows the finger instead; it resumes on spring-back.
     */
    fun holdVideo(hold: Boolean) {
        val v = _video.value ?: return
        if (hold == v.held) return
        _video.value = v.copy(held = hold)
        if (hold) player?.pause() else if (!videoEnded && c.display.mode.value != ScreenMode.Asleep) player?.play()
    }

    private fun stopVideo() {
        _liveMark.value = null
        if (_video.value == null) return
        _video.value = null
        videoEnded = false
        prebufferedId = null
        player?.run { stop(); clearMediaItems() }
    }

    // ------------------------------------------------------------------ live photos

    /**
     * Loads the next slide's clip into the (single, reused) player while the current slide shows, so it starts
     * without a decode hiccup on the RK3126. Nothing is drawn: the video layer exists only while a clip plays.
     */
    private fun prebufferLive(p: PlacementEntity) {
        val file = liveClip(p) ?: return
        if (_video.value != null || !LivePolicy.prebuffer(settings.slideshow.livePhotos, true, _current.value?.isVideo == true)) return
        if (prebufferedId == p.id) return
        val pl = ensurePlayer()
        pl.repeatMode = Player.REPEAT_MODE_OFF
        pl.volume = 0f
        pl.playWhenReady = false
        pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        pl.prepare()
        prebufferedId = p.id
    }

    private fun startLive(p: PlacementEntity) {
        val file = liveClip(p) ?: return
        val pl = ensurePlayer()
        videoEnded = false
        _liveMark.value = null
        _video.value = VideoState(p.id, file, live = true)
        pl.repeatMode = Player.REPEAT_MODE_OFF
        if (prebufferedId == p.id && pl.mediaItemCount > 0) {
            pl.seekTo(0)
        } else {
            pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            pl.prepare()
        }
        prebufferedId = p.id
        pl.volume = playerVolume()
        pl.playWhenReady = c.display.mode.value != ScreenMode.Asleep
    }

    /** The clip ended: fade the video layer out over the still, then drop it; the interval starts now. */
    private fun finishLive() {
        val v = _video.value ?: return
        _video.value = v.copy(visible = false)
        c.scope.launch {
            delay(LIVE_FADE_MS + 50)
            if (_video.value?.placementId == v.placementId && _video.value?.live == true && videoEnded) {
                _video.value = null
                shownAt = SystemClock.elapsedRealtime()
                updateLiveMark()
            }
        }
    }

    /** Video volume; live clips are silent unless the sender kept their sound. */
    private fun playerVolume(): Float {
        val v = _video.value
        if (v != null && v.live && playable[v.placementId]?.motionHasAudio != true) return 0f
        if (v == null) return 0f
        return videoVolume()
    }

    fun setVolume(percent: Int) { if (_video.value?.live != true) player?.volume = percent.coerceIn(0, 100) / 100f }

    /**
     * The video volume now: the setting, or silence once nobody touched the frame for `auto_mute_min` minutes
     * (frameo-behaviour-spec B.2). Any touch brings the sound back.
     */
    private fun videoVolume(): Float {
        val v = settings.video
        val idleMs = SystemClock.elapsedRealtime() - c.display.lastTouchElapsed
        return if (VideoPolicy.muted(v.autoMuteMin, idleMs)) 0f else v.volume.coerceIn(0, 100) / 100f
    }

    private val _volumeHint = MutableStateFlow<Int?>(null)
    /** Shown briefly after a volume key press. */
    val volumeHint: StateFlow<Int?> = _volumeHint
    private var hintJob: Job? = null

    /** Volume keys (product spec 4.4): steps of 10 %, saved as the frame's video volume. */
    fun nudgeVolume(delta: Int) {
        val target = (settings.video.volume + delta).coerceIn(0, 100)
        c.display.onUserActivity()
        settings = settings.copy(video = settings.video.copy(volume = target))
        player?.volume = playerVolume()
        _volumeHint.value = target
        hintJob?.cancel()
        hintJob = c.scope.launch { delay(1_800); _volumeHint.value = null }
        c.scope.launch { c.session.value?.outbox?.settingsPatch(app.kinframe.core.api.MergePatch.field("video", "volume", target)) }
    }

    /** Power-off/restart: stop playback and release the decoder. */
    fun stopPlayback() {
        stopped = true
        stopVideo()
        player?.release()
        player = null
        timerJob?.cancel()
    }

    /** After revocation: forget everything. */
    fun reset() {
        stopVideo()
        cache.clear()
        _stage.value = Stage()
        _current.value = null
        playlist.update(emptyList(), PlayOrder.RECEIVED, false)
    }

    companion object {
        const val ADJUST_FADE_MS = 350
        private const val TAG = "KinSlideshow"
        /** Crossfade between a live photo's clip and its still. */
        const val LIVE_FADE_MS = 400L
    }
}
