package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R
import app.kinframe.frame.weather.Weather
import java.time.Instant
import java.time.ZoneId

/**
 * Hybrid view (product spec 4.9): today and what is coming up, beside the slideshow (a side panel in landscape, a
 * bottom panel in portrait), with the weather. An opaque paper panel: the slideshow is simply smaller, nothing is
 * blended over the photo. Tapping it opens the full calendar.
 */
@Composable
fun CalendarSidebar(
    data: CalendarData?,
    weather: Weather?,
    nowMs: Long,
    zone: ZoneId,
    h24: Boolean,
    unit: String,
    landscape: Boolean,
    onOpen: () -> Unit,
    onEvent: (CalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val cal = data ?: CalendarData.EMPTY
    val minute = nowMs / 60_000
    val upcoming = remember(cal, today, minute) { CalendarLayout.upcoming(cal, today, nowMs, maxDays = if (landscape) 5 else 3, maxEvents = if (landscape) 9 else 9) }
    val env = remember(today, minute, h24, unit, weather) { CalendarEnv(today, nowMs, h24, unit, fadePast = false, weekNumbers = false, forecast = forecastByDate(weather, nowMs)) }
    val header = @Composable { m: Modifier ->
        Column(m) {
            Text(CalendarFormat.weekdayLong(today, locale), style = Kin.type.label, color = Kin.colors.accent)
            Text(CalendarFormat.dayMonth(today, locale), style = Kin.type.headline, color = Kin.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (weather != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WeatherNowInline(weather, nowMs, unit, showLabel = true)
                }
                env.forecast[today]?.let { d ->
                    val rain = app.kinframe.frame.weather.WeatherFormat.rainChance(d.precipProb)
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${app.kinframe.frame.weather.Units.tempLabel(d.minC, unit)} – ${app.kinframe.frame.weather.Units.tempLabel(d.maxC, unit)}",
                            style = Kin.type.caption, color = Kin.colors.inkSoft,
                        )
                        if (rain != null) {
                            Text(stringResource(R.string.calendar_rain, rain), style = Kin.type.caption.copy(fontWeight = FontWeight.Medium), color = RAIN, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
    Box(modifier.background(Kin.colors.paper).clickable(onClick = onOpen)) {
        if (landscape) {
            Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 22.dp, top = 30.dp, bottom = 24.dp)) {
                header(Modifier)
                Box(Modifier.padding(vertical = 18.dp).fillMaxWidth().height(1.dp).background(Kin.colors.line))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    upcoming.forEachIndexed { i, day -> SidebarDay(day, i == 0, env, onEvent) }
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
                header(Modifier.width(230.dp))
                Box(Modifier.padding(horizontal = 20.dp).width(1.dp).fillMaxHeight().background(Kin.colors.line))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    upcoming.take(2).forEachIndexed { i, day -> Box(Modifier.weight(1f)) { SidebarDay(day, i == 0, env, onEvent) } }
                }
            }
        }
    }
}

@Composable
private fun SidebarDay(day: CalendarLayout.AgendaDay, isToday: Boolean, env: CalendarEnv, onEvent: (CalEvent) -> Unit) {
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isToday) stringResource(R.string.calendar_today) else relativeDayName(day.date, env.today, locale),
                style = Kin.type.caption.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), color = Kin.colors.inkSoft,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!isToday) env.forecast[day.date]?.let { DayWeather(it, env.unit, 22.dp) }
        }
        if (day.isEmpty) {
            Text(
                stringResource(if (isToday) R.string.calendar_nothing_today else R.string.calendar_nothing),
                style = Kin.type.body.copy(fontSize = 17.sp), color = Kin.colors.inkFaint,
            )
        }
        day.all.forEach { e -> SidebarEvent(e, whenText(e, day.date, env)) { onEvent(e) } }
    }
}

/** A compact event row: colour bar, time, title. */
@Composable
private fun SidebarEvent(e: CalEvent, whenText: String, onClick: () -> Unit) {
    val sw = CalendarPalette.of(e.color)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(sw.fill).clickable(onClick = onClick)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(46.dp).background(sw.strong))
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(whenText, style = Kin.type.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = sw.strong.darken(), maxLines = 1)
            Text(e.title, style = Kin.type.bodyStrong.copy(fontSize = 17.sp, lineHeight = 21.sp), color = Kin.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
    }
}
