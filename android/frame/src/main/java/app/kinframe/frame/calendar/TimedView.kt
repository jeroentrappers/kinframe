package app.kinframe.frame.calendar

import app.kinframe.core.api.CalendarScheduleDto
import app.kinframe.frame.schedule.SleepPlanner
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Calendar timed view (behaviour spec B.8): schedules with the sleep-schedule shape during which the frame shows the
 * full-screen calendar instead of photos. The window math is [SleepPlanner]'s; only the decision is added here.
 */
object TimedView {
    private val dayNames = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    fun rules(list: List<CalendarScheduleDto>): List<SleepPlanner.Rule> = list.mapNotNull { d ->
        val from = SleepPlanner.parseTime(d.from) ?: return@mapNotNull null
        val to = SleepPlanner.parseTime(d.to) ?: return@mapNotNull null
        SleepPlanner.Rule(from, to, d.days.mapNotNull { dayNames[it.lowercase()] }.toSet(), d.enabled)
    }

    fun active(list: List<CalendarScheduleDto>, now: LocalDateTime): SleepPlanner.Window? = SleepPlanner.activeWindow(rules(list), now)

    fun nextBoundary(list: List<CalendarScheduleDto>, now: LocalDateTime): LocalDateTime? = SleepPlanner.nextBoundary(rules(list), now)

    /** What the controller should do with the full-screen calendar. */
    enum class Action { None, Open, Close }

    /**
     * Opens the calendar when a window starts and closes it when the window ends, but only if the timed view opened
     * it. Like every scheduled action, it waits until [deferMs] after the last touch ([sinceTouchMs], null when the
     * frame was never touched), so it never yanks the screen from someone using it. A window the person closed by
     * hand ([dismissed]) stays closed until the next one.
     */
    fun decide(inWindow: Boolean, openByTimer: Boolean, calendarOpen: Boolean, sinceTouchMs: Long?, dismissed: Boolean = false, deferMs: Long = 60_000): Action {
        if (sinceTouchMs != null && sinceTouchMs < deferMs) return Action.None
        return when {
            inWindow && !calendarOpen && !dismissed -> Action.Open
            !inWindow && openByTimer && calendarOpen -> Action.Close
            else -> Action.None
        }
    }
}
