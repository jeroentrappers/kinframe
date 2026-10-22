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
    /** Videos only: the caption as a bottom strip, drawn above the playing video. */
    val captionStrip: ImageBitmap? = null,
    /** Collages and smart pairs: every photo and its cell ([placement] is the first one). Empty for one photo. */
    val tiles: List<Tile> = emptyList(),
    /** Ken Burns motion while this slide is on screen, or null. */
    val kenBurns: KenBurns.Params? = null,
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
)

sealed interface EmptyState {
    data object NoPhotos : EmptyState
    data object AllHidden : EmptyState
    data class Downloading(val total: Int) : EmptyState
}

data class VideoState(val placementId: String, val file: File, val visible: Boolean = false)

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

    /** Set by the UI: true while the quick menu, settings or a dialog is open (auto-advance pauses). */
    val paused = MutableStateFlow(false)

    private var playable: Map<String, PlacementEntity> = emptyMap()
    /** Visible items whose file was evicted to free space: in the playlist, downloaded again just before they show. */
    private var onDemand: Set<String> = emptySet()
    private var settings = FrameSettings.DEFAULT
    private var screenW = 0
    private var screenH = 0
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
                if (asleep) player?.pause() else if (_video.value != null && !videoEnded) player?.play()
            }
        }
    }

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
            player?.let { pl -> videoVolume().let { v -> if (pl.volume != v) pl.volume = v } }
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
            val due = if (_video.value != null) VideoPolicy.due(settings.video.playback, videoEnded, elapsed, interval) else elapsed >= interval
            if (due) next(manual = false)
        }
    }

    fun next(manual: Boolean, fade: Boolean = false) = c.scope.launch { navigate(+1, manual, fade) }
    fun previous(fade: Boolean = false) = c.scope.launch { navigate(-1, true, fade) }

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

    private suspend fun navigate(direction: Int, manual: Boolean, fade: Boolean = false) {
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
            present(id, direction, kind)
        }
    }

    private suspend fun present(id: String, direction: Int, kind: TransitionKind) {
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
        val duration = when (kind) {
            TransitionKind.Slide -> 450
            TransitionKind.Cut -> 0
            TransitionKind.Crossfade -> settings.slideshow.transitionMs.coerceIn(200, 3000)
        }
        transitionRunning = duration > 0
        val hold = settings.slideshow.intervalS.coerceIn(2, 86_400) * 1000L + duration
        _stage.value = Stage(prev.incoming, slide, prev.seq + 1, if (prev.incoming == null) TransitionKind.Crossfade else kind, duration, direction, hold)
        _current.value = p
        shownAt = SystemClock.elapsedRealtime()
        g.prefs.playlistCurrent = id
        if (playlist.seed != g.prefs.playlistSeed) g.prefs.playlistSeed = playlist.seed
        _newCount.value = playlist.pendingNew().size
        group.forEach { markShown(it) }
        if (duration == 0) onTransitionDone(_stage.value.seq)
        prefetch()
    }

    /** The UI reports the end of a transition; videos start only then. */
    fun onTransitionDone(seq: Long) {
        if (seq != _stage.value.seq) return
        transitionRunning = false
        shownAt = SystemClock.elapsedRealtime()
        val st = _stage.value
        if (st.outgoing != null) _stage.value = st.copy(outgoing = null)
        val p = st.incoming?.placement ?: return
        if (p.isVideo && settings.video.autoplay && st.incoming.image != null) startVideo(p)
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
        val ss = settings.slideshow
        val photos = playlist.upcoming(12).mapNotNull { playable[it] }.filter { !it.isVideo }
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
        val placed = Collage.place(aspects, screenW, screenH)
        val withLabels = settings.widgets.caption
        val ctx = app.kinframe.frame.Locales.wrap(g.app)
        val key = "group|" + group.joinToString(",") { "${it.id}:${it.localFile}:${it.focalX}:${it.focalY}:${it.rotation}:${it.caption}" } +
            "|$withLabels|${screenW}x$screenH"
        cache[key]?.let { return it }
        val tiles = placed.cells.mapIndexed { i, cell -> Tile(group[placed.order[i]], cell) }
        val requests = tiles.map { t ->
            val q = t.placement
            val dateMs = when (settings.slideshow.captionDate) { "taken" -> q.capturedAt ?: q.receivedAt; "none" -> null; else -> q.receivedAt }
            val meta = listOfNotNull(q.senderName, dateMs?.let { app.kinframe.frame.ui.Format.relativeDate(ctx, it, g.clock.nowMs(), g.clock.zone) })
                .joinToString(" · ").ifEmpty { null }
            SlideRenderer.TileRequest(
                g.media.file(q.localFile ?: ""), q.rotation, q.focalX, q.focalY, t.cell,
                label = meta.takeIf { withLabels }, caption = q.caption?.takeIf { withLabels && it.isNotBlank() },
            )
        }
        val slide = withContext(renderDispatcher) {
            val bmp = try {
                renderer.renderTiles(requests, screenW, screenH, g.app.resources.displayMetrics.density)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering collage"); cache.clear(); System.gc(); null
            }
            Slide(group.first(), bmp?.asImageBitmap()?.also { it.prepareToDraw() }, key, tiles = tiles)
        }
        cache[key] = slide
        while (cache.size > 3) cache.remove(cache.keys.first())
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
            name?.let { add(it) }
            dateMs?.let { add(app.kinframe.frame.ui.Format.relativeDate(ctx, it, g.clock.nowMs(), g.clock.zone)) }
            if (p.captionEdited) add(ctx.getString(app.kinframe.frame.R.string.caption_edited))
        }.joinToString(" · ") + (p.reaction?.let { "   " + reactionEmoji(it) } ?: "")
        if (name == null && p.caption.isNullOrBlank()) return null
        val avatar = if (w.avatar && p.senderId != null) runCatching { Avatars.load(g, p.senderId, p.id) }.getOrNull() else null
        return SlideRenderer.CaptionSpec(
            text = p.caption?.takeIf { it.isNotBlank() },
            meta = meta,
            avatar = avatar,
            initials = if (w.avatar && name != null) Avatars.initials(name) else null,
            avatarColor = Avatars.color(p.senderId ?: name ?: ""),
            textScale = settings.display.textScale.toFloat().coerceIn(1f, 1.3f),
            rightInsetDp = if (w.clock || w.date) 340f else 36f,
        )
    }

    private suspend fun obtain(p: PlacementEntity): Slide {
        val caption = captionSpec(p)
        val key = renderKey(p) + "|" + caption?.let { "${it.text}|${it.meta}|${it.avatar != null}|${it.initials}|${it.textScale}|${it.rightInsetDp}" }
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
            )
            val strip = if (p.isVideo) runCatching { renderer.renderCaptionStrip(request) }.getOrNull() else null
            val bmp: Bitmap? = try {
                renderer.render(request)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering ${p.id}")
                cache.clear(); System.gc(); null
            } catch (e: Exception) {
                Log.w(TAG, "cannot render ${p.id}: ${e.message}")
                null
            }
            Slide(p, bmp?.asImageBitmap()?.also { it.prepareToDraw() }, key, strip?.asImageBitmap())
        }
        cache[key] = slide
        while (cache.size > 3) cache.remove(cache.keys.first())
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

    private fun prefetch() {
        val nextId = playlist.peekNext() ?: return
        if (nextId in onDemand) c.scope.launch { c.storage.requestOnDemand(nextId) }
        val p = playable[nextId] ?: return
        c.scope.launch {
            delay(1_200) // after the running transition
            runCatching { obtain(p) }.getOrNull()?.let { _prepared.value = it }
        }
    }

    private suspend fun rerenderCurrent() {
        val inc = _stage.value.incoming ?: return
        val p = playable[inc.placement.id] ?: return
        val group = inc.tiles.mapNotNull { playable[it.placement.id] }
        navLock.withLock {
            val slide = if (group.size > 1 && group.size == inc.tiles.size) obtainGroup(group) else obtain(p)
            val st = _stage.value
            _stage.value = Stage(null, slide, st.seq + 1, TransitionKind.Cut, 0, 1)
            transitionRunning = false
        }
    }

    // ------------------------------------------------------------------ video

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(g.app).build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) videoEnded = true
            }

            override fun onRenderedFirstFrame() {
                _video.value = _video.value?.copy(visible = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "video error: ${error.errorCodeName}")
                videoEnded = true
                _video.value = _video.value?.copy(visible = false)
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

    private fun stopVideo() {
        if (_video.value == null) return
        _video.value = null
        videoEnded = false
        player?.run { stop(); clearMediaItems() }
    }

    fun setVolume(percent: Int) { player?.volume = percent.coerceIn(0, 100) / 100f }

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
        player?.volume = videoVolume()
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

    companion object { private const val TAG = "KinSlideshow" }
}
