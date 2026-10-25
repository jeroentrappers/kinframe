package app.kinframe.frame.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.animates
import app.kinframe.core.plugin.ui.LocalPluginClock
import app.kinframe.core.plugin.ui.rememberPluginClock
import app.kinframe.frame.slideshow.CornerWidgets
import app.kinframe.frame.weather.Weather

/**
 * The slideshow's plugin layout without the slideshow: a photo stand-in, the clock corner, the overlay corners or the
 * band, and the detail card. Used by the dev preview (debug builds) and the screenshot tests, with the same pieces
 * and geometry [app.kinframe.frame.slideshow.SlideshowScreen] uses.
 */
@Composable
fun PluginStage(
    layout: PluginLayoutConfig,
    states: List<InstanceState>,
    clock: String?,
    date: String?,
    weather: Weather?,
    nowMs: Long,
    unit: String = "c",
    /** The band column's two-line date ("Sun" / "25 Oct"); the strip uses [date]. */
    shortDate: String? = null,
    photoImage: ImageBitmap? = null,
    detail: InstanceState? = null,
    detailView: PluginView? = null,
    onTap: (InstanceState) -> Unit = {},
    onCloseDetail: () -> Unit = {},
    photo: @Composable () -> Unit,
) {
    val clockState = rememberPluginClock(running = states.any { it.shown && it.view?.animates() == true })
    CompositionLocalProvider(LocalPluginClock provides clockState) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = LocalDensity.current
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val screenW = maxWidth
            val screenH = maxHeight
            val band = if (layout.mode == PluginLayoutMode.Band) BandGeometry.compute(w, h, d.density, layout.side) as? BandGeometry.Band else null
            val overlay = layout.mode == PluginLayoutMode.Overlay || (layout.mode == PluginLayoutMode.Band && band == null)
            val clockInBand = band != null && (clock != null || date != null || weather != null)
            val open = band != null && (clockInBand || states.anyVisible())
            val area = if (open) band!!.photo else PxRect(0, 0, w, h)
            Box(
                Modifier.layout { m, _ ->
                    val p = m.measure(Constraints.fixed(area.width, area.height))
                    layout(w, h) { p.place(area.left, area.top) }
                }.clipToBounds(),
            ) {
                photo()
                if (open) Box(Modifier.fillMaxSize().bandGridFrame { 1f })
                CornerWidgets(
                    clock.takeIf { !(clockInBand && open) }, date.takeIf { !(clockInBand && open) }, weather.takeIf { !(clockInBand && open) },
                    nowMs, unit, onPaper = false,
                ) {}
                if (overlay) {
                    CornerSlot(
                        states.inSlot(Slot.TopRight), end = true, screenWidth = screenW, screenHeight = screenH, hidden = false, onTap = onTap,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp),
                    )
                    Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                        CornerSlot(states.inSlot(Slot.TopLeft), end = false, screenWidth = screenW, screenHeight = screenH, hidden = false, onTap = onTap)
                    }
                }
            }
            if (open && band != null) {
                PluginBand(
                    band, fraction = { 1f }, states = states, background = layout.background, photo = photoImage, onTap = onTap,
                    clock = if (!clockInBand) null else { form, palette ->
                        BandClock(form, palette, clock, date, weather, nowMs, unit, onPaper = layout.background == BandBackground.Paper, shortDate = shortDate)
                    },
                )
            }
            PluginDetailCard(detail, detailView, onCloseDetail)
        }
    }
}

/** Preview and test helper: an instance that shows [view]. */
fun previewInstance(id: String, view: PluginView?, slot: Slot, status: InstanceStatus = InstanceStatus.Ok, name: String = id) = InstanceState(
    config = InstanceConfig(id, "preview", slot = slot),
    name = name, view = view, status = status, shown = true, lastGoodAt = 1L, error = null,
)

/**
 * A neutral stand-in photo (sky, horizon, a pale sun) for the dev preview and screenshots: no personal photos.
 * Four palettes by [seed].
 */
fun placeholderPhoto(w: Int, h: Int, seed: Int = 0): androidx.compose.ui.graphics.ImageBitmap {
    val palettes = listOf(
        intArrayOf(0xFF9CCBF0.toInt(), 0xFFE9F4FB.toInt(), 0xFF8DB580.toInt(), 0xFF55804A.toInt()),
        intArrayOf(0xFF2B2A44.toInt(), 0xFF6E4A62.toInt(), 0xFFE39A6B.toInt(), 0xFF3A2A2A.toInt()),
        intArrayOf(0xFFF3D9B1.toInt(), 0xFFE7B07A.toInt(), 0xFFB86B4B.toInt(), 0xFF6B3F2F.toInt()),
        intArrayOf(0xFFBFD8D2.toInt(), 0xFF7FA99B.toInt(), 0xFF3F6E63.toInt(), 0xFF1F3B36.toInt()),
    )
    val p = palettes[Math.floorMod(seed, palettes.size)]
    val bmp = androidx.core.graphics.createBitmap(w, h)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), p, floatArrayOf(0f, 0.5f, 0.62f, 1f), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = 0x80FFFFFF.toInt()
    c.drawCircle(w * 0.72f, h * 0.25f, minOf(w, h) * 0.12f, paint)
    paint.color = (p[3] and 0x00FFFFFF) or 0x80000000.toInt()
    c.drawRect(0f, h * 0.78f, w.toFloat(), h.toFloat(), paint)
    return bmp.asImageBitmap()
}
