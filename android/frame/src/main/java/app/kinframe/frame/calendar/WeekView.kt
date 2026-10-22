package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val ALL_DAY_LANE = 26.dp
private const val ALL_DAY_MAX_LANES = 3

/**
 * Week view: one column per day with its forecast in the header, an all-day strip with spanning bars, and a
 * timeline where overlapping events sit side by side. The visible hours are fitted to the week's events, so nothing
 * needs scrolling; a line marks the current time.
 */
@Composable
internal fun WeekView(
    data: CalendarData,
    days: List<LocalDate>,
    env: CalendarEnv,
    onEvent: (CalEvent) -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val hours = remember(data, days) { CalendarLayout.hourRange(days, data) }
    val blocks = remember(data, days) { days.map { CalendarLayout.timedBlocks(it, data.on(it)) } }
    val spanning = remember(data, days) {
        val bars = CalendarLayout.packBars(days, days.flatMap { d -> data.on(d).filter { it.spans } }.distinct())
        CalendarLayout.clampLanes(bars, days.size, ALL_DAY_MAX_LANES)
    }
    val lanes = (spanning.visible.maxOfOrNull { it.lane + 1 } ?: 0).let { v -> if (spanning.hiddenPerCol.any { it > 0 }) ALL_DAY_MAX_LANES else v }
    val gutter = 56.dp
    val line = Kin.colors.line
    val narrow = days.size > 4
    Column(modifier) {
        // Day headers.
        Row(Modifier.fillMaxWidth().padding(start = gutter)) {
            days.forEach { d -> DayHeader(d, env, narrow, Modifier.weight(1f).clickable { onDay(d) }) }
        }
        // All-day strip.
        if (lanes > 0) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(ALL_DAY_LANE * lanes + 8.dp).padding(top = 4.dp)) {
                val colW = (maxWidth - gutter) / days.size
                Text(
                    stringResource(R.string.calendar_all_day), style = Kin.type.caption.copy(fontSize = 12.sp), color = Kin.colors.inkFaint,
                    maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.width(gutter - 8.dp).padding(top = 3.dp),
                )
                spanning.visible.forEach { b ->
                    val past = env.fadePast && b.event.isPast(env.nowMs, data.zone)
                    EventBar(
                        b.event, b.event.title, past, b.continuesBefore, b.continuesAfter,
                        Modifier.offset(x = gutter + colW * b.startCol + 2.dp, y = ALL_DAY_LANE * b.lane)
                            .width(colW * b.span - 4.dp).height(ALL_DAY_LANE - 4.dp),
                    ) { onEvent(b.event) }
                }
                spanning.hiddenPerCol.forEachIndexed { col, n ->
                    if (n > 0) {
                        Text(
                            pluralStringResource(R.plurals.calendar_more, n, n), style = Kin.type.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = Kin.colors.inkSoft,
                            modifier = Modifier.offset(x = gutter + colW * col + 8.dp, y = ALL_DAY_LANE * (ALL_DAY_MAX_LANES - 1) + 2.dp).clickable { onDay(days[col]) },
                        )
                    }
                }
            }
        }
        // Timeline.
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).drawBehind {
                val n = hours.last - hours.first
                val h = size.height / n
                val g = gutter.toPx()
                val rtl = layoutDirection == LayoutDirection.Rtl
                val ti = days.indexOf(env.today)
                if (ti >= 0) {
                    val cw0 = (size.width - g) / days.size
                    val x0 = if (rtl) size.width - g - (ti + 1) * cw0 else g + ti * cw0
                    drawRect(TODAY_WASH, Offset(x0, 0f), androidx.compose.ui.geometry.Size(cw0, size.height))
                }
                for (i in 0..n) {
                    val y = i * h
                    if (rtl) drawLine(line, Offset(0f, y), Offset(size.width - g, y), 1f) else drawLine(line, Offset(g, y), Offset(size.width, y), 1f)
                }
                val cw = (size.width - g) / days.size
                for (c in 0..days.size) {
                    val x = if (rtl) size.width - g - c * cw else g + c * cw
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                }
            },
        ) {
            val n = hours.last - hours.first
            val hourH = maxHeight / n
            val colW = (maxWidth - gutter) / days.size
            val perMin = hourH / 60f
            // Hour labels.
            for (i in 0 until n) {
                Text(
                    CalendarFormat.time(days.first().atTime(LocalTime.of(hours.first + i, 0)).atZone(data.zone), env.h24, locale)
                        .let { if (env.h24) it else it.replace(":00", "") },
                    style = Kin.type.caption.copy(fontSize = 13.sp), color = Kin.colors.inkFaint, textAlign = TextAlign.End,
                    modifier = Modifier.offset(y = hourH * i - 8.dp).width(gutter - 10.dp),
                )
            }
            days.forEachIndexed { di, d ->
                val isToday = d == env.today
                blocks[di].forEach { b ->
                    val top = perMin * (b.startMin - hours.first * 60).coerceAtLeast(0)
                    val h = (perMin * (b.endMin - maxOf(b.startMin, hours.first * 60))).coerceAtLeast(24.dp)
                    val w = colW / b.cols
                    val past = env.fadePast && b.event.isPast(env.nowMs, data.zone)
                    TimedBlockView(
                        b.event, past, h, w, env,
                        Modifier.offset(x = gutter + colW * di + w * b.col + 2.dp, y = top + 1.dp).width(w - 4.dp).height(h - 2.dp),
                    ) { onEvent(b.event) }
                }
                if (isToday) {
                    val now = Instant.ofEpochMilli(env.nowMs).atZone(data.zone).toLocalTime()
                    val m = now.hour * 60 + now.minute
                    if (m in hours.first * 60..hours.last * 60) {
                        val y = perMin * (m - hours.first * 60)
                        Box(Modifier.offset(x = gutter + colW * di, y = y - 1.dp).width(colW).height(2.dp).background(Kin.colors.accent))
                        Box(Modifier.offset(x = gutter + colW * di - 5.dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(Kin.colors.accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimedBlockView(e: CalEvent, past: Boolean, height: Dp, width: Dp, env: CalendarEnv, modifier: Modifier, onClick: () -> Unit) {
    val sw = CalendarPalette.of(e.color)
    val locale = currentLocale()
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(if (past) sw.fillPast else sw.fill).clickable(onClick = onClick)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (past) sw.strong.copy(alpha = 0.45f) else sw.strong))
        // Too narrow for words (three overlapping events in a portrait week): colour only, details on tap.
        if (width < 48.dp) return@Row
        Column(Modifier.padding(start = 6.dp, end = 4.dp, top = if (height < 30.dp) 2.dp else 4.dp)) {
            val titleStyle = Kin.type.caption.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            val lines = ((height.value - 8) / 17).toInt().coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.local) KinIcon(KinIcons.HeartFilled, null, tint = sw.strong, size = 12.dp, modifier = Modifier.padding(end = 4.dp))
                Text(
                    e.title, style = titleStyle, color = if (past) Kin.colors.inkFaint else Kin.colors.ink,
                    maxLines = if (lines >= 3) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (lines >= 2) {
                val time = e.start?.let { CalendarFormat.time(it, env.h24, locale) } ?: ""
                Text(
                    time, style = Kin.type.caption.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = if (past) Kin.colors.inkFaint else sw.strong.darken(), maxLines = 1,
                )
            }
            if (lines >= 4 && e.location != null) {
                Text(e.location, style = Kin.type.caption.copy(fontSize = 12.sp, lineHeight = 15.sp), color = Kin.colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Column header: weekday, date (today in an accent circle) and the day's forecast. */
@Composable
internal fun DayHeader(d: LocalDate, env: CalendarEnv, narrow: Boolean, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val isToday = d == env.today
    Column(modifier.padding(horizontal = 2.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                CalendarFormat.weekdayShort(d, locale), style = Kin.type.caption.copy(fontWeight = FontWeight.Medium),
                color = if (isToday) Kin.colors.accent else Kin.colors.inkSoft,
            )
            Box(
                Modifier.size(34.dp).clip(CircleShape).then(if (isToday) Modifier.background(Kin.colors.accent) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${d.dayOfMonth}", style = Kin.type.bodyStrong.copy(fontSize = 18.sp),
                    color = if (isToday) Color.White else Kin.colors.ink,
                )
            }
        }
        val day = env.forecast[d]
        Box(Modifier.height(if (narrow) 26.dp else 30.dp), contentAlignment = Alignment.Center) {
            if (day != null) DayWeather(day, env.unit, if (narrow) 24.dp else 28.dp)
        }
    }
}

/** Today's column: a warm wash, opaque-composited so it costs one fill. */
private val TODAY_WASH = Color(0xFFFBEFE6)
