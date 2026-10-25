package app.kinframe.frame.ui

import android.content.Context
import app.kinframe.frame.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    /** "48213907" → "4821 3907". */
    fun code(code: String): String = if (code.length == 8) code.substring(0, 4) + " " + code.substring(4) else code

    /** Caption date: today, yesterday, n days ago, "12 March", "12 March 2024". */
    fun relativeDate(context: Context, epochMs: Long, nowMs: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(d, today)
        val locale = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        return when {
            days <= 0L -> context.getString(R.string.date_today)
            days == 1L -> context.getString(R.string.date_yesterday)
            days < 7L -> context.resources.getQuantityString(R.plurals.date_days_ago, days.toInt(), days.toInt())
            d.year == today.year -> skeleton(locale, "dMMMM", epochMs, zone)
            else -> skeleton(locale, "dMMMMyyyy", epochMs, zone)
        }
    }

    /** "11 h 58 min" / "42 min". */
    fun remaining(context: Context, ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) context.getString(R.string.duration_h_min, h, m) else context.getString(R.string.duration_min, m)
    }

    fun clock(nowMs: Long, zone: ZoneId, h24: Boolean, locale: Locale): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm", locale))

    fun longDate(nowMs: Long, zone: ZoneId, locale: Locale): String =
        skeleton(locale, "EEEEdMMMM", nowMs, zone).replaceFirstChar { it.titlecase(locale) }

    /** The date in the narrow band column, two short lines: "Zo" / "25 okt". */
    fun shortDate(nowMs: Long, zone: ZoneId, locale: Locale): String =
        skeleton(locale, "EEE", nowMs, zone).trimEnd('.').replaceFirstChar { it.titlecase(locale) } + "\n" + skeleton(locale, "dMMM", nowMs, zone)

    /** Localised date from a skeleton via ICU (correct order and month case, e.g. "donderdag 22 oktober"). */
    private fun skeleton(locale: Locale, skeleton: String, epochMs: Long, zone: ZoneId): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        // ICU may answer with stand-alone letters (Russian "cccc", "LLLL") that a plain JVM SimpleDateFormat
        // rejects; the format-context letters read just as well in a full date.
        val fmt = runCatching { java.text.SimpleDateFormat(pattern, locale) }
            .getOrElse { java.text.SimpleDateFormat(pattern.replace('c', 'E').replace('L', 'M'), locale) }
        return fmt.apply { timeZone = java.util.TimeZone.getTimeZone(zone.id) }
            .format(java.util.Date(epochMs))
    }

    fun mediumDate(date: LocalDate, locale: Locale): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    /** Interval presets (behaviour spec B.2). */
    val intervalPresets = listOf(5, 15, 30, 60, 300, 600, 1800, 3600, 86400)

    fun interval(context: Context, seconds: Int): String = when {
        seconds < 60 -> context.getString(R.string.interval_s, seconds)
        seconds < 3600 -> context.getString(R.string.interval_min, seconds / 60)
        seconds < 86400 -> context.getString(R.string.interval_h, seconds / 3600)
        else -> context.getString(R.string.interval_h, seconds / 3600)
    }
}
