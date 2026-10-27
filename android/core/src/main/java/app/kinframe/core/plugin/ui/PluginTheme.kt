package app.kinframe.core.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinColors
import app.kinframe.core.plugin.Tone
import kotlinx.coroutines.delay

/**
 * Where a view is drawn. [Corner] is the overlay in a top corner of the photo, [Detail] the card that opens on tap,
 * [Column] the narrow band beside a landscape photo (icon above value) and [Strip] the short band above a portrait
 * photo (widgets side by side). Plugins never choose; the same view renders in every form.
 */
enum class ViewForm { Corner, Detail, Column, Strip;
    val compact: Boolean get() = this == Column || this == Strip
}

/**
 * Colours for plugin views: white with a soft text shadow on photos (like the clock and caption), ink on paper and
 * on the detail card. Tones map to the frame's palette, lightened on photos so they stay readable over any image.
 */
@Immutable
data class PluginPalette(
    val text: Color,
    val soft: Color,
    val faint: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val accent: Color,
    val info: Color,
    val water: Color,
    val gas: Color,
    val heat: Color,
    val shadow: Shadow?,
    /** Icons on a filled (primary) button. */
    val onTone: Color = Color.White,
) {
    fun tone(t: Tone): Color = when (t) {
        Tone.Neutral -> text
        Tone.Good -> good
        Tone.Warn -> warn
        Tone.Bad -> bad
        Tone.Accent -> accent
        Tone.Info -> info
        Tone.Water -> water
        Tone.Gas -> gas
        Tone.Heat -> heat
    }

    companion object {
        val OnPhoto = PluginPalette(
            text = Color.White,
            soft = Color.White.copy(alpha = 0.82f),
            faint = Color.White.copy(alpha = 0.32f),
            good = Color(0xFFA9E8B6),
            warn = Color(0xFFFFD27A),
            bad = Color(0xFFFFA092),
            accent = Color(0xFFF8BC98),
            info = Color(0xFFD3BBF7),
            water = Color(0xFFB3EEFF),
            gas = Color(0xFFFFA3D2),
            heat = Color(0xFFFFB066),
            shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f),
        )

        fun onPaper(c: KinColors) = PluginPalette(
            text = c.ink, soft = c.inkSoft, faint = c.line, good = if (c.isDark) c.sage else PaperGreen, warn = c.warn, bad = c.danger, accent = c.accent, info = c.violet,
            water = c.water, gas = c.gas, heat = c.heat, shadow = null,
        )
    }
}

/** Sage reads grey at widget size; a plugin's `good` on paper is a clear green that still passes AA on paper. */
private val PaperGreen = Color(0xFF2E7D46)

/** Sizes per form. The frame is read from across the room: corner values are a little smaller than the clock. */
@Immutable
internal data class FormMetrics(
    val icon: Dp,
    val value: TextStyle,
    val unit: TextStyle,
    val label: TextStyle,
    val gap: Dp,
    val nodeCircle: Dp,
    val nodeIcon: Dp,
    val nodeValue: TextStyle,
    val gauge: Dp,
    val gaugeStroke: Dp,
    val sparkW: Dp,
    val sparkH: Dp,
    val line1: TextStyle,
    val line2: TextStyle,
)

@Composable
internal fun metricsFor(form: ViewForm): FormMetrics {
    val t = Kin.type
    fun TextStyle.size(s: TextUnit, w: FontWeight? = null) = copy(fontSize = s, lineHeight = s * 1.18f, fontWeight = w ?: fontWeight)
    val base = t.body
    val scale = t.body.fontSize.value / 19f // the frame's text-size setting
    fun sp(v: Float) = androidx.compose.ui.unit.TextUnit(v * scale, androidx.compose.ui.unit.TextUnitType.Sp)
    return remember(form, scale) {
        when (form) {
            ViewForm.Corner -> FormMetrics(
                icon = 34.dp, value = base.size(sp(40f), FontWeight.Light), unit = base.size(sp(21f)), label = base.size(sp(16f)), gap = 10.dp,
                nodeCircle = 50.dp, nodeIcon = 26.dp, nodeValue = base.size(sp(19f), FontWeight.Medium), gauge = 76.dp, gaugeStroke = 6.dp,
                sparkW = 200.dp, sparkH = 54.dp, line1 = base.size(sp(20f)), line2 = base.size(sp(16f)),
            )
            ViewForm.Detail -> FormMetrics(
                icon = 40.dp, value = base.size(sp(46f), FontWeight.Light), unit = base.size(sp(24f)), label = base.size(sp(17f)), gap = 12.dp,
                nodeCircle = 60.dp, nodeIcon = 32.dp, nodeValue = base.size(sp(21f), FontWeight.Medium), gauge = 96.dp, gaugeStroke = 7.dp,
                sparkW = 420.dp, sparkH = 96.dp, line1 = base.size(sp(21f)), line2 = base.size(sp(17f)),
            )
            ViewForm.Column -> FormMetrics(
                icon = 26.dp, value = base.size(sp(21f), FontWeight.Medium), unit = base.size(sp(13f)), label = base.size(sp(12f)), gap = 4.dp,
                nodeCircle = 34.dp, nodeIcon = 19.dp, nodeValue = base.size(sp(14f), FontWeight.Medium), gauge = 54.dp, gaugeStroke = 5.dp,
                sparkW = 64.dp, sparkH = 30.dp, line1 = base.size(sp(13f)), line2 = base.size(sp(12f)),
            )
            ViewForm.Strip -> FormMetrics(
                icon = 26.dp, value = base.size(sp(23f), FontWeight.Medium), unit = base.size(sp(14f)), label = base.size(sp(13f)), gap = 8.dp,
                nodeCircle = 34.dp, nodeIcon = 19.dp, nodeValue = base.size(sp(14f), FontWeight.Medium), gauge = 56.dp, gaugeStroke = 5.dp,
                sparkW = 120.dp, sparkH = 40.dp, line1 = base.size(sp(16f)), line2 = base.size(sp(13f)),
            )
        }
    }
}

/**
 * The pictures of `image` views, by [app.kinframe.core.plugin.ImageView.src]: fetched and decoded by the host, null
 * while loading or when they failed. The app provides it per instance.
 */
val LocalPluginImages = compositionLocalOf<(String) -> androidx.compose.ui.graphics.ImageBitmap?> { { null } }

/** Runs an `actions` button's id through the plugin; null where views aren't interactive (buttons drawn disabled). */
val LocalPluginActions = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * One clock for every animated flow on screen, in seconds. It ticks at about 25 frames per second instead of every
 * vsync: arrows still move smoothly, and on the Mali-400 the screen redraws less than half as often while nothing
 * else animates. Only draw lambdas read it, so ticking never recomposes or relayouts anything.
 */
val LocalPluginClock = staticCompositionLocalOf<FloatState?> { null }

@Composable
fun rememberPluginClock(running: Boolean, frameMs: Long = 40): FloatState {
    val state = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.nanoTime() - (state.floatValue * 1e9f).toLong()
        while (true) {
            delay(frameMs)
            // Wraps every ~2.7 h so float precision stays well below a pixel.
            state.floatValue = ((System.nanoTime() - start) / 1e9 % 10_000.0).toFloat()
        }
    }
    return state
}
