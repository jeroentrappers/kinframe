package app.kinframe.frame.schedule

import app.kinframe.core.api.DisplaySettings
import app.kinframe.core.api.LocationSettings
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Target backlight (15..255) for the current moment (product spec 4.7). */
object BrightnessPolicy {
    const val MIN = 15
    const val MAX = 255

    fun target(display: DisplaySettings, location: LocationSettings?, now: ZonedDateTime): Int {
        val manual = display.brightness.coerceIn(MIN, MAX)
        return when (display.brightnessMode) {
            "schedule" -> scheduled(display, now) ?: manual
            "sun" -> {
                val lat = location?.lat
                val lon = location?.lon
                if (lat == null || lon == null) manual
                else sunScaled(manual, SunPosition.elevation(lat, lon, now.toInstant().toEpochMilli()))
            }
            else -> manual // "manual", and "sensor" on hardware without a light sensor
        }
    }

    /**
     * Full brightness in daylight (sun ≥ 10°), 30 % (never below [MIN]) at night (sun ≤ −6°, civil twilight),
     * linear in between.
     */
    fun sunScaled(day: Int, elevationDeg: Double): Int {
        val night = (day * 0.3).roundToInt().coerceAtLeast(MIN)
        val t = ((elevationDeg + 6.0) / 16.0).coerceIn(0.0, 1.0)
        return (night + (day - night) * t).roundToInt().coerceIn(MIN, MAX)
    }

    private fun scheduled(display: DisplaySettings, now: ZonedDateTime): Int? {
        val points = display.brightnessSchedule.mapNotNull { p -> SleepPlanner.parseTime(p.at)?.let { it to p.brightness } }
            .sortedBy { it.first }
        if (points.isEmpty()) return null
        val t = now.toLocalTime()
        val current = points.lastOrNull { !it.first.isAfter(t) } ?: points.last()
        return current.second.coerceIn(MIN, MAX)
    }
}
