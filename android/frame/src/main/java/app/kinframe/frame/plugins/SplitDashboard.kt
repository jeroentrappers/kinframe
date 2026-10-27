package app.kinframe.frame.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.design.LocalKinColors
import app.kinframe.core.plugin.ui.PluginPalette
import app.kinframe.core.plugin.ui.ViewForm
import app.kinframe.frame.calendar.CalEvent
import app.kinframe.frame.calendar.CalendarData
import app.kinframe.frame.calendar.UpcomingAgenda
import app.kinframe.frame.slideshow.Collage
import app.kinframe.frame.slideshow.SlideRenderer
import app.kinframe.frame.weather.Units
import app.kinframe.frame.weather.Weather
import app.kinframe.frame.weather.WeatherCodes
import app.kinframe.frame.weather.WeatherIcon
import java.time.ZoneId

/** How far ahead the dashboard's agenda looks. */
const val DASHBOARD_HOURS = 48

/** At most this many widgets on the dashboard (two per corner slot). */
const val DASHBOARD_WIDGETS = 4

/**
 * The split layout's dashboard (docs/plugins.md §2.2): the clock and the weather on top, the plugin widgets in one
 * strip below them, and the next 48 hours of the calendar (today and the next day side by side in a wide cell). Dark,
 * a shade lighter than the grid between the photos, so it sits with them instead of glaring beside them, and stays
 * calm at night. Without a calendar the widgets take the room, and the other way round.
 */
@Composable
fun SplitDashboard(
    states: List<InstanceState>,
    clock: String?,
    date: String?,
    weather: Weather?,
    nowMs: Long,
    unit: String,
    calendar: CalendarData?,
    zone: ZoneId,
    h24: Boolean,
    onWidget: (InstanceState) -> Unit,
    onEvent: (CalEvent) -> Unit,
    onClock: () -> Unit,
    onWeather: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalKinColors provides KinPalettes.dark) {
        val palette = PluginPalette.onPaper(Kin.colors)
        val widgets = states.filter { it.shown && (it.view != null || it.status == InstanceStatus.Unavailable) }
            .sortedWith(compareBy({ it.config.slot.ordinal }, { it.config.order })).take(DASHBOARD_WIDGETS)
        BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(Kin.colors.surface)) {
            val wide = maxWidth > maxHeight * 1.15f
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
                DashboardHeader(clock, date, weather, nowMs, unit, palette, onClock, onWeather)
                if (widgets.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    WidgetStrip(widgets, palette, onWidget)
                }
                if (calendar != null) {
                    Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Kin.colors.line))
                    UpcomingAgenda(
                        calendar, weather, nowMs, zone, h24, unit, hours = DASHBOARD_HOURS, maxEvents = 8, onEvent = onEvent,
                        modifier = Modifier.weight(1f).fillMaxWidth(), columns = if (wide) 2 else 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    clock: String?, date: String?, weather: Weather?, nowMs: Long, unit: String, palette: PluginPalette, onClock: () -> Unit, onWeather: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClock),
        ) {
            if (clock != null) Text(clock, style = Kin.type.headline.copy(fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light), color = palette.text, maxLines = 1)
            if (date != null) Text(date, style = Kin.type.body.copy(fontSize = 17.sp), color = palette.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val now = weather?.nowConditions(nowMs)
        if (now != null) {
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onWeather),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 52.dp, onPaper = false)
                Text(Units.tempLabel(now.tempC, unit), style = Kin.type.headline.copy(fontSize = 36.sp, fontWeight = FontWeight.Light), color = palette.text)
            }
        }
    }
}

/**
 * The widgets in their strip form (the band's horizontal form: compact, legible from across the room), each at its
 * own size, flowing into rows like words: a widget that doesn't fit beside the previous one starts a new row. Only a
 * widget wider than the whole dashboard is scaled down, and on its own.
 */
@Composable
private fun WidgetStrip(widgets: List<InstanceState>, palette: PluginPalette, onTap: (InstanceState) -> Unit) {
    Layout(
        content = {
            widgets.forEach { s ->
                androidx.compose.runtime.key(s.config.instanceId) {
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onTap(s) }) {
                        InstanceContent(s, ViewForm.Strip, palette)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 36.dp.roundToPx()
        val rowGap = 12.dp.roundToPx()
        val maxW = constraints.maxWidth
        val items = measurables.map { it.measure(Constraints(maxHeight = STRIP_HEIGHT.roundToPx())) }
        // Where each widget goes; one wider than the dashboard gets a row of its own, scaled to fit.
        data class Placed(val i: Int, val x: Int, val y: Int, val scale: Float)
        val placed = ArrayList<Placed>()
        var x = 0
        var y = 0
        var rowH = 0
        items.forEachIndexed { i, p ->
            val scale = if (p.width > maxW) maxW.toFloat() / p.width else 1f
            val w = (p.width * scale).toInt()
            val h = (p.height * scale).toInt()
            if (x > 0 && x + gap + w > maxW) { y += rowH + rowGap; x = 0; rowH = 0 }
            val left = if (x == 0) 0 else x + gap
            placed += Placed(i, left, y, scale)
            x = left + w
            rowH = maxOf(rowH, h)
        }
        layout(maxW, if (items.isEmpty()) 0 else y + rowH) {
            placed.forEach { (i, px, py, scale) ->
                if (scale == 1f) items[i].place(px, py)
                else items[i].placeWithLayer(px, py) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            }
        }
    }
}

/** A widget row's height: the band strip's (§2.1). */
private val STRIP_HEIGHT = 76.dp

/**
 * Places the dashboard in its [cell] of a [screenW] × [screenH] screen, in the same grid as the photo tiles
 * ([app.kinframe.frame.slideshow.SlideRenderer.renderTiles]): half a gap between cells, a whole one at the screen's edges.
 */
fun Modifier.inSplitCell(cell: Collage.Cell, screenW: Dp, screenH: Dp): Modifier {
    val gap = SlideRenderer.TILE_GAP_DP.dp
    fun inset(atEdge: Boolean) = gap * (if (atEdge) 1f else .5f)
    return offset(screenW * cell.left + inset(cell.left == 0f), screenH * cell.top + inset(cell.top == 0f))
        .size(
            screenW * cell.width - inset(cell.left == 0f) - inset(cell.right >= 1f),
            screenH * cell.height - inset(cell.top == 0f) - inset(cell.bottom >= 1f),
        )
}
