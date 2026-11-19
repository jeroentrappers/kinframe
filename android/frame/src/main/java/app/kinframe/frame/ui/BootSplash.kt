package app.kinframe.frame.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.kinframe.frame.R
import app.kinframe.frame.StartupStep
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The frame's start-up screen: the Android boot animation (tools/brand/boot/build.py) continued pixel for pixel, so
 * the screen doesn't change when Android hands over to the app, plus a quiet line saying what the frame is doing.
 *
 * Everything is laid out in screen pixels from the real drawing size, exactly as build.py does it for that screen:
 * the reference layout is 1280x800 and other screens scale it by their height.
 */
object BootSplashGeometry {
    val Background = Color(0xFF191512) // web/src/app.css dark --bg
    val Accent = Color(0xFFF09A6D) // dark --primary
    val Dim = Color(0xFF3A322B) // dark --border
    /** The status line: dark --text-faint, a muted version of the wordmark's light colour. */
    val Status = Color(0xFF94867A)

    private const val REF_H = 800.0
    private const val LOGO_W = 562.0
    private const val VIEW_W = 281.0
    private const val VIEW_H = 64.0
    private const val ANIM_W = 720.0
    private const val ANIM_H = 336.0
    const val DOTS = 3
    private const val DOT_R = 6.0
    private const val DOT_GAP = 30.0
    private const val DOT_DY = 56.0
    const val FPS = 20
    const val FRAMES = 24 // 1.2 s loop

    /** The status line's text size and its centre below the dots, in reference pixels. */
    private const val STATUS_SIZE = 17.0
    private const val STATUS_DY = 46.0

    data class Layout(
        val width: Int, val height: Int, val scale: Double,
        /** The wordmark's rectangle in whole pixels, as resvg renders it for the boot animation. */
        val logoX: Int, val logoY: Int, val logoW: Int, val logoH: Double,
        val dotY: Double, val dotX: List<Double>, val dotR: Double,
        val statusY: Double, val statusSize: Double,
    )

    /** Python's round(): halves go to the even neighbour. */
    private fun pyRound(v: Double): Int = Math.rint(v).toInt()

    fun of(width: Int, height: Int): Layout {
        val s = height / REF_H
        val logoW = pyRound(LOGO_W * s)
        val logoH = logoW * VIEW_H / VIEW_W // 172.87 px at 1080 px high
        // resvg sizes the bitmap by rounding the scaled height up and stretches the viewBox over all of it, so the
        // wordmark is drawn (and centred) at that whole-pixel height.
        val markH = ceil(logoH - 1e-9).toInt()
        val animW = 2 * pyRound(ANIM_W * s / 2)
        val animH = 2 * pyRound(ANIM_H * s / 2)
        val dotDy = pyRound(DOT_DY * s)
        val dotGap = DOT_GAP * s
        // The boot animation draws its frame centred (integer division), and the dots sit in that frame.
        val ox = (width - animW) / 2
        val oy = (height - animH) / 2
        val dotY = (oy + animH / 2 + markH / 2 + dotDy).toDouble()
        val cx0 = animW / 2 - dotGap * (DOTS - 1) / 2
        return Layout(
            width, height, s,
            logoX = (width - logoW) / 2, logoY = (height - markH) / 2, logoW = logoW, logoH = markH.toDouble(),
            dotY = dotY, dotX = (0 until DOTS).map { ox + cx0 + it * dotGap }, dotR = DOT_R * s,
            statusY = dotY + STATUS_DY * s, statusSize = STATUS_SIZE * s,
        )
    }

    /** How much dot [k] glows (0..1) in animation frame [frame], as build.py's anim_frame(). */
    fun glow(frame: Int, k: Int): Double {
        val raw = frame.toDouble() / FRAMES - k.toDouble() / (DOTS * 2)
        val phase = raw - floor(raw)
        return max(0.0, cos(2 * PI * phase)).let { it * it }
    }

    fun dotRadius(l: Layout, glow: Double): Double = l.dotR * (0.85 + 0.15 * glow)

    /**
     * Added to the radius when drawing: build.py draws the dots with PIL at 4x (whose ellipse includes its right and
     * bottom bounds) and scales down with Lanczos, which makes them look this much bigger than Skia's circle of the
     * same radius. Measured against the boot animation's frames (BootSplashScreenshotTest).
     */
    const val DOT_EDGE = 0.25

    /** The dot colour as build.py mixes it: per channel, rounded. */
    fun dotColor(glow: Double): Color {
        fun ch(a: Int, b: Int) = Math.rint(a + (b - a) * glow).toInt() // Python's round()
        return Color(ch(0x3A, 0xF0), ch(0x32, 0x9A), ch(0x2B, 0x6D))
    }

    /** The boot animation's frame at [elapsedMs]: it plays at 20 frames a second. */
    fun frameAt(elapsedMs: Long): Int = ((elapsedMs * FPS / 1000) % FRAMES).toInt()
}

/** Shared by every splash on screen, so two of them (cross-fading between screens) draw the very same picture. */
object SplashClock {
    private val origin = SystemClock.elapsedRealtime()
    @Volatile private var firstShown = UNSET

    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - origin

    /** How long a splash has been on screen; starts counting at the first call. */
    fun shownForMs(): Long {
        val now = SystemClock.elapsedRealtime()
        if (firstShown == UNSET) firstShown = now
        return now - firstShown
    }

    /** Tests: Robolectric restarts its clock for every test, this object lives on. */
    @androidx.annotation.VisibleForTesting
    fun reset() { firstShown = UNSET }

    /** Tests: as if a splash had been on screen for [ms] already, so the status line shows from the first frame. */
    @androidx.annotation.VisibleForTesting
    fun pretendShownFor(ms: Long) { firstShown = SystemClock.elapsedRealtime() - ms }

    /** Fast starts show no text: the status line appears only after the splash has been up this long. */
    const val STATUS_DELAY_MS = 1_500L

    /** No splash shown yet (a sentinel, since a test clock can make real timestamps negative). */
    private const val UNSET = Long.MIN_VALUE
}

@StringRes
fun startupStepText(step: StartupStep): Int? = when (step) {
    StartupStep.Starting -> R.string.startup_starting
    StartupStep.CheckingLibrary -> R.string.startup_checking_library
    StartupStep.CheckingUpdate -> R.string.startup_checking_update
    StartupStep.LoadingPhotos -> R.string.startup_loading_photos
    StartupStep.PreparingPhoto -> R.string.startup_preparing_photo
    StartupStep.WaitingForWifi -> R.string.startup_waiting_wifi
    StartupStep.SettingClock -> R.string.startup_setting_clock
    StartupStep.ConnectingToServer -> R.string.startup_connecting
    StartupStep.Ready -> null
}

/**
 * The boot animation's picture: the wordmark and three pulsing dots on the brand background, and below them what the
 * frame is doing ([step]). The dots step at the boot animation's 20 frames a second, which also keeps the redraws
 * cheap while the first photo is being prepared.
 */
@Composable
fun BootSplash(step: StartupStep, modifier: Modifier = Modifier, showStatus: Boolean = true, fixedFrame: Int? = null) {
    val wordmark = painterResource(R.drawable.kinframe_wordmark)
    BoxWithConstraints(modifier.fillMaxSize().drawBehind { drawRect(BootSplashGeometry.Background) }) {
        val l = remember(constraints.maxWidth, constraints.maxHeight) { BootSplashGeometry.of(constraints.maxWidth, constraints.maxHeight) }
        Canvas(Modifier.fillMaxSize()) {
            translate(l.logoX.toFloat(), l.logoY.toFloat()) {
                with(wordmark) { draw(Size(l.logoW.toFloat(), l.logoH.toFloat())) }
            }
        }
        var frame by remember { mutableIntStateOf(fixedFrame ?: BootSplashGeometry.frameAt(SplashClock.elapsedMs())) }
        if (fixedFrame == null) LaunchedEffect(Unit) {
            val frameMs = 1000L / BootSplashGeometry.FPS
            while (true) {
                kotlinx.coroutines.delay(frameMs - SplashClock.elapsedMs() % frameMs)
                frame = BootSplashGeometry.frameAt(SplashClock.elapsedMs())
            }
        }
        // Its own layer: a new frame redraws only the dots.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            for (k in 0 until BootSplashGeometry.DOTS) {
                val g = BootSplashGeometry.glow(frame, k)
                drawCircle(
                    BootSplashGeometry.dotColor(g), (BootSplashGeometry.dotRadius(l, g) + BootSplashGeometry.DOT_EDGE).toFloat(),
                    Offset(l.dotX[k].toFloat(), l.dotY.toFloat()),
                )
            }
        }
        if (showStatus) StatusLine(step, l)
    }
}

/** Fades in once the splash has been up [SplashClock.STATUS_DELAY_MS], cross-fades between steps, fades out when done. */
@Composable
private fun StatusLine(step: StartupStep, l: BootSplashGeometry.Layout) {
    val text = startupStepText(step)?.let { stringResource(it) }
    var last by remember { mutableStateOf(text) }
    if (text != null) last = text
    val due = SplashClock.shownForMs() >= SplashClock.STATUS_DELAY_MS
    var late by remember { mutableStateOf(due) }
    LaunchedEffect(Unit) {
        val wait = SplashClock.STATUS_DELAY_MS - SplashClock.shownForMs()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        late = true
    }
    val visible = late && text != null
    // A splash that replaces another one mid-way (a screen change) starts where the previous one was.
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) { alpha.animateTo(if (visible) 1f else 0f, tween(if (visible) 450 else 250)) }
    val shown = last ?: return
    val density = LocalDensity.current
    val sizeSp = with(density) { (l.statusSize.toFloat() / fontScale).toDp().value }.sp
    val lineH = l.statusSize * 1.4
    Box(
        Modifier.fillMaxWidth()
            .offset { IntOffset(0, (l.statusY - lineH / 2).roundToInt()) }
            .padding(horizontal = with(density) { (48 * l.scale).toFloat().toDp() })
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        Crossfade(shown, animationSpec = tween(300), label = "startupStep") { t ->
            Text(
                t, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = BootSplashGeometry.Status, fontSize = sizeSp,
                    lineHeight = with(density) { (lineH.toFloat() / fontScale).toDp().value }.sp,
                ),
            )
        }
    }
}

/** How long the splash takes to fade out over the first photo. */
const val SPLASH_FADE_MS = 600

/**
 * The start-up splash over the slideshow: opaque until the first photo is on the stage (it appears there at once,
 * see SlideshowController.present), then it fades out over it, so splash and photo cross-fade directly. Swallows
 * touches while it is up. Not shown when the slideshow comes up later (after setup or recovery).
 */
@Composable
fun SplashOverlay(c: app.kinframe.frame.FrameController) {
    val up by c.splashUp.collectAsState()
    if (!up) return
    val step by c.startup.collectAsState()
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(step == StartupStep.Ready) {
        if (step == StartupStep.Ready) {
            alpha.animateTo(0f, tween(SPLASH_FADE_MS))
            c.onSplashGone()
        }
    }
    BootSplash(
        step,
        Modifier.graphicsLayer { this.alpha = alpha.value }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    )
}
