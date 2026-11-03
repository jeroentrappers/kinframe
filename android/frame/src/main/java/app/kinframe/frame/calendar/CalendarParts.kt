package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.R
import app.kinframe.frame.weather.Units
import app.kinframe.frame.weather.Weather
import app.kinframe.frame.weather.WeatherCodes
import app.kinframe.frame.weather.WeatherFormat
import app.kinframe.frame.weather.WeatherIcon
import java.time.LocalDate
import java.util.Locale

/** Everything a calendar view needs besides the events: passed down as one stable object. */
@Immutable
data class CalendarEnv(
    val today: LocalDate,
    val nowMs: Long,
    val h24: Boolean,
    val unit: String,
    val fadePast: Boolean,
    val weekNumbers: Boolean,
    /** Forecast per local date (only the days the forecast covers). */
    val forecast: Map<LocalDate, Weather.Day>,
)

@Composable
internal fun currentLocale(): Locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT

/** Forecast days by date; empty without (fresh) weather. */
fun forecastByDate(weather: Weather?, nowMs: Long): Map<LocalDate, Weather.Day> =
    weather?.upcomingDays(nowMs)?.mapNotNull { d -> d.localDate?.let { it to d } }?.toMap() ?: emptyMap()

private val chipShape = RoundedCornerShape(6.dp)

/**
 * An event as a bar: a soft fill in the source colour with a strong edge at the start. All-day and multi-day
 * events use it in month rows and the all-day strip; the rounded corners are dropped where the bar continues.
 */
@Composable
internal fun EventBar(
    e: CalEvent,
    label: String,
    past: Boolean,
    continuesBefore: Boolean,
    continuesAfter: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sw = CalendarPalette.of(e.color)
    val shape = RoundedCornerShape(
        topStart = if (continuesBefore) 0.dp else 6.dp, bottomStart = if (continuesBefore) 0.dp else 6.dp,
        topEnd = if (continuesAfter) 0.dp else 6.dp, bottomEnd = if (continuesAfter) 0.dp else 6.dp,
    )
    Row(
        modifier.clip(shape).background(if (past) sw.fillPast else sw.fill).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!continuesBefore) Box(Modifier.width(4.dp).fillMaxHeight().background(if (past) sw.strong.copy(alpha = 0.45f) else sw.strong))
        if (e.local) {
            KinIcon(KinIcons.HeartFilled, null, tint = sw.strong, size = 13.dp, modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            label, style = Kin.type.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = if (past) Kin.colors.inkFaint else Kin.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
        )
    }
}

/** A single-day timed event as a line in a month cell: colour dot, start time, title. */
@Composable
internal fun EventLine(e: CalEvent, time: String, past: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sw = CalendarPalette.of(e.color)
    Row(modifier.clip(chipShape).clickable(onClick = onClick).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (past) sw.strong.copy(alpha = 0.4f) else sw.strong))
        if (time.isNotEmpty()) {
            Text(
                time, style = Kin.type.caption.copy(fontSize = 13.sp), color = if (past) Kin.colors.inkFaint else Kin.colors.inkSoft,
                maxLines = 1, modifier = Modifier.padding(start = 5.dp),
            )
        }
        Text(
            e.title, style = Kin.type.caption.copy(fontSize = 14.sp), color = if (past) Kin.colors.inkFaint else Kin.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp, end = 2.dp),
        )
    }
}

/**
 * A card for one event in the 4-day and sidebar lists: colour edge, time on its own line, title, place.
 * [compact] drops the place and uses one line for time and title.
 */
@Composable
internal fun EventCard(e: CalEvent, whenText: String, past: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val sw = CalendarPalette.of(e.color)
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(if (past) sw.fillPast else sw.fill).clickable(onClick = onClick)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(if (past) sw.strong.copy(alpha = 0.45f) else sw.strong))
        Column(Modifier.padding(start = 12.dp, end = 10.dp, top = if (compact) 7.dp else 9.dp, bottom = if (compact) 7.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.local) KinIcon(KinIcons.HeartFilled, null, tint = sw.strong, size = 14.dp, modifier = Modifier.padding(end = 6.dp))
                Text(
                    whenText, style = Kin.type.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = if (past) Kin.colors.inkFaint else sw.strong.darken(), maxLines = 1,
                )
            }
            Text(
                e.title, style = Kin.type.bodyStrong.copy(fontSize = if (compact) 17.sp else 18.sp, lineHeight = if (compact) 22.sp else 24.sp),
                color = if (past) Kin.colors.inkFaint else Kin.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!compact && e.location != null) {
                Text(
                    e.location, style = Kin.type.caption.copy(fontSize = 14.sp), color = if (past) Kin.colors.inkFaint else Kin.colors.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A darker shade of a palette colour for text on its own soft fill (keeps AA contrast). */
internal fun Color.darken(f: Float = 0.62f): Color = Color(red * f, green * f, blue * f, alpha)

/** Forecast for one day: icon, max/min and the rain chance when it matters. */
@Composable
internal fun DayWeather(day: Weather.Day, unit: String, iconSize: Dp, modifier: Modifier = Modifier, vertical: Boolean = false, large: Boolean = false) {
    val rain = WeatherFormat.rainChance(day.precipProb)
    val tempStyle = (if (large) Kin.type.bodyStrong else Kin.type.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
    val icon = @Composable { WeatherIcon(WeatherCodes.glyph(day.code, true), iconSize, onPaper = true) }
    val temps = @Composable {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Units.tempLabel(day.maxC, unit), style = tempStyle, color = Kin.colors.ink)
            Text(
                " " + Units.tempLabel(day.minC, unit), style = tempStyle.copy(fontWeight = FontWeight.Normal), color = Kin.colors.inkFaint,
            )
        }
    }
    val rainText = @Composable {
        if (rain != null) {
            Text(
                stringResource(R.string.calendar_rain, rain), style = Kin.type.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = RAIN, maxLines = 1,
            )
        }
    }
    if (vertical) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { icon(); temps(); rainText() }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon(); temps()
            if (rain != null) Box(Modifier.padding(start = 2.dp)) { rainText() }
        }
    }
}

internal val RAIN = Color(0xFF3B82C4)

/** Current conditions for the calendar header: icon, temperature, condition. */
@Composable
internal fun WeatherNowInline(weather: Weather, nowMs: Long, unit: String, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val now = weather.nowConditions(nowMs)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 44.dp, onPaper = true)
        Text(
            Units.tempLabel(now.tempC, unit), style = Kin.type.title.copy(fontWeight = FontWeight.Normal), color = Kin.colors.ink,
            modifier = Modifier.padding(start = 6.dp),
        )
        if (showLabel) {
            Text(
                stringResource(WeatherCodes.label(now.code, now.isDay)), style = Kin.type.caption, color = Kin.colors.inkSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/** Day-relative name: "Today", "Tomorrow" or the weekday with its date. */
@Composable
internal fun relativeDayName(d: LocalDate, today: LocalDate, locale: Locale): String = when (d) {
    today -> stringResource(R.string.calendar_today)
    today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
    else -> "${CalendarFormat.weekdayLong(d, locale)} ${CalendarFormat.dayMonth(d, locale)}"
}

/** "Received events" for the local calendar, else the source's name. */
@Composable
internal fun sourceLabel(name: String, local: Boolean): String =
    if (local && name.isBlank()) stringResource(R.string.calendar_received) else name

@Composable
internal fun whenText(e: CalEvent, day: LocalDate?, env: CalendarEnv): String =
    CalendarFormat.whenLabel(LocalContext.current, e, day, env.h24, currentLocale())
