package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.time.LocalDate

/**
 * The 4-day view: an agenda per day, side by side in landscape and stacked in portrait. Each day shows its forecast
 * large, then all-day events, then timed events as cards. Easy to read from across the room.
 */
@Composable
internal fun FourDayView(
    data: CalendarData,
    days: List<LocalDate>,
    env: CalendarEnv,
    landscape: Boolean,
    onEvent: (CalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agenda = remember(data, days) { days.map { CalendarLayout.agendaDay(data, it) } }
    if (landscape) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            agenda.forEach { a -> AgendaColumn(a, data, env, onEvent, Modifier.weight(1f).fillMaxHeight()) }
        }
    } else {
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            agenda.forEach { a -> AgendaRow(a, data, env, onEvent) }
        }
    }
}

@Composable
private fun AgendaColumn(a: CalendarLayout.AgendaDay, data: CalendarData, env: CalendarEnv, onEvent: (CalEvent) -> Unit, modifier: Modifier) {
    val isToday = a.date == env.today
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (isToday) Kin.colors.surface else CARD_QUIET)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        AgendaHeader(a.date, env)
        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Kin.colors.line))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgendaEvents(a, data, env, onEvent)
        }
    }
}

@Composable
private fun AgendaRow(a: CalendarLayout.AgendaDay, data: CalendarData, env: CalendarEnv, onEvent: (CalEvent) -> Unit) {
    val isToday = a.date == env.today
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (isToday) Kin.colors.surface else CARD_QUIET)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Box(Modifier.width(210.dp)) { AgendaHeader(a.date, env) }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgendaEvents(a, data, env, onEvent)
        }
    }
}

@Composable
private fun AgendaHeader(d: LocalDate, env: CalendarEnv) {
    val locale = currentLocale()
    val isToday = d == env.today
    Column {
        Text(
            when (d) {
                env.today -> stringResource(R.string.calendar_today)
                env.today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
                else -> CalendarFormat.weekdayLong(d, locale)
            },
            style = Kin.type.caption.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = if (isToday) Kin.colors.accent else Kin.colors.inkSoft,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CalendarFormat.dayMonth(d, locale), style = Kin.type.title, color = Kin.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        env.forecast[d]?.let { day -> DayWeather(day, env.unit, 34.dp, Modifier.padding(top = 4.dp), large = true) }
    }
}

@Composable
private fun AgendaEvents(a: CalendarLayout.AgendaDay, data: CalendarData, env: CalendarEnv, onEvent: (CalEvent) -> Unit) {
    if (a.isEmpty) {
        Text(stringResource(R.string.calendar_nothing), style = Kin.type.body, color = Kin.colors.inkFaint, modifier = Modifier.padding(top = 4.dp))
        return
    }
    // Busy days use compact cards so the whole day fits without scrolling.
    val compact = a.all.size > 5
    a.all.forEach { e ->
        val past = env.fadePast && e.isPast(env.nowMs, data.zone)
        EventCard(e, whenText(e, a.date, env), past, Modifier.fillMaxWidth(), compact = compact) { onEvent(e) }
    }
}

/** Other days' cards: a step deeper than the paper so today's white card stands out. */
private val CARD_QUIET = androidx.compose.ui.graphics.Color(0xFFF6EFE6)
