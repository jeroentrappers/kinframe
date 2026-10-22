package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val DAY_HEAD = 34.dp
private val LANE = 23.dp

/**
 * Month grid: whole weeks, all calendars merged. All-day and multi-day events are bars that span their days (lanes
 * are packed per week row); single-day timed events are dot-time-title lines. What does not fit becomes "+N more".
 * The forecast sits in the corner of each day it covers. Tapping a day opens it in the 4-day view.
 */
@Composable
internal fun MonthView(
    data: CalendarData,
    month: YearMonth,
    first: DayOfWeek,
    env: CalendarEnv,
    onEvent: (CalEvent) -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val weeks = remember(month, first) { CalendarLayout.monthWeeks(month, first) }
    val rows = remember(data, weeks) { weeks.map { w -> CalendarLayout.packBars(w, w.flatMap { data.on(it) }.distinct()) } }
    val gutter = if (env.weekNumbers) 44.dp else 0.dp
    val line = Kin.colors.line
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(30.dp).padding(start = gutter), verticalAlignment = Alignment.CenterVertically) {
            weeks.first().forEach { d ->
                Text(
                    CalendarFormat.weekdayShort(d, locale), style = Kin.type.caption.copy(fontWeight = FontWeight.Medium),
                    color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().drawBehind {
                // Grid lines: one pass, no per-cell borders.
                val g = gutter.toPx()
                val cw = (size.width - g) / 7f
                val rh = size.height / weeks.size
                for (i in 0..weeks.size) drawLine(line, Offset(g, i * rh), Offset(size.width, i * rh), 1f)
                for (c in 1 until 7) {
                    val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - g - c * cw else g + c * cw
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                }
            },
        ) {
            val rowH = maxHeight / weeks.size
            val cellW = (maxWidth - gutter) / 7
            val maxLanes = ((rowH - DAY_HEAD - 4.dp) / LANE).toInt().coerceAtLeast(1)
            val compact = cellW < 140.dp
            Column(Modifier.fillMaxSize()) {
                weeks.forEachIndexed { i, week ->
                    val layout = remember(rows[i], maxLanes) { CalendarLayout.clampLanes(rows[i], 7, maxLanes) }
                    Box(Modifier.height(rowH).fillMaxWidth()) {
                        Row(Modifier.fillMaxSize()) {
                            if (env.weekNumbers) {
                                Text(
                                    "${CalendarLayout.weekNumber(week.first(), first)}", style = Kin.type.caption.copy(fontSize = 13.sp),
                                    color = Kin.colors.inkFaint, textAlign = TextAlign.Center, modifier = Modifier.width(gutter).padding(top = 8.dp),
                                )
                            }
                            week.forEach { d -> DayCell(d, d.month == month.month, env, cellW, compact) { onDay(d) } }
                        }
                        layout.visible.forEach { b ->
                            val e = b.event
                            val past = env.fadePast && e.isPast(env.nowMs, data.zone)
                            Box(
                                Modifier.offset(x = gutter + cellW * b.startCol + 3.dp, y = DAY_HEAD + LANE * b.lane)
                                    .width(cellW * b.span - 6.dp).height(LANE - 3.dp),
                            ) {
                                if (e.spans) {
                                    EventBar(e, e.title, past, b.continuesBefore, b.continuesAfter, Modifier.fillMaxSize()) { onEvent(e) }
                                } else {
                                    // Narrow cells (portrait) give the room to the title.
                                    val time = if (compact) "" else e.start?.let { CalendarFormat.time(it, env.h24, locale) } ?: ""
                                    EventLine(e, time, past, Modifier.fillMaxSize()) { onEvent(e) }
                                }
                            }
                        }
                        layout.hiddenPerCol.forEachIndexed { col, n ->
                            if (n > 0) {
                                Text(
                                    pluralStringResource(R.plurals.calendar_more, n, n),
                                    style = Kin.type.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = Kin.colors.inkSoft,
                                    modifier = Modifier.offset(x = gutter + cellW * col + 8.dp, y = DAY_HEAD + LANE * (maxLanes - 1) + 1.dp)
                                        .clickable { onDay(week[col]) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, env: CalendarEnv, width: Dp, compact: Boolean, onClick: () -> Unit) {
    val isToday = d == env.today
    Box(
        Modifier.width(width).fillMaxHeight()
            // Inset by the grid line so the wash never covers it.
            .then(if (!inMonth) Modifier.padding(1.dp).background(OUT_OF_MONTH) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().height(DAY_HEAD).padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).then(if (isToday) Modifier.background(Kin.colors.accent) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${d.dayOfMonth}",
                    style = Kin.type.caption.copy(fontSize = 15.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium),
                    color = when {
                        isToday -> Color.White
                        !inMonth -> Kin.colors.inkFaint
                        else -> Kin.colors.ink
                    },
                )
            }
            Box(Modifier.weight(1f))
            env.forecast[d]?.let { day ->
                if (compact) {
                    app.kinframe.frame.weather.WeatherIcon(app.kinframe.frame.weather.WeatherCodes.glyph(day.code, true), 22.dp, onPaper = true)
                } else {
                    DayWeather(day, env.unit, 22.dp)
                }
            }
        }
    }
}

/** Days of the neighbouring months: a faint wash (opaque, precomposited over the paper). */
private val OUT_OF_MONTH = Color(0xFFF5EEE5)
