package app.kinframe.frame.schedule

import app.kinframe.core.api.SleepScheduleDto
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Sleep schedules (product spec 4.7, behaviour spec B.8): any number of rules, each with a sleep time, a wake time
 * and a weekday set. A rule's day is the day its sleep starts; a wake time at or before the sleep time means the
 * next morning.
 */
object SleepPlanner {
    data class Rule(
        val sleepAt: LocalTime,
        val wakeAt: LocalTime,
        val days: Set<DayOfWeek>,
        val enabled: Boolean = true,
        val dim: Boolean = false,
    )

    data class Window(val start: LocalDateTime, val end: LocalDateTime, val dim: Boolean)

    private val dayNames = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    fun parseTime(s: String): LocalTime? = runCatching {
        val (h, m) = s.split(":").map { it.toInt() }
        LocalTime.of(h % 24, m)
    }.getOrNull()

    fun fromSettings(list: List<SleepScheduleDto>): List<Rule> = list.mapNotNull { d ->
        val s = parseTime(d.sleepAt) ?: return@mapNotNull null
        val w = parseTime(d.wakeAt) ?: return@mapNotNull null
        Rule(s, w, d.days.mapNotNull { dayNames[it.lowercase()] }.toSet(), d.enabled, d.mode == "dim")
    }

    private fun windows(rules: List<Rule>, from: LocalDateTime, days: Long): List<Window> {
        val out = ArrayList<Window>()
        for (r in rules) {
            if (!r.enabled || r.sleepAt == r.wakeAt || r.days.isEmpty()) continue
            for (offset in -1L..days) {
                val d = from.toLocalDate().plusDays(offset)
                if (d.dayOfWeek !in r.days) continue
                val start = d.atTime(r.sleepAt)
                val end = if (r.wakeAt.isAfter(r.sleepAt)) d.atTime(r.wakeAt) else d.plusDays(1).atTime(r.wakeAt)
                out += Window(start, end, r.dim)
            }
        }
        return out.sortedBy { it.start }
    }

    /** The sleep window covering [now] (overlapping or touching windows merged), or null when awake. */
    fun activeWindow(rules: List<Rule>, now: LocalDateTime): Window? {
        val all = windows(rules, now, 8)
        var active = all.filter { !now.isBefore(it.start) && now.isBefore(it.end) }.maxByOrNull { it.end } ?: return null
        var extended = true
        while (extended) {
            extended = false
            for (w in all) {
                if (!w.start.isAfter(active.end) && w.end.isAfter(active.end)) {
                    active = active.copy(end = w.end, dim = active.dim && w.dim)
                    extended = true
                }
            }
        }
        return active
    }

    /** The next moment the sleep state changes after [now], or null if no rule is active. */
    fun nextBoundary(rules: List<Rule>, now: LocalDateTime): LocalDateTime? {
        activeWindow(rules, now)?.let { return it.end }
        return windows(rules, now, 8).map { it.start }.filter { it.isAfter(now) }.minOrNull()
    }
}
