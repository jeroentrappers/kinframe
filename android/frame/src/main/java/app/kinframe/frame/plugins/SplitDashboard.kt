package app.kinframe.frame.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.plugin.ui.PluginPalette
import app.kinframe.core.plugin.ui.ScaleToFit
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
 * The split layout's dashboard (docs/plugins.md §2.2): the clock, date and weather on top, then the plugin widgets and
 * the next 48 hours of the calendar, side by side in a wide cell and stacked in a tall one. On paper, as a cell of the
 * same grid as the two photos. Without a calendar the widgets take the whole body, and the other way round.
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
    val palette = PluginPalette.onPaper(Kin.colors)
    val widgets = states.filter { it.shown && (it.view != null || it.status == InstanceStatus.Unavailable) }
        .sortedWith(compareBy({ it.config.slot.ordinal }, { it.config.order })).take(DASHBOARD_WIDGETS)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(Kin.colors.paper)) {
        val wide = maxWidth > maxHeight * 1.15f
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            DashboardHeader(clock, date, weather, nowMs, unit, palette, onClock, onWeather)
            Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Kin.colors.line))
            val agenda = @Composable { m: Modifier ->
                UpcomingAgenda(calendar, weather, nowMs, zone, h24, unit, hours = DASHBOARD_HOURS, maxEvents = if (wide) 5 else 6, onEvent = onEvent, modifier = m)
            }
            val showAgenda = calendar != null
            when {
                widgets.isEmpty() && showAgenda -> agenda(Modifier.weight(1f).fillMaxWidth())
                !showAgenda -> Widgets(widgets, palette, onWidget, Modifier.weight(1f).fillMaxWidth(), columns = if (wide) 2 else 1)
                wide -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    Widgets(widgets, palette, onWidget, Modifier.weight(1f).fillMaxHeight(), columns = 1)
                    Box(Modifier.padding(horizontal = 16.dp).width(1.dp).fillMaxHeight().background(Kin.colors.line))
                    agenda(Modifier.weight(1.1f).fillMaxHeight())
                }
                else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    Widgets(widgets, palette, onWidget, Modifier.fillMaxWidth(), columns = 1)
                    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Kin.colors.line))
                    agenda(Modifier.weight(1f).fillMaxWidth())
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
            if (clock != null) Text(clock, style = Kin.type.headline.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal), color = palette.text, maxLines = 1)
            if (date != null) Text(date, style = Kin.type.caption.copy(fontSize = 15.sp), color = palette.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val now = weather?.nowConditions(nowMs)
        if (now != null) {
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onWeather),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 44.dp, onPaper = true)
                Text(Units.tempLabel(now.tempC, unit), style = Kin.type.title.copy(fontWeight = FontWeight.Light), color = palette.text)
            }
        }
    }
}

/** The widgets in their corner form, scaled down when they don't fit their share of the body. */
@Composable
private fun Widgets(widgets: List<InstanceState>, palette: PluginPalette, onTap: (InstanceState) -> Unit, modifier: Modifier, columns: Int) {
    BoxWithConstraints(modifier) {
        val rows = widgets.chunked(columns)
        val cellW = maxWidth / columns
        val cellH = if (rows.isEmpty()) maxHeight else (maxHeight - 10.dp * (rows.size - 1)) / rows.size
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row {
                    row.forEach { s ->
                        androidx.compose.runtime.key(s.config.instanceId) {
                            Box(
                                Modifier.width(cellW)
                                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onTap(s) },
                            ) {
                                ScaleToFit(cellW, cellH) { InstanceContent(s, ViewForm.Corner, palette) }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
