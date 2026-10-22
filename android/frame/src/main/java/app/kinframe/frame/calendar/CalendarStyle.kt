package app.kinframe.frame.calendar

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.CalendarColors
import app.kinframe.frame.R
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Event colours on the paper style. Each palette key gives a strong colour (the source bar, dots) and a soft fill
 * already composited over the card colour: opaque, so chips never need alpha blending on the Mali-400.
 */
object CalendarPalette {
    private val surface = Color(0xFFFFFDFA)
    private val cache = HashMap<String, Swatch>()

    data class Swatch(val strong: Color, val fill: Color, val fillPast: Color, val onDark: Color)

    fun of(key: String?): Swatch = synchronized(cache) {
        cache.getOrPut(key ?: "") {
            val s = CalendarColors.swatch(key)
            val strong = Color(s.light)
            Swatch(
                strong = strong,
                fill = strong.copy(alpha = 0.16f).compositeOver(surface),
                fillPast = strong.copy(alpha = 0.07f).compositeOver(surface),
                onDark = Color(s.dark),
            )
        }
    }
}

/** Date and time labels for the calendar. Times are shown in the frame's zone (already applied to [CalEvent]). */
object CalendarFormat {
    fun time(t: ZonedDateTime, h24: Boolean, locale: Locale): String =
        t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", locale))

    /** "Thu" / "do" without the trailing dot, capitalised. */
    fun weekdayShort(d: LocalDate, locale: Locale): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) }

    fun weekdayLong(d: LocalDate, locale: Locale): String =
        d.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }

    /** "October 2026" / "oktober 2026" in the locale's own order (ICU skeleton). */
    fun monthYear(d: LocalDate, locale: Locale): String = skeleton(locale, "MMMMyyyy", d).replaceFirstChar { it.titlecase(locale) }

    /** "22 October" / "October 22". */
    fun dayMonth(d: LocalDate, locale: Locale): String = skeleton(locale, "dMMMM", d)

    /** "Thursday 22 October". */
    fun longDay(d: LocalDate, locale: Locale): String = skeleton(locale, "EEEEdMMMM", d).replaceFirstChar { it.titlecase(locale) }

    /** "19 – 25 Oct 2026" style range for the week header. */
    fun range(from: LocalDate, to: LocalDate, locale: Locale): String = when {
        from.year != to.year -> "${skeleton(locale, "dMMMyyyy", from)} – ${skeleton(locale, "dMMMyyyy", to)}"
        else -> "${skeleton(locale, "dMMM", from)} – ${skeleton(locale, "dMMMyyyy", to)}"
    }

    private fun skeleton(locale: Locale, skeleton: String, d: LocalDate): String {
        val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull() ?: "d MMMM yyyy"
        return DateTimeFormatter.ofPattern(pattern.replace("LLLL", "MMMM").replace("LLL", "MMM"), locale).format(d)
    }

    /**
     * When an event happens, for lists and details: "All day", "14:00 – 15:30", "from 21:40" / "until 09:10" on the
     * first and last day of a multi-day event, or a date range for multi-day all-day events.
     */
    fun whenLabel(context: Context, e: CalEvent, day: LocalDate?, h24: Boolean, locale: Locale): String {
        if (e.allDay) {
            return if (e.multiDay && day == null) {
                context.getString(R.string.calendar_time_range, dayMonth(e.firstDay, locale), dayMonth(e.lastDay, locale))
            } else context.getString(R.string.calendar_all_day)
        }
        val s = e.start ?: return ""
        val t = e.end ?: s
        if (!e.multiDay) return if (t == s) time(s, h24, locale) else context.getString(R.string.calendar_time_range, time(s, h24, locale), time(t, h24, locale))
        return when (day) {
            null -> context.getString(
                R.string.calendar_time_range,
                "${weekdayShort(s.toLocalDate(), locale)} ${time(s, h24, locale)}", "${weekdayShort(t.toLocalDate(), locale)} ${time(t, h24, locale)}",
            )
            e.firstDay -> context.getString(R.string.calendar_from, time(s, h24, locale))
            e.lastDay -> context.getString(R.string.calendar_until, time(t, h24, locale))
            else -> context.getString(R.string.calendar_all_day)
        }
    }

    /** The details sheet's date line: "Thursday 22 October" or "22 – 24 October". */
    fun dateLabel(context: Context, e: CalEvent, locale: Locale): String =
        if (e.multiDay) context.getString(R.string.calendar_time_range, longDay(e.firstDay, locale), longDay(e.lastDay, locale))
        else longDay(e.firstDay, locale)
}

/** A few line icons the calendar needs, in the same style as `KinIcons` (24 grid, 2 px round strokes). */
object CalendarIcons {
    private fun line(name: String, vararg d: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        d.forEach { path ->
            b.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Calendar by lazy { line("calendar", "M4 6h16v14H4z", "M4 10h16", "M8 3v4", "M16 3v4") }
    val Sync by lazy { line("sync", "M20 11a8 8 0 0 0-14.3-4.6", "M4 3v4h4", "M4 13a8 8 0 0 0 14.3 4.6", "M20 21v-4h-4") }
    val Pin by lazy { line("pin", "M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z", "M12 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z") }
    val Notes by lazy { line("notes", "M5 5h14", "M5 10h14", "M5 15h9") }
    val Repeat by lazy { line("repeat", "M4 11V9a3 3 0 0 1 3-3h12", "M16 3l3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H5", "M8 21l-3-3 3-3") }
    val Hourglass by lazy { line("hourglass", "M7 3h10", "M7 21h10", "M8 3c0 5 8 5 8 9s-8 4-8 9", "M16 3c0 5-8 5-8 9s8 4 8 9") }
    val Today by lazy { line("today", "M4 6h16v14H4z", "M4 10h16", "M8 3v4", "M16 3v4", "M11 14h2v2h-2z") }
}
