package app.kinframe.frame.birthdays

import app.kinframe.frame.birthdays.BalloonPlanner.Companion.HOUR_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BalloonPlannerTest {
    private val day = LocalDate.of(2027, 3, 12)
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun firstAppearanceThenHourlyAlignedToIt() {
        val p = BalloonPlanner()
        assertTrue(p.tick(t0, day, cardShown = true, awake = true, canRun = true))
        assertFalse(p.tick(t0 + 10 * min, day, true, true, true))
        assertFalse(p.tick(t0 + HOUR_MS - 1, day, true, true, true))
        assertTrue(p.tick(t0 + HOUR_MS + 5_000, day, true, true, true))
        assertFalse("once per hour", p.tick(t0 + HOUR_MS + 20_000, day, true, true, true))
        assertTrue(p.tick(t0 + 2 * HOUR_MS, day, true, true, true))
    }

    @Test
    fun theFirstRunWaitsForAVideoButHourlyRunsAreSkipped() {
        val p = BalloonPlanner()
        assertFalse(p.tick(t0, day, true, true, canRun = false))
        assertFalse(p.tick(t0 + 5 * min, day, true, true, canRun = false))
        assertTrue("runs once the video ended", p.tick(t0 + 6 * min, day, true, true, canRun = true))
        val first = t0 + 6 * min
        // The next slot is busy for longer than the grace: skipped, not queued.
        assertFalse(p.tick(first + HOUR_MS, day, true, true, canRun = false))
        assertFalse(p.tick(first + HOUR_MS + 5 * min, day, true, true, canRun = true))
        assertTrue(p.tick(first + 2 * HOUR_MS + 30_000, day, true, true, canRun = true))
    }

    @Test
    fun aFirstAppearanceGivesUpAfterAnHour() {
        val p = BalloonPlanner()
        assertFalse(p.tick(t0, day, true, true, canRun = false))
        assertFalse(p.tick(t0 + HOUR_MS + min, day, true, true, canRun = true))
    }

    @Test
    fun neverAsleepOrDimmedOrWithoutCard() {
        val p = BalloonPlanner()
        assertFalse(p.tick(t0, day, cardShown = true, awake = false, canRun = true))
        assertFalse(p.tick(t0, day, cardShown = false, awake = true, canRun = true))
        assertFalse("clock not trusted", p.tick(t0, null, cardShown = true, awake = true, canRun = true))
    }

    @Test
    fun wakingCountsAsFirstAppearanceOnlyAfterAnHourWithoutBalloons() {
        val p = BalloonPlanner()
        assertTrue(p.tick(t0, day, true, true, true))
        p.tick(t0 + 10 * min, day, true, awake = false, canRun = true) // screen off by hand
        assertFalse("woke 20 min after the last run", p.tick(t0 + 20 * min, day, true, true, true))
        p.tick(t0 + 30 * min, day, true, awake = false, canRun = true)
        // Slept through the hourly slot; wakes 2 h after the last run.
        assertTrue(p.tick(t0 + 2 * HOUR_MS + 10 * min, day, true, true, true))
        assertFalse(p.tick(t0 + 2 * HOUR_MS + 11 * min, day, true, true, true))
        assertTrue("realigned to the wake-up", p.tick(t0 + 3 * HOUR_MS + 10 * min, day, true, true, true))
    }

    @Test
    fun midnightStartsANewDay() {
        val p = BalloonPlanner()
        assertTrue(p.tick(t0, day, true, true, true))
        assertTrue("a screen that is on at midnight", p.tick(t0 + 5 * min, day.plusDays(1), true, true, true))
    }

    @Test
    fun anEditOnTheDayDoesNotRerun() {
        val p = BalloonPlanner()
        var runs = 0
        for (i in 0 until 30) if (p.tick(t0 + i * 10_000L, day, true, true, true)) runs++
        assertEquals(1, runs)
    }
}
