package app.kinframe.frame.weather

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Formatting for the weather widget and forecast panel. Times are shown in the location's own offset. */
object WeatherFormat {
    fun time(t: OffsetDateTime, h24: Boolean, locale: Locale): String =
        t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", locale))

    /** Hour cell label: "15" or "3 PM". */
    fun hour(t: OffsetDateTime, h24: Boolean, locale: Locale): String =
        t.format(DateTimeFormatter.ofPattern(if (h24) "HH" else "h a", locale))

    /** "Thu" / "Do" (capitalised, no trailing dot). */
    fun weekday(d: LocalDate, locale: Locale): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) }

    /** Rain chance worth showing, to the nearest 10 % (below 10 % it is noise). */
    fun rainChance(p: Int?): Int? = if (p == null || p < 10) null else ((p + 5) / 10 * 10).coerceAtMost(100)

    private val stops = listOf(
        -10.0 to Color(0xFF7BA9FF),
        0.0 to Color(0xFF86CFF3),
        8.0 to Color(0xFF8EDDC0),
        15.0 to Color(0xFFD6E27A),
        21.0 to Color(0xFFFFCB5C),
        27.0 to Color(0xFFFF9A4D),
        34.0 to Color(0xFFFF6247),
    )

    /** A colour for a temperature in °C, cool blue to warm red, for the week's range bars. */
    fun tempColor(c: Double): Color {
        if (c <= stops.first().first) return stops.first().second
        if (c >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= c }
        val (t0, c0) = stops[i]
        val (t1, c1) = stops[i + 1]
        return lerp(c0, c1, ((c - t0) / (t1 - t0)).toFloat())
    }
}
