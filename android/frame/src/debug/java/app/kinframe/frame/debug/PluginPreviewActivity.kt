package app.kinframe.frame.debug

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import app.kinframe.core.design.KinframeTheme
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.BandBackground
import app.kinframe.frame.plugins.BandSide
import app.kinframe.frame.plugins.InstanceState
import app.kinframe.frame.plugins.PluginLayoutConfig
import app.kinframe.frame.plugins.PluginLayoutMode
import app.kinframe.frame.plugins.PluginStage
import app.kinframe.frame.plugins.Slot
import app.kinframe.frame.plugins.placeholderPhoto
import app.kinframe.frame.plugins.previewInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dev preview of plugin views and layouts on a frame or an emulator, with the example views from
 * docs/api/plugin-views (debug assets) over neutral placeholder photos that cross-fade and zoom like the slideshow.
 *
 *     adb shell am start -n app.kinframe.frame/.debug.PluginPreviewActivity \
 *       --es layout band|overlay|off --es side left|right --es bg paper|photo --ez clock true \
 *       --es a flow-solar-home-grid --es b value-using --es orient land|port --ez sim true --ez detail false --ez cycle true
 *
 * Tap: opens (or closes) the detail card of the first widget. Instance b cycles through b, value-returning and
 * gauge-car every 5 s so value changes animate. Used for the screenshots in docs/plugins/ and the frame-time numbers.
 */
class PluginPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val x = intent
        requestedOrientation = when (x.getStringExtra("orient")) {
            "port" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "land" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val layout = PluginLayoutConfig(
            mode = when (x.getStringExtra("layout")) { "overlay" -> PluginLayoutMode.Overlay; "off" -> PluginLayoutMode.Off; else -> PluginLayoutMode.Band },
            side = if (x.getStringExtra("side") == "left") BandSide.Left else BandSide.Right,
            clockInBand = x.getBooleanExtra("clock", false),
            background = if (x.getStringExtra("bg") == "photo") BandBackground.Photo else BandBackground.Paper,
        )
        val a = x.getStringExtra("a") ?: "flow-solar-home-grid"
        val b = x.getStringExtra("b") ?: "value-using"
        val sim = x.getBooleanExtra("sim", true)
        val detailAtStart = x.getBooleanExtra("detail", false)
        val cycle = x.getBooleanExtra("cycle", true)
        setContent { KinframeTheme { Preview(layout, a, b, sim, detailAtStart, cycle) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Full screen like the frame's own activity.
        if (hasFocus) androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun load(name: String): PluginView? = runCatching {
        val text = assets.open("examples/$name.json").bufferedReader().readText()
        (PluginViewParser.parse(text) as? ViewResult.Valid)?.view
    }.getOrNull()

    @Composable
    private fun Preview(layout: PluginLayoutConfig, a: String, b: String, sim: Boolean, detailAtStart: Boolean, cycle: Boolean) {
        val bCycle = remember { listOf(b, "value-returning", "gauge-car").distinct().mapNotNull { load(it) } }
        var bIndex by remember { mutableIntStateOf(0) }
        if (cycle) LaunchedEffect(Unit) { while (true) { delay(5_000); bIndex = (bIndex + 1) % bCycle.size } }
        val aView = remember { load(a) }
        val states: List<InstanceState> = if (layout.mode == PluginLayoutMode.Off) emptyList() else listOfNotNull(
            aView?.let { previewInstance("a", it, Slot.TopLeft, name = a) },
            bCycle.getOrNull(bIndex)?.let { previewInstance("b", it, Slot.TopRight, name = b) },
        )
        var detail by remember { mutableStateOf(detailAtStart) }
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { detail = !detail } }) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val photos = remember(w, h) { List(4) { placeholderPhoto(w, h, it) } }
            var index by remember { mutableIntStateOf(0) }
            val fade = remember { Animatable(1f) }
            val zoom = remember { Animatable(0f) }
            if (sim) LaunchedEffect(Unit) {
                while (true) {
                    zoom.snapTo(0f)
                    // Ken Burns-like motion for the whole hold, then an 800 ms cross-fade (the slideshow's defaults).
                    val motion = launch { zoom.animateTo(1f, tween(5_000, easing = LinearEasing)) }
                    delay(4_000)
                    index = (index + 1) % photos.size
                    fade.snapTo(0f)
                    fade.animateTo(1f, tween(800))
                    motion.cancel()
                }
            }
            PluginStage(
                layout, states, "14:20", "Sunday 25 October", null, System.currentTimeMillis(),
                photoImage = photos[index], detail = if (detail) states.firstOrNull() else null, detailView = if (detail) load("detail-p1") else null,
                onCloseDetail = { detail = false },
            ) {
                val prev = photos[(index + photos.size - 1) % photos.size]
                val cur = photos[index]
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha }) { drawImage(prev) }
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = fade.value
                        val s = 1f + 0.08f * zoom.value
                        scaleX = s; scaleY = s
                    },
                ) { drawImage(cur) }
            }
        }
    }
}
