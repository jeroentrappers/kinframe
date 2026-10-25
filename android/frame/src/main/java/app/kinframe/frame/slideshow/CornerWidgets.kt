package app.kinframe.frame.slideshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.frame.weather.Weather
import app.kinframe.frame.weather.WeatherNow

private val cornerShadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f)
private const val END_MARGIN_DP = 40f
private const val BOTTOM_MARGIN_DP = 28f

/**
 * A soft elliptical shade behind the corner block, so white text holds up on bright photos (beach, snow) without a
 * visible box. One gradient draw, no blur: cheap on the Mali-400. It spills past the block and off the screen edge.
 */
private fun Modifier.cornerScrim(): Modifier = drawBehind {
    // Centred low and right of the block, so it reads as a natural vignette into the corner.
    val c = Offset(size.width * 0.72f, size.height * 0.9f)
    val r = maxOf(size.width * 1.05f, 380.dp.toPx())
    scale(scaleX = 1f, scaleY = 0.5f, pivot = c) {
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.30f), 0.35f to Color.Black.copy(alpha = 0.20f), 0.7f to Color.Black.copy(alpha = 0.06f),
                1f to Color.Transparent, center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }
}

/**
 * The slideshow's corner: current weather and clock/date side by side, sharing two type lines (display and body)
 * so they read as one calm block. Bottom-right on photos, top-right on the paper empty state.
 */
@Composable
internal fun CornerWidgets(
    clock: String?,
    date: String?,
    weather: Weather?,
    nowMs: Long,
    unit: String,
    onPaper: Boolean,
    /** Tapping the clock or date opens the full-screen calendar (behaviour spec B.3). */
    onClockClick: (() -> Unit)? = null,
    /**
     * The block's size in dp with its screen margins (end and bottom), 0 × 0 when nothing is shown: captions keep
     * clear of it ([CaptionSafeArea]).
     */
    onBlockSize: ((widthDp: Float, heightDp: Float) -> Unit)? = null,
    onWeatherClick: () -> Unit,
) {
    val empty = clock == null && date == null && weather == null
    // Hidden, it takes no room. (On the paper empty state it moves to the top and isn't measured: no captions there.)
    if (onBlockSize != null && empty) androidx.compose.runtime.LaunchedEffect(Unit) { onBlockSize(0f, 0f) }
    if (empty) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val color = if (onPaper) Kin.colors.ink else Color.White
    val shadow = if (onPaper) null else cornerShadow
    Box(
        Modifier.fillMaxSize().padding(end = END_MARGIN_DP.dp, bottom = BOTTOM_MARGIN_DP.dp, top = 28.dp),
        contentAlignment = if (onPaper) Alignment.TopEnd else Alignment.BottomEnd,
    ) {
        Row(
            Modifier.then(if (onPaper) Modifier else Modifier.cornerScrim())
                .then(
                    if (onBlockSize == null || onPaper) Modifier
                    else Modifier.onSizeChanged { sz ->
                        with(density) { onBlockSize(sz.width.toDp().value + END_MARGIN_DP, sz.height.toDp().value + BOTTOM_MARGIN_DP) }
                    },
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (weather != null) {
                WeatherNow(weather, nowMs, unit, onPaper, onWeatherClick)
                if (clock != null || date != null) {
                    Box(
                        Modifier.padding(start = 26.dp, end = 26.dp, bottom = 6.dp).width(1.dp).height(if (clock != null) 74.dp else 24.dp)
                            .background(color.copy(alpha = if (onPaper) 0.25f else 0.45f)),
                    )
                }
            }
            if (clock != null || date != null) {
                Column(
                    if (onClockClick != null) {
                        Modifier.clickable(
                            androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            role = androidx.compose.ui.semantics.Role.Button, onClick = onClockClick,
                        )
                    } else Modifier,
                    horizontalAlignment = Alignment.End,
                ) {
                    if (clock != null) Text(clock, style = Kin.type.display.copy(color = color, shadow = shadow, fontWeight = FontWeight.Light))
                    if (date != null) Text(date, style = Kin.type.body.copy(color = color.copy(alpha = 0.9f), shadow = shadow))
                }
            }
        }
    }
}
