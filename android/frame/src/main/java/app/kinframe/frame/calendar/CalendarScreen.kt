package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.CalendarSettings
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.R
import app.kinframe.frame.weather.Weather
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The full-screen calendar (product spec 4.9): month, week and 4-day views of every calendar merged, with the
 * forecast in each day's header and the current weather in the title bar. Paper style, translate-free (no blur, no
 * animation beyond the slideshow's own), so it stays smooth on the RK3126.
 */
@Composable
fun CalendarScreen(
    data: CalendarData?,
    weather: Weather?,
    nowMs: Long,
    zone: ZoneId,
    settings: CalendarSettings,
    firstDayOfWeek: String,
    unit: String,
    h24: Boolean,
    syncing: Boolean,
    onEvent: (CalEvent) -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialView: CalendarView = CalendarView.of(settings.view),
    initialAnchor: LocalDate? = null,
) {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    var view by remember(settings.view) { mutableStateOf(initialView) }
    var anchor by remember { mutableStateOf(initialAnchor ?: today) }
    val first = CalendarLayout.firstDayOfWeek(firstDayOfWeek)
    val cal = data ?: CalendarData.EMPTY
    val env = remember(today, nowMs / 60_000, h24, unit, settings.fadePast, settings.weekNumbers, weather) {
        CalendarEnv(today, nowMs, h24, unit, settings.fadePast, settings.weekNumbers, forecastByDate(weather, nowMs))
    }
    val locale = currentLocale()
    val start = CalendarLayout.rangeStart(view, anchor, first, settings.startOnToday, today)
    val days = remember(view, start) { List(CalendarLayout.dayCount(view)) { start.plusDays(it.toLong()) } }
    val title = when (view) {
        CalendarView.Month -> CalendarFormat.monthYear(start, locale)
        else -> CalendarFormat.range(days.first(), days.last(), locale)
    }
    val subtitle = if (settings.weekNumbers && view != CalendarView.Month) {
        stringResource(R.string.calendar_week_number, CalendarLayout.weekNumber(days.first(), first))
    } else null
    val showingToday = when (view) {
        CalendarView.Month -> YearMonth.from(start) == YearMonth.from(today)
        else -> today in days
    }
    var drag by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxSize().background(Kin.colors.paper)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Header(
                title, subtitle, weather, nowMs, unit, view, showingToday, syncing, landscape,
                onView = { view = it; anchor = today },
                onPrev = { anchor = CalendarLayout.step(view, start, -1) },
                onToday = { anchor = today },
                onNext = { anchor = CalendarLayout.step(view, start, 1) },
                onSync = onSync, onClose = onClose,
            )
            val countdowns = remember(cal, nowMs / 60_000) { cal.upcomingCountdowns(nowMs, if (landscape) 4 else 2) }
            if (countdowns.isNotEmpty()) CountdownBar(countdowns, today, Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(start = if (view == CalendarView.Week) 8.dp else 24.dp, end = 24.dp, bottom = 20.dp)
                    .pointerInput(view, start) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (abs(drag) > size.width * 0.15f) anchor = CalendarLayout.step(view, start, if (drag < 0) 1 else -1)
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { change, dx -> change.consume(); drag += dx }
                    },
            ) {
                if (data == null || (cal.sources.isEmpty() && cal.isEmpty)) {
                    EmptyCalendar(Modifier.align(Alignment.Center))
                } else when (view) {
                    CalendarView.Month -> MonthView(
                        cal, YearMonth.from(start), first, env, onEvent,
                        onDay = { d -> view = CalendarView.FourDay; anchor = d }, modifier = Modifier.fillMaxSize(),
                    )
                    CalendarView.Week -> WeekView(cal, days, env, onEvent, onDay = { d -> view = CalendarView.FourDay; anchor = d }, modifier = Modifier.fillMaxSize())
                    CalendarView.FourDay -> FourDayView(cal, days, env, landscape, onEvent, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String?,
    weather: Weather?,
    nowMs: Long,
    unit: String,
    view: CalendarView,
    showingToday: Boolean,
    syncing: Boolean,
    landscape: Boolean,
    onView: (CalendarView) -> Unit,
    onPrev: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val titleBlock = @Composable { m: Modifier ->
        Column(m) {
            Text(title, style = Kin.type.headline, color = Kin.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
    }
    val nav = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderIcon(KinIcons.ChevronLeft, stringResource(R.string.calendar_previous), onPrev)
            Text(
                stringResource(R.string.calendar_today), style = Kin.type.label,
                color = if (showingToday) Kin.colors.inkFaint else Kin.colors.accent,
                modifier = Modifier.clip(CircleShape).border(1.dp, Kin.colors.line, CircleShape).clickable(onClick = onToday)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HeaderIcon(KinIcons.ChevronRight, stringResource(R.string.calendar_next), onNext)
        }
    }
    val actions = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderIcon(CalendarIcons.Sync, stringResource(if (syncing) R.string.calendar_syncing else R.string.calendar_sync_now), onSync, tint = if (syncing) Kin.colors.inkFaint else Kin.colors.ink)
            HeaderIcon(KinIcons.Close, stringResource(R.string.calendar_close), onClose)
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            titleBlock(Modifier.weight(1f))
            if (weather != null) WeatherNowInline(weather, nowMs, unit, Modifier.padding(end = 20.dp).widthIn(max = 280.dp))
            ViewSwitch(view, onView)
            Spacer(Modifier.size(16.dp))
            nav()
            Spacer(Modifier.size(10.dp))
            actions()
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                titleBlock(Modifier.weight(1f))
                if (weather != null) WeatherNowInline(weather, nowMs, unit, Modifier.padding(end = 8.dp), showLabel = false)
                actions()
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ViewSwitch(view, onView)
                Spacer(Modifier.weight(1f))
                nav()
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Kin.colors.ink) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KinIcon(icon, label, tint = tint, size = 26.dp)
    }
}

/** Segmented control for the three views. */
@Composable
private fun ViewSwitch(view: CalendarView, onView: (CalendarView) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Kin.colors.paperDeep).padding(4.dp)) {
        listOf(
            CalendarView.Month to R.string.calendar_view_month,
            CalendarView.Week to R.string.calendar_view_week,
            CalendarView.FourDay to R.string.calendar_view_four_day,
        ).forEach { (v, label) ->
            val selected = v == view
            Text(
                stringResource(label), style = Kin.type.label, color = if (selected) Kin.colors.ink else Kin.colors.inkSoft,
                modifier = Modifier.clip(CircleShape).then(if (selected) Modifier.background(Kin.colors.surface) else Modifier)
                    .clickable { onView(v) }.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** Countdowns at the top of the calendar: colour dot, days left, title. */
@Composable
private fun CountdownBar(items: List<CountdownItem>, today: LocalDate, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { c ->
            val sw = CalendarPalette.of(c.color)
            val days = ChronoUnit.DAYS.between(today, c.day).toInt()
            Row(
                Modifier.clip(CircleShape).background(Kin.colors.surface).border(1.dp, Kin.colors.line, CircleShape)
                    .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(sw.fill), contentAlignment = Alignment.Center) {
                    KinIcon(CalendarIcons.Hourglass, null, tint = sw.strong.darken(0.8f), size = 16.dp)
                }
                Text(
                    countdownShort(days), style = Kin.type.label.copy(fontWeight = FontWeight.Bold), color = Kin.colors.ink,
                    modifier = Modifier.padding(start = 10.dp),
                )
                Text(c.title, style = Kin.type.label.copy(fontWeight = FontWeight.Normal), color = Kin.colors.inkSoft, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
internal fun countdownShort(days: Int): String = when {
    days <= 0 -> stringResource(R.string.countdown_today)
    days == 1 -> stringResource(R.string.countdown_tomorrow)
    else -> pluralStringResource(R.plurals.countdown_in_days, days, days)
}

@Composable
private fun EmptyCalendar(modifier: Modifier) {
    Column(modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(Kin.colors.accentSoft), contentAlignment = Alignment.Center) {
            KinIcon(CalendarIcons.Calendar, null, tint = Kin.colors.accent, size = 48.dp)
        }
        Text(stringResource(R.string.calendar_empty_title), style = Kin.type.headline, modifier = Modifier.padding(top = 24.dp))
        Text(
            stringResource(R.string.calendar_empty_body), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
