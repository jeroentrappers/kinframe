package app.kinframe.frame

import app.kinframe.core.api.SleepScheduleDto
import app.kinframe.frame.schedule.SleepPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class SleepPlannerTest {
    private val defaults = SleepPlanner.fromSettings(listOf(SleepScheduleDto(id = "d")))
    private fun t(s: String) = LocalDateTime.parse(s)

    @Test
    fun defaultIsElevenToSevenEveryDay() {
        assertNull(SleepPlanner.activeWindow(defaults, t("2026-10-22T22:59")))
        val w = SleepPlanner.activeWindow(defaults, t("2026-10-22T23:00"))!!
        assertEquals(t("2026-10-23T07:00"), w.end)
        assertNotNull(SleepPlanner.activeWindow(defaults, t("2026-10-23T03:00")))
        assertNull(SleepPlanner.activeWindow(defaults, t("2026-10-23T07:00")))
        assertEquals(t("2026-10-22T23:00"), SleepPlanner.nextBoundary(defaults, t("2026-10-22T12:00")))
        assertEquals(t("2026-10-23T07:00"), SleepPlanner.nextBoundary(defaults, t("2026-10-23T01:00")))
    }

    @Test
    fun weekdaysApplyToTheDaySleepStarts() {
        // Only Friday night (Fri 2026-10-23): wakes Saturday morning.
        val r = SleepPlanner.fromSettings(listOf(SleepScheduleDto(sleepAt = "22:00", wakeAt = "09:00", days = listOf("fri"))))
        assertNull(SleepPlanner.activeWindow(r, t("2026-10-22T23:00"))) // Thursday
        assertNotNull(SleepPlanner.activeWindow(r, t("2026-10-24T08:00"))) // Saturday morning, Friday's window
        assertNull(SleepPlanner.activeWindow(r, t("2026-10-25T08:00")))
        assertEquals(t("2026-10-23T22:00"), SleepPlanner.nextBoundary(r, t("2026-10-22T12:00")))
    }

    @Test
    fun sameDayWindowAndDisabledRules() {
        val r = SleepPlanner.fromSettings(
            listOf(
                SleepScheduleDto(sleepAt = "13:00", wakeAt = "14:30"),
                SleepScheduleDto(sleepAt = "01:00", wakeAt = "05:00", enabled = false),
                SleepScheduleDto(sleepAt = "10:00", wakeAt = "10:00"),
            ),
        )
        assertNotNull(SleepPlanner.activeWindow(r, t("2026-10-22T13:30")))
        assertNull(SleepPlanner.activeWindow(r, t("2026-10-22T02:00")))
        assertNull(SleepPlanner.activeWindow(r, t("2026-10-22T10:00")))
    }

    @Test
    fun touchingWindowsMergeSoTheFrameDoesNotBlink() {
        val r = SleepPlanner.fromSettings(
            listOf(SleepScheduleDto(sleepAt = "22:00", wakeAt = "02:00"), SleepScheduleDto(sleepAt = "02:00", wakeAt = "06:30", mode = "dim")),
        )
        val w = SleepPlanner.activeWindow(r, t("2026-10-22T23:00"))!!
        assertEquals(t("2026-10-23T06:30"), w.end)
        assertEquals(t("2026-10-23T06:30"), SleepPlanner.nextBoundary(r, t("2026-10-22T23:00")))
        assertEquals(true, SleepPlanner.activeWindow(r, t("2026-10-23T03:00"))!!.dim)
    }

    @Test
    fun noRulesMeansAlwaysAwake() {
        assertNull(SleepPlanner.activeWindow(emptyList(), t("2026-10-22T03:00")))
        assertNull(SleepPlanner.nextBoundary(emptyList(), t("2026-10-22T03:00")))
    }
}
