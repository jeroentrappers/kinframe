package app.kinframe.frame.slideshow

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.kinframe.frame.calendar.CalendarIcons
import app.kinframe.frame.calendar.CalendarLayer
import app.kinframe.frame.calendar.CalendarSidebar
import app.kinframe.frame.calendar.CountdownWidgets
import app.kinframe.frame.calendar.InvitationsPill
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.ConfigurationCompat
import app.kinframe.core.api.FrameCode
import app.kinframe.core.api.Reaction
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinHeading
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.core.design.KinShapeM
import app.kinframe.core.design.StatusPill
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.join.JoinRequestLayer
import app.kinframe.frame.join.JoinRequestsPill
import app.kinframe.frame.join.NotificationsPanel
import app.kinframe.frame.join.rememberJoinAvatars
import app.kinframe.frame.schedule.ScreenMode
import app.kinframe.frame.sync.FrameSession
import app.kinframe.frame.ui.Format
import app.kinframe.frame.ui.FrameStatus
import app.kinframe.frame.ui.FrameStatusCard
import app.kinframe.frame.ui.FrameStatuses
import app.kinframe.frame.ui.PowerDialog
import app.kinframe.frame.ui.QrCode
import app.kinframe.frame.ui.SettingsScreen
import app.kinframe.frame.weather.ForecastOverlay
import app.kinframe.frame.weather.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SlideshowScreen(c: FrameController) {
    val ss = c.slideshow
    LaunchedEffect(Unit) { ss.start() }
    val stage by ss.stage.collectAsState()
    val empty by ss.empty.collectAsState()
    val settings by c.g.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var menuTouch by remember { mutableLongStateOf(0L) }
    var powerOpen by remember { mutableStateOf(false) }
    val settingsOpen by c.settingsOpen.collectAsState()
    var burst by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var burstSeq by remember { mutableIntStateOf(0) }
    var snack by remember { mutableStateOf<Snack?>(null) }
    var forecastOpen by remember { mutableStateOf(false) }
    var notificationsOpen by remember { mutableStateOf(false) }
    val joinPending by c.joins.pending.collectAsState()
    val joinFocused by c.joins.focused.collectAsState()
    val sync by c.syncState.collectAsState()
    val nowMs by minuteTicker(c)
    val weatherDoc by c.weather.collectAsState()
    // Shown only when enabled, located and fresh (> 6 h old means the frame has been offline a long while).
    val weather = weatherDoc?.takeIf { settings.widgets.weather && settings.location != null && !it.isStale(nowMs) }
    LaunchedEffect(weather == null) { if (weather == null) forecastOpen = false }

    val joinCard = joinFocused != null
    // Calendar (product spec 4.9): full-screen view, details, invitation card, hybrid sidebar and countdowns.
    val calendarOpen by c.calendar.open.collectAsState()
    val calendarData by c.calendar.data.collectAsState()
    val eventDetail by c.calendar.detail.collectAsState()
    val invitation by c.calendar.focused.collectAsState()
    val invitations by c.calendar.invitations.collectAsState()
    val calendarBusy = calendarOpen || eventDetail != null || invitation != null
    val storageOpen by c.removable.panelOpen.collectAsState()
    // The calendar shows the forecast whenever the frame has fresh weather.
    val calendarWeather = weatherDoc?.takeIf { settings.location != null && !it.isStale(nowMs) }
    LaunchedEffect(menuOpen, powerOpen, settingsOpen, forecastOpen, notificationsOpen, joinCard, calendarBusy, storageOpen) {
        ss.paused.value = menuOpen || powerOpen || settingsOpen || forecastOpen || notificationsOpen || joinCard || calendarBusy || storageOpen
    }
    // Quick menu auto-hides after 15 s without interaction (product spec §6).
    LaunchedEffect(menuOpen, menuTouch) { if (menuOpen) { delay(15_000); menuOpen = false } }

    val react: (String?, Offset?) -> Unit = { r, at ->
        val p = ss.current.value
        if (p != null) {
            scope.launch { c.session.value?.outbox?.reaction(p.id, r) }
            if (r != null) { burst = r to (at ?: Offset.Unspecified); burstSeq++ }
        }
    }

    // No full-screen background here: on the Mali-400 every full-screen fill costs fill rate during the fade.
    // The slides are opaque; black is drawn only when there is nothing else.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Hybrid view: the calendar takes a side panel (landscape) or a bottom panel (portrait) and the slideshow
        // simply renders smaller beside it; nothing is blended over the photo.
        val landscape = maxWidth > maxHeight
        val hybrid = settings.widgets.calendarSidebar && calendarData != null
        val panel = when {
            !hybrid -> 0.dp
            landscape -> (maxWidth * 0.3f).coerceIn(340.dp, 420.dp)
            else -> (maxHeight * 0.28f).coerceIn(300.dp, 380.dp)
        }
        val photoW = if (landscape) maxWidth - panel else maxWidth
        val photoH = if (landscape) maxHeight else maxHeight - panel
        val density = LocalDensity.current
        Box(Modifier.size(photoW, photoH)) {
        if (stage.incoming == null && empty == null) Box(Modifier.fillMaxSize().background(Color.Black))
        val wPx = with(density) { photoW.roundToPx() }
        val hPx = with(density) { photoH.roundToPx() }
        LaunchedEffect(wPx, hPx) { ss.setScreenSize(wPx, hPx) }

        // A fresh Animatable per stage, created in the same composition that introduces the incoming slide, so its very
        // first frame already draws progress 0. (Snapping to 0 inside the LaunchedEffect ran a frame too late: the new
        // photo flashed fully opaque, then jumped back and faded in.)
        val animates = stage.incoming != null && stage.durationMs > 0
        val progress = remember(stage.seq) { Animatable(if (animates) 0f else 1f) }
        LaunchedEffect(stage.seq) {
            if (animates) {
                progress.animateTo(1f, tween(stage.durationMs, easing = if (stage.kind == TransitionKind.Slide) FastOutSlowInEasing else LinearOutSlowInEasing))
            }
            ss.onTransitionDone(stage.seq)
        }
        val drag = remember { Animatable(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInputTaps(
                    onTap = { pos ->
                        if (!menuOpen) {
                            ss.selectAt(pos.x / wPx, pos.y / hPx)
                            // A live photo plays its clip again; the menu opens as usual.
                            ss.replayLive()
                        }
                        menuOpen = !menuOpen; menuTouch = System.currentTimeMillis()
                    },
                    onDoubleTap = { pos -> if (ss.current.value != null) react(Reaction.LOVE, pos) },
                )
                .pointerInputSwipe(
                    onDrag = { dx -> scope.launch { drag.snapTo(drag.value + dx) } },
                    onEnd = {
                        val dx = drag.value
                        scope.launch { drag.animateTo(0f, tween(250)) }
                        if (abs(dx) > wPx * 0.12f) { if (dx < 0) ss.next(manual = true) else ss.previous() }
                    },
                ),
        ) {
            val dir = stage.direction
            // One call site for both layers, so a slide keeps its state (its Ken Burns motion) when it becomes the
            // outgoing one and fades out from where it was.
            listOfNotNull(stage.outgoing?.let { it to false }, stage.incoming?.let { it to true }).forEach { (slide, incoming) ->
                key(slide.key) {
                    val kb = slide.kenBurns
                    val motion = remember { Animatable(0f) }
                    if (kb != null) {
                        val hold = stage.holdMs
                        LaunchedEffect(Unit) { motion.animateTo(1f, tween(hold.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing)) }
                    }
                    SlideLayer(slide) {
                        if (incoming) {
                            when (stage.kind) {
                                TransitionKind.Slide -> translationX = dir * wPx * (1 - progress.value) + drag.value * 0.35f
                                TransitionKind.Crossfade -> { alpha = progress.value; translationX = drag.value * 0.35f }
                                TransitionKind.Cut -> translationX = drag.value * 0.35f
                            }
                        } else if (stage.kind == TransitionKind.Slide) {
                            translationX = -dir * wPx * progress.value
                        }
                        if (kb != null) {
                            // One scaled textured quad per frame: within the Mali-400 budget (see KenBurns).
                            val t = motion.value
                            scaleX = kb.scaleAt(t); scaleY = kb.scaleAt(t)
                            translationX += kb.xAt(t) * wPx
                            translationY = kb.yAt(t) * hPx
                        }
                    }
                    if (incoming) VideoLayer(c, slide, wPx, hPx)
                }
            }
            TextureWarmup(c)
            if (stage.incoming == null) empty?.let { EmptyStateView(c, it) }
        }

        ClockWidget(
            c, nowMs, settings.widgets.clock, settings.widgets.date, settings.widgets.clock24h, weather, settings.locale.temperatureUnit,
            onPaper = stage.incoming == null && empty != null,
            onWeatherClick = { menuOpen = false; forecastOpen = true },
            onClockClick = { menuOpen = false; c.calendar.openCalendar() },
        )
        if (settings.widgets.countdowns && !menuOpen) {
            val items = remember(calendarData, nowMs) { calendarData?.upcomingCountdowns(nowMs).orEmpty() }
            val paper = stage.incoming == null && empty != null
            CountdownWidgets(
                items, java.time.Instant.ofEpochMilli(nowMs).atZone(c.calendar.zone).toLocalDate(),
                onPaper = paper, onClick = { c.calendar.openCalendar() },
                // Top corner on photos; on the paper empty state the clock has the top corner.
                modifier = if (paper) Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp, end = 40.dp)
                else Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp),
            )
        }
        }
        if (hybrid) {
            CalendarSidebar(
                calendarData, calendarWeather, nowMs, c.calendar.zone, settings.widgets.clock24h, settings.locale.temperatureUnit, landscape,
                onOpen = { c.calendar.openCalendar() }, onEvent = { c.calendar.showEvent(it) },
                modifier = if (landscape) Modifier.align(Alignment.CenterEnd).width(panel).fillMaxHeight() else Modifier.align(Alignment.BottomCenter).height(panel).fillMaxWidth(),
            )
        }
        NewPhotosPill(c, Modifier.align(Alignment.TopCenter))
        LiveMark(c, hidden = menuOpen, Modifier.align(Alignment.TopEnd))
        VolumePill(c, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
        Column(Modifier.align(Alignment.TopStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusArea(c, hidden = menuOpen || joinCard)
            app.kinframe.frame.removable.RemovableSlideshowCards(c, hidden = menuOpen || joinCard || settingsOpen)
            JoinRequestsPill(if (menuOpen || joinCard) emptyList() else joinPending, Modifier) { notificationsOpen = true }
            InvitationsPill(if (menuOpen || calendarBusy || joinCard) emptyList() else invitations, Modifier) { c.calendar.reopen() }
        }

        burst?.let { (r, at) -> key(burstSeq) { ReactionBurst(r, at) { burst = null } } }

        QuickMenu(
            c, visible = menuOpen, compact = maxWidth < 900.dp,
            onTouch = { menuTouch = System.currentTimeMillis() },
            onClose = { menuOpen = false },
            onReact = { r -> menuTouch = System.currentTimeMillis(); react(r, null) },
            onHide = { p ->
                menuOpen = false
                scope.launch {
                    val id = c.session.value?.outbox?.placementPatch(p.id, hidden = true, delayMs = UNDO_MS) ?: return@launch
                    snack = Snack(R.string.snack_hidden, p.id, id, hidden = true)
                }
            },
            onDelete = { p ->
                menuOpen = false
                scope.launch {
                    val id = c.session.value?.outbox?.placementPatch(p.id, trashed = true, delayMs = UNDO_MS) ?: return@launch
                    snack = Snack(R.string.snack_deleted, p.id, id, hidden = false)
                }
            },
            onPower = { menuOpen = false; powerOpen = true },
            onSettings = { menuOpen = false; c.openSettings() },
            onCalendar = { menuOpen = false; c.calendar.openCalendar() },
            notificationCount = joinPending.size,
            onNotifications = { menuOpen = false; notificationsOpen = true },
        )

        snack?.let { s ->
            key(s) {
                UndoSnackbar(s, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), onUndo = {
                    snack = null
                    scope.launch {
                        val outbox = c.session.value?.outbox ?: return@launch
                        if (s.hidden) outbox.cancel(s.outboxId, s.placementId, restoreHidden = false)
                        else outbox.cancel(s.outboxId, s.placementId, restoreTrashed = false)
                        ss.showWhenAvailable(s.placementId)
                    }
                }, onTimeout = { snack = null })
            }
        }

        ForecastOverlay(
            visible = forecastOpen, weather = weather, nowMs = nowMs, unit = settings.locale.temperatureUnit,
            h24 = settings.widgets.clock24h, onClose = { forecastOpen = false },
        )

        NotificationsPanel(
            visible = notificationsOpen, items = joinPending, avatars = rememberJoinAvatars(c), nowMs = nowMs,
            onAllow = { c.joins.decide(it, approve = true) }, onDeny = { c.joins.decide(it, approve = false) },
            onClose = { notificationsOpen = false },
        )
        JoinRequestLayer(c, sync?.frameName ?: stringResource(R.string.frame_default_name))
        CalendarLayer(c, nowMs, calendarWeather)

        if (powerOpen) PowerDialog(c, onDismiss = { powerOpen = false })

        AnimatedVisibility(settingsOpen, enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 }, exit = fadeOut(tween(200))) {
            SettingsScreen(c, onClose = { c.closeSettings() })
        }
        app.kinframe.frame.removable.RemovableHost(c)

        IdentifyOverlay(c)
        SleepOverlay(c)
    }
}

const val UNDO_MS = 6_500L

data class Snack(val text: Int, val placementId: String, val outboxId: Long, val hidden: Boolean)

private fun Modifier.pointerInputTaps(onTap: (Offset) -> Unit, onDoubleTap: (Offset) -> Unit): Modifier =
    pointerInput("taps") { detectTapGestures(onTap = { onTap(it) }, onDoubleTap = { onDoubleTap(it) }) }

private fun Modifier.pointerInputSwipe(onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput("swipe") {
        detectHorizontalDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, dx -> change.consume(); onDrag(dx) }
    }

// ---------------------------------------------------------------------------------------------- layers

/** Draws the prepared next slide into a 1 × 1 px box so HWUI uploads its texture before the transition starts. */
@Composable
private fun TextureWarmup(c: FrameController) {
    val prepared by c.slideshow.prepared.collectAsState()
    val img = prepared?.image ?: return
    Canvas(Modifier.size(1.dp)) {
        drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(1, 1))
    }
}

@Composable
private fun SlideLayer(slide: Slide, layer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) {
    val img = slide.image
    if (img != null) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                layer()
            },
        ) { drawImage(img) }
    } else {
        Box(Modifier.fillMaxSize().graphicsLayer(layer).background(Color(0xFF1C1714)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KinIcon(KinIcons.Image, null, tint = Color.White.copy(alpha = 0.6f), size = 56.dp)
                Text(
                    stringResource(if (slide.placement.isVideo) R.string.unable_video else R.string.unable_photo),
                    style = Kin.type.body, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoLayer(c: FrameController, slide: Slide, wPx: Int, hPx: Int) {
    val p = slide.placement
    val video by c.slideshow.video.collectAsState()
    val v = video
    if (v == null || v.placementId != p.id) return
    val alpha by animateFloatAsState(if (v.visible) 1f else 0f, tween(if (v.live) SlideshowController.LIVE_FADE_MS.toInt() else 250), label = "video")
    val settings by c.g.settings.settings.collectAsState()
    val vw = p.width ?: wPx
    val vh = p.height ?: hPx
    val fill = (p.fit ?: if (settings.slideshow.fill) "fill" else "fit") == "fill" && !FocalCrop.cropTooAggressive(vw, vh, wPx, hPx)
    val scale = if (fill) maxOf(wPx.toFloat() / vw, hPx.toFloat() / vh) else minOf(wPx.toFloat() / vw, hPx.toFloat() / vh)
    val dw = (vw * scale).roundToInt()
    val dh = (vh * scale).roundToInt()
    val left = if (fill) (wPx / 2f - (p.focalX ?: 0.5).toFloat() * dw).coerceIn((wPx - dw).toFloat(), 0f) else (wPx - dw) / 2f
    val top = if (fill) (hPx / 2f - (p.focalY ?: 0.5).toFloat() * dh).coerceIn((hPx - dh).toFloat(), 0f) else (hPx - dh) / 2f
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape)) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { tv -> c.slideshow.player?.setVideoTextureView(tv) } },
            update = { tv -> c.slideshow.player?.setVideoTextureView(tv) },
            onRelease = { tv -> c.slideshow.player?.clearVideoTextureView(tv) },
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(d) { dw.toDp() }, with(d) { dh.toDp() })
                .graphicsLayer { this.alpha = alpha },
        )
        slide.captionStrip?.let { strip ->
            Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                drawImage(strip, topLeft = Offset(0f, (hPx - strip.height).toFloat()))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- widgets

fun reactionEmoji(r: String) = when (r) {
    Reaction.LOVE -> "❤️"
    Reaction.LAUGH -> "😂"
    Reaction.WOW -> "😮"
    Reaction.SAD -> "😢"
    else -> ""
}

/** The trusted time, updated on every minute boundary (clock, date and weather staleness). */
@Composable
private fun minuteTicker(c: FrameController): androidx.compose.runtime.State<Long> {
    val now = remember { mutableLongStateOf(c.g.clock.nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = c.g.clock.nowMs()
            delay(60_000 - now.longValue % 60_000 + 50)
        }
    }
    return now
}

@Composable
private fun ClockWidget(
    c: FrameController,
    now: Long,
    showClock: Boolean,
    showDate: Boolean,
    h24: Boolean,
    weather: Weather?,
    unit: String,
    onPaper: Boolean,
    onWeatherClick: () -> Unit,
    onClockClick: () -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(androidx.compose.ui.platform.LocalConfiguration.current)[0] ?: Locale.ROOT
    CornerWidgets(
        clock = if (showClock) Format.clock(now, c.g.clock.zone, h24, locale) else null,
        date = if (showDate) Format.longDate(now, c.g.clock.zone, locale) else null,
        weather = weather, nowMs = now, unit = unit, onPaper = onPaper, onClockClick = onClockClick, onWeatherClick = onWeatherClick,
    )
}

@Composable
private fun VolumePill(c: FrameController, modifier: Modifier) {
    val v by c.slideshow.volumeHint.collectAsState()
    AnimatedVisibility(v != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val pct = v ?: 0
        Row(
            Modifier.clip(CircleShape).background(Color(0xE61C1714)).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (pct == 0) "\uD83D\uDD07" else "\uD83D\uDD0A", fontSize = 22.sp)
            Box(Modifier.padding(start = 14.dp).size(220.dp, 8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).clip(CircleShape).background(Color.White))
            }
            Text("$pct%", style = Kin.type.label, color = Color.White, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** The small "Live" mark on a live photo while its still shows (hidden while the clip plays). */
@Composable
private fun LiveMark(c: FrameController, hidden: Boolean, modifier: Modifier) {
    val mark by c.slideshow.liveMark.collectAsState()
    val m = mark
    AnimatedVisibility(m != null && !hidden, modifier.padding(24.dp), enter = fadeIn(), exit = fadeOut()) {
        LiveMarkContent(tapHint = m == true)
    }
}

@Composable
internal fun LiveMarkContent(tapHint: Boolean) {
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(KinIcons.Play, null, tint = Color.White, size = 18.dp)
        Text(
            stringResource(if (tapHint) R.string.live_tap_hint else R.string.live_badge), style = Kin.type.label, color = Color.White,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun NewPhotosPill(c: FrameController, modifier: Modifier) {
    val n by c.slideshow.newCount.collectAsState()
    AnimatedVisibility(n > 0, modifier.padding(top = 24.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        NewPhotosPillContent(n) { c.slideshow.showNew() }
    }
}

@Composable
internal fun NewPhotosPillContent(n: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Kin.colors.accent).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(KinIcons.Image, null, tint = Color.White, size = 22.dp)
        Text(pluralStringResource(R.plurals.new_photos, n, n), style = Kin.type.label, color = Color.White, modifier = Modifier.padding(start = 10.dp))
    }
}

/** One calm, dismissible status card (offline, server unreachable, captive portal, storage full). */
@Composable
private fun StatusArea(c: FrameController, hidden: Boolean) {
    val status by c.frameStatus.collectAsState()
    var shown by remember { mutableStateOf<FrameStatus?>(null) }
    LaunchedEffect(status) {
        shown = null
        delay(FrameStatuses.delayMs(status))
        shown = status
    }
    var dismissed by remember { mutableStateOf<Pair<FrameStatus, Long>?>(null) }
    val now = System.currentTimeMillis()
    val visible = shown?.takeIf { s ->
        val d = dismissed
        d == null || d.first != s || (s == FrameStatus.StorageCritical && now - d.second > FrameStatuses.STORAGE_SNOOZE_MS)
    }
    FrameStatusCard(
        if (hidden) null else visible,
        onDismiss = { dismissed = shown?.let { it to System.currentTimeMillis() } },
        onAction = { c.portalOpen.value = true },
    )
}

@Composable
private fun ReactionBurst(reaction: String, at: Offset, onDone: () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing)); onDone() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val center = if (at == Offset.Unspecified) Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f) else at
        val p = anim.value
        val scale = if (p < 0.3f) 0.4f + p / 0.3f * 0.9f else 1.3f - (p - 0.3f) * 0.2f
        val alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
        val sizePx = with(d) { 160.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset((center.x - sizePx / 2).roundToInt(), (center.y - sizePx / 2 - p * 80).roundToInt()) }
                .size(160.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
            contentAlignment = Alignment.Center,
        ) {
            if (reaction == Reaction.LOVE) KinIcon(KinIcons.HeartFilled, null, tint = Kin.colors.love, size = 140.dp)
            else Text(reactionEmoji(reaction), fontSize = 110.sp)
        }
    }
}

@Composable
internal fun UndoSnackbar(s: Snack, modifier: Modifier, onUndo: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(Unit) { delay(UNDO_MS - 300); onTimeout() }
    Row(
        modifier.clip(CircleShape).background(Color(0xF02E2621)).padding(start = 28.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(s.text), style = Kin.type.body, color = Color.White)
        Spacer(Modifier.size(20.dp))
        Row(
            Modifier.clip(CircleShape).clickable(onClick = onUndo).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KinIcon(KinIcons.Undo, null, tint = Kin.colors.accentSoft, size = 22.dp)
            Text(stringResource(R.string.action_undo), style = Kin.type.label, color = Kin.colors.accentSoft, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun IdentifyOverlay(c: FrameController) {
    val name by c.identify.collectAsState()
    AnimatedVisibility(name != null, enter = fadeIn(tween(400)), exit = fadeOut(tween(600))) {
        val pulse = remember { Animatable(0f) }
        LaunchedEffect(Unit) { while (true) { pulse.snapTo(0f); pulse.animateTo(1f, tween(1400)) } }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            val accent = Kin.colors.accent
            Canvas(Modifier.size(420.dp)) {
                drawCircle(accent.copy(alpha = 1f - pulse.value), radius = size.minDimension / 2 * (0.4f + 0.6f * pulse.value), style = androidx.compose.ui.graphics.drawscope.Stroke(6f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.identify_this_is), style = Kin.type.title, color = Color.White.copy(alpha = 0.8f))
                Text(name ?: "", style = Kin.type.display.copy(color = Color.White, fontWeight = FontWeight.Normal), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SleepOverlay(c: FrameController) {
    val mode by c.display.mode.collectAsState()
    val dark = mode == ScreenMode.FadingOut || mode == ScreenMode.Asleep
    val a by animateFloatAsState(if (dark) 1f else 0f, tween(if (dark) 1500 else 600), label = "sleep")
    if (a > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Color.Black))
}

// ---------------------------------------------------------------------------------------------- quick menu

@Composable
private fun QuickMenu(
    c: FrameController,
    visible: Boolean,
    compact: Boolean,
    onTouch: () -> Unit,
    onClose: () -> Unit,
    onReact: (String?) -> Unit,
    onHide: (PlacementEntity) -> Unit,
    onDelete: (PlacementEntity) -> Unit,
    onPower: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit,
    notificationCount: Int,
    onNotifications: () -> Unit,
) {
    val current by c.slideshow.current.collectAsState()
    val conn by c.connection.collectAsState()
    val net by c.g.network.status.collectAsState()
    val sync by c.syncState.collectAsState()
    val p = current
    QuickMenuContent(
        visible = visible,
        compact = compact,
        frameName = sync?.frameName ?: stringResource(R.string.frame_default_name),
        status = when {
            !net.connected -> stringResource(R.string.status_offline)
            conn == FrameSession.Connection.Online -> stringResource(R.string.status_online, net.ssid ?: stringResource(R.string.wifi_network))
            else -> stringResource(R.string.status_server_unreachable)
        },
        wifiIcon = if (net.connected) (if (net.level >= 2) KinIcons.Wifi else KinIcons.WifiWeak) else KinIcons.WifiOff,
        hasPhoto = p != null,
        reaction = p?.reaction,
        onTouch = onTouch,
        onReact = onReact,
        onHide = { p?.let(onHide) },
        onDelete = { p?.let(onDelete) },
        onPower = onPower,
        onSettings = onSettings,
        onCalendar = onCalendar,
        notificationCount = notificationCount,
        onNotifications = onNotifications,
    )
}

/** The quick menu's bars without the controller (also rendered by the screenshot tests). */
@Composable
internal fun QuickMenuContent(
    visible: Boolean,
    compact: Boolean,
    frameName: String,
    status: String,
    wifiIcon: androidx.compose.ui.graphics.vector.ImageVector,
    hasPhoto: Boolean,
    reaction: String?,
    onTouch: () -> Unit,
    onReact: (String?) -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onPower: () -> Unit,
    onSettings: () -> Unit,
    onCalendar: () -> Unit = {},
    notificationCount: Int = 0,
    onNotifications: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { -it / 2 }) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .padding(start = 36.dp, end = 28.dp, top = 24.dp, bottom = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(frameName, style = Kin.type.title, color = Color.White, maxLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        KinIcon(wifiIcon, null, tint = Color.White.copy(alpha = 0.85f), size = 20.dp)
                        Text(
                            status,
                            style = Kin.type.caption, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                RoundAction(CalendarIcons.Calendar, stringResource(R.string.menu_calendar)) { onTouch(); onCalendar() }
                Spacer(Modifier.size(14.dp))
                Box {
                    RoundAction(KinIcons.Bell, stringResource(R.string.menu_notifications)) { onTouch(); onNotifications() }
                    if (notificationCount > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(Kin.colors.accent),
                            contentAlignment = Alignment.Center,
                        ) { Text("$notificationCount", style = Kin.type.caption, color = Color.White) }
                    }
                }
                Spacer(Modifier.size(14.dp))
                RoundAction(KinIcons.Settings, stringResource(R.string.menu_settings)) { onTouch(); onSettings() }
                Spacer(Modifier.size(14.dp))
                RoundAction(KinIcons.Power, stringResource(R.string.menu_power)) { onTouch(); onPower() }
            }
        }
        AnimatedVisibility(visible && hasPhoto, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it / 2 }) {
            run {
                Row(
                    // Float above the caption + sender line (drawn into the bottom of the slide) instead of covering it.
                    Modifier.padding(bottom = 112.dp).clip(KinShapeL).background(Color(0xE61C1714)).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Reaction.all.forEach { r ->
                        val selected = reaction == r
                        Box(
                            Modifier.size(64.dp).clip(CircleShape)
                                .background(if (selected) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.1f))
                                .clickable { onReact(if (selected) null else r) },
                            contentAlignment = Alignment.Center,
                        ) { Text(reactionEmoji(r), fontSize = 30.sp) }
                    }
                    Box(Modifier.size(1.dp, 48.dp).background(Color.White.copy(alpha = 0.2f)))
                    PillAction(KinIcons.EyeOff, stringResource(R.string.menu_hide), compact) { onHide() }
                    PillAction(KinIcons.Trash, stringResource(R.string.menu_delete), compact) { onDelete() }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KinIcon(icon, label, tint = Color.White) }
}

@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick)
            .padding(horizontal = if (compact) 20.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(icon, if (compact) label else null, tint = Color.White, size = 24.dp)
        if (!compact) Text(label, style = Kin.type.label, color = Color.White, modifier = Modifier.padding(start = 10.dp))
    }
}

// ---------------------------------------------------------------------------------------------- empty states

@Composable
private fun EmptyStateView(c: FrameController, state: EmptyState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        val landscape = maxWidth > maxHeight
        val message = @Composable { EmptyMessage(state) }
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Box(Modifier.weight(1f)) { message() }
                if (state == EmptyState.NoPhotos) EmptyCodeCard(c)
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 120.dp),
                verticalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                message()
                if (state == EmptyState.NoPhotos) EmptyCodeCard(c)
            }
        }
    }
}

@Composable
internal fun EmptyMessage(state: EmptyState) {
    Column(Modifier.widthIn(max = 560.dp)) {
        EmptyIllustration()
        Spacer(Modifier.height(36.dp))
        when (state) {
            EmptyState.NoPhotos -> KinHeading(stringResource(R.string.empty_title), stringResource(R.string.empty_body))
            EmptyState.AllHidden -> KinHeading(stringResource(R.string.empty_hidden_title), stringResource(R.string.empty_hidden_body))
            is EmptyState.Downloading -> {
                KinHeading(stringResource(R.string.empty_downloading_title), pluralStringResource(R.plurals.empty_downloading_body, state.total, state.total))
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(Modifier.size(28.dp), color = Kin.colors.accent, strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun EmptyCodeCard(c: FrameController) {
    var code by remember { mutableStateOf<FrameCode?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            code = runCatching { c.g.api.code() }.getOrNull() ?: code
            delay(if (code == null) 30_000 else 30 * 60_000)
        }
    }
    val cd = code ?: return
    Column(
        Modifier.widthIn(max = 380.dp).clip(KinShapeM).background(Kin.colors.surface).border(1.dp, Kin.colors.line, KinShapeM).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_code_title), style = Kin.type.bodyStrong, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        QrCode(cd.joinUrl, Modifier.size(200.dp))
        Spacer(Modifier.height(16.dp))
        Text(Format.code(cd.code), style = Kin.type.headline.copy(fontWeight = FontWeight.Medium, letterSpacing = 3.sp))
        Text(stringResource(R.string.empty_code_body), style = Kin.type.caption, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EmptyIllustration() {
    val accent = Kin.colors.accent
    val sage = Kin.colors.sage
    val line = Kin.colors.line
    val soft = Kin.colors.accentSoft
    Canvas(Modifier.size(220.dp, 150.dp)) {
        fun frame(x: Float, y: Float, w: Float, h: Float, rot: Float, fill: Color) {
            rotateCompat(rot, Offset(x + w / 2, y + h / 2)) {
                drawRoundRect(Color.White, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(14f))
                drawRoundRect(line, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(14f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                drawRoundRect(fill, Offset(x + 12, y + 12), androidx.compose.ui.geometry.Size(w - 24, h - 24), androidx.compose.ui.geometry.CornerRadius(8f))
            }
        }
        frame(10f, 30f, 150f, 110f, -8f, soft)
        frame(120f, 20f, 150f, 110f, 7f, sage.copy(alpha = 0.35f))
        drawCircle(accent, 16f, Offset(size.width * 0.62f, size.height * 0.42f))
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateCompat(deg: Float, pivot: Offset, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.transform.rotate(deg, pivot)
    block()
    drawContext.transform.rotate(-deg, pivot)
}

