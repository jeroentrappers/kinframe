package app.kinframe.frame

import app.kinframe.core.api.BrightnessPoint
import app.kinframe.core.api.DisplaySettings
import app.kinframe.core.api.LocationSettings
import app.kinframe.frame.schedule.BrightnessPolicy
import app.kinframe.frame.schedule.SunPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SunAndBrightnessTest {
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun elevationMatchesKnownValues() {
        // Brussels, summer solstice, solar noon (~11:43 UTC): 90 - 50.85 + 23.44 ≈ 62.6°.
        assertEquals(62.6, SunPosition.elevation(50.85, 4.35, ms("2026-06-21T11:43:00Z")), 0.7)
        // Brussels, winter solstice, solar noon (~11:41 UTC): 90 - 50.85 - 23.44 ≈ 15.7°.
        assertEquals(15.7, SunPosition.elevation(50.85, 4.35, ms("2026-12-21T11:41:00Z")), 0.7)
        // Equator at the March equinox around noon: almost overhead.
        assertTrue(SunPosition.elevation(0.0, 0.0, ms("2026-03-20T12:07:00Z")) > 87)
        // Midnight in Brussels: well below the horizon.
        assertTrue(SunPosition.elevation(50.85, 4.35, ms("2026-10-22T23:00:00Z")) < -30)
        // Sydney summer solstice, solar noon: 90 - 33.87 + 23.44 ≈ 79.6°.
        assertEquals(79.6, SunPosition.elevation(-33.87, 151.21, ms("2026-12-21T01:55:00Z")), 1.5)
    }

    @Test
    fun sunScaledBrightness() {
        assertEquals(204, BrightnessPolicy.sunScaled(204, 30.0))
        assertEquals(204, BrightnessPolicy.sunScaled(204, 10.0))
        assertEquals(61, BrightnessPolicy.sunScaled(204, -20.0))
        val mid = BrightnessPolicy.sunScaled(204, 2.0)
        assertTrue(mid in 62..203)
        assertEquals(15, BrightnessPolicy.sunScaled(30, -10.0))
    }

    @Test
    fun modes() {
        val zone = ZoneId.of("Europe/Brussels")
        val night = ZonedDateTime.of(2026, 10, 22, 23, 30, 0, 0, zone)
        val noon = ZonedDateTime.of(2026, 10, 22, 13, 0, 0, 0, zone)
        val loc = LocationSettings("Brussels", 50.85, 4.35)
        assertEquals(204, BrightnessPolicy.target(DisplaySettings(), loc, night))
        assertEquals(61, BrightnessPolicy.target(DisplaySettings(brightnessMode = "sun"), loc, night))
        assertEquals(204, BrightnessPolicy.target(DisplaySettings(brightnessMode = "sun"), loc, noon))
        assertEquals(204, BrightnessPolicy.target(DisplaySettings(brightnessMode = "sun"), null, night))
        val sched = DisplaySettings(brightnessMode = "schedule", brightnessSchedule = listOf(BrightnessPoint("07:00", 200), BrightnessPoint("21:00", 40)))
        assertEquals(40, BrightnessPolicy.target(sched, null, night))
        assertEquals(200, BrightnessPolicy.target(sched, null, noon))
        assertEquals(40, BrightnessPolicy.target(sched, null, night.withHour(3)))
        assertEquals(15, BrightnessPolicy.target(DisplaySettings(brightness = 2), null, noon))
    }
}
