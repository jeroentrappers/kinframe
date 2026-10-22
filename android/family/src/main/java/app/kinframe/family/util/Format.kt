package app.kinframe.family.util

import android.content.Context
import android.text.format.DateUtils
import app.kinframe.family.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formatting shared by the screens. Everything is locale-aware through the platform. */
object Format {
    fun instant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** "5 minutes ago", "yesterday", … */
    fun relative(iso: String?, now: Long = System.currentTimeMillis()): String {
        val t = instant(iso) ?: return ""
        val ms = t.toEpochMilli()
        if (now - ms in 0..59_000) return DateUtils.getRelativeTimeSpanString(now, now, DateUtils.MINUTE_IN_MILLIS).toString()
        return DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

    /** Medium date, e.g. "22 Oct 2026". */
    fun date(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant(iso)?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(it.atZone(zone)) } ?: ""

    fun time(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant(iso)?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(it.atZone(zone)) } ?: ""

    /** History section header: Today / Yesterday / a date. */
    fun day(context: Context, iso: String?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = instant(iso)?.atZone(zone)?.toLocalDate() ?: return ""
        return when (d) {
            today -> context.getString(R.string.history_today)
            today.minusDays(1) -> context.getString(R.string.history_yesterday)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault()).format(d)
        }
    }

    fun bytes(context: Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

    /** 83_000 → "1:23", 3_725_000 → "1:02:05". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** A whole-unit duration in words: "30 seconds", "5 minutes", "1 hour". */
    fun duration(context: Context, seconds: Long): String {
        val r = context.resources
        return when {
            seconds % 86_400 == 0L && seconds >= 86_400 -> (seconds / 86_400).toInt().let { r.getQuantityString(R.plurals.duration_days, it, it) }
            seconds % 3600 == 0L && seconds >= 3600 -> (seconds / 3600).toInt().let { r.getQuantityString(R.plurals.duration_hours, it, it) }
            seconds % 60 == 0L && seconds >= 60 -> (seconds / 60).toInt().let { r.getQuantityString(R.plurals.duration_minutes, it, it) }
            else -> seconds.toInt().let { r.getQuantityString(R.plurals.duration_seconds, it, it) }
        }
    }
}
