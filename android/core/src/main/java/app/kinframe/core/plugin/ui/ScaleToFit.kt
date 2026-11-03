package app.kinframe.core.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lays [content] out at its natural size and scales it down (never up) to fit [maxWidth] × [maxHeight]: "a plugin
 * never sets pixel sizes; the frame scales the content to fit" (docs/plugins.md §2). Text is measured at up to
 * [wrapFactor] × [maxWidth] first, so a long line shrinks a little instead of wrapping into a tall block.
 */
@Composable
fun ScaleToFit(maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier, wrapFactor: Float = 1.6f, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, _ ->
        val mw = maxWidth.roundToPx().coerceAtLeast(1)
        val mh = maxHeight.roundToPx().coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = (mw * wrapFactor).roundToInt(), maxHeight = Constraints.Infinity)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        val s = if (w == 0 || h == 0) 1f else min(1f, min(mw.toFloat() / w, mh.toFloat() / h))
        layout((w * s).roundToInt(), (h * s).roundToInt()) {
            placeables.forEach { p ->
                if (s >= 0.999f) p.place(0, 0)
                else p.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
            }
        }
    }
}
