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
)

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
            s.slideshow.captionDate != settings.slideshow.captionDate || s.display.textScale != settings.display.textScale
        val widgetsChanged = s.widgets != settings.widgets
        settings = s
        val skipped = g.crashGuard.skippedItems()
        val visible = all.filter { it.isVisible }
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
            (playable.values + evicted).map { PlaylistEntry(it.id, it.receivedAt, it.capturedAt, it.isNew) },
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
            cur != null && cur.id !in playable -> next(manual = false) // removed, hidden or trashed
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
            val due = if (_video.value != null) {
                when (settings.video.playback) {
                    "once_advance" -> videoEnded
                    else -> videoEnded && elapsed >= interval
                }
            } else elapsed >= interval
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
        val slide = obtain(p)
        stopVideo()
        val prev = _stage.value
        val duration = when (kind) {
            TransitionKind.Slide -> 450
            TransitionKind.Cut -> 0
            TransitionKind.Crossfade -> settings.slideshow.transitionMs.coerceIn(200, 3000)
        }
        transitionRunning = duration > 0
        _stage.value = Stage(prev.incoming, slide, prev.seq + 1, if (prev.incoming == null) TransitionKind.Crossfade else kind, duration, direction)
        _current.value = p
        shownAt = SystemClock.elapsedRealtime()
        g.prefs.playlistCurrent = id
        if (playlist.seed != g.prefs.playlistSeed) g.prefs.playlistSeed = playlist.seed
        _newCount.value = playlist.pendingNew().size
        markShown(p)
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
        val meta = buildList {
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
        st.incoming?.placement?.id?.let(::add)
        st.outgoing?.placement?.id?.let(::add)
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
        val id = _stage.value.incoming?.placement?.id ?: return
        val p = playable[id] ?: return
        navLock.withLock {
            val slide = obtain(p)
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
        pl.volume = (settings.video.volume.coerceIn(0, 100) / 100f)
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
