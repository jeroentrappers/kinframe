package app.kinframe.frame.weather

import app.kinframe.core.api.KinJson
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * The `weather` change payload (openapi `Weather`). The server fetches it from Open-Meteo and always sends metric
 * values; [Units] converts for `locale.temperature_unit`. Times carry the location's UTC offset.
 */
@Serializable
data class Weather(
    val fetchedAt: String,
    val location: WeatherLocation? = null,
    val timezone: String? = null,
    val utcOffsetSeconds: Int? = null,
    val current: Current,
    val hourly: List<Hour> = emptyList(),
    val daily: List<Day> = emptyList(),
    /**
     * The owner's own weather station's temperature, when "use my weather station for the current temperature" is on
     * (docs/plugins.md §9.4): it replaces the current temperature; the condition and today's range stay the forecast's.
     * Never stored or sent.
     */
    @kotlinx.serialization.Transient val stationTempC: Double? = null,
) {
    @Serializable
    data class WeatherLocation(val name: String? = null, val lat: Double? = null, val lon: Double? = null)

    @Serializable
    data class Current(
        val time: String? = null,
        val tempC: Double,
        val apparentC: Double? = null,
        val code: Int,
        val isDay: Boolean = true,
        val windKmh: Double? = null,
        val windDirDeg: Int? = null,
        val humidityPct: Int? = null,
        val precipMm: Double? = null,
    )

    @Serializable
    data class Hour(
        val time: String,
        val tempC: Double,
        val code: Int,
        val isDay: Boolean = true,
        val precipProb: Int? = null,
    ) {
        val at: OffsetDateTime? get() = parseOffset(time)
    }

    @Serializable
    data class Day(
        val date: String,
        val minC: Double,
        val maxC: Double,
        val code: Int,
        val precipMm: Double? = null,
        val precipProb: Int? = null,
        val sunrise: String? = null,
        val sunset: String? = null,
    ) {
        val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
    }

    val fetchedAtMs: Long? get() = runCatching { Instant.parse(fetchedAt).toEpochMilli() }.getOrNull()
        ?: parseOffset(fetchedAt)?.toInstant()?.toEpochMilli()

    val offset: ZoneOffset get() = ZoneOffset.ofTotalSeconds((utcOffsetSeconds ?: 0).coerceIn(-18 * 3600, 18 * 3600))

    /** Older than this, the widget hides: a forecast from yesterday is worse than none. */
    fun isStale(nowMs: Long): Boolean {
        val at = fetchedAtMs ?: return true
        return nowMs - at > STALE_MS
    }

    /** The hour slot containing [nowMs], if the forecast covers it. */
    fun hourAt(nowMs: Long): Hour? = hourly.lastOrNull { h ->
        val t = h.at?.toInstant()?.toEpochMilli() ?: return@lastOrNull false
        t <= nowMs && nowMs < t + 3_600_000
    }

    /**
     * Conditions to show now. Right after a fetch that is [current]; once the data is a while old (the frame was
     * offline) the forecast for the current hour is closer to the truth.
     */
    fun nowConditions(nowMs: Long): Current {
        val c = forecastNow(nowMs)
        return stationTempC?.let { c.copy(tempC = it, apparentC = null) } ?: c
    }

    private fun forecastNow(nowMs: Long): Current {
        val age = nowMs - (fetchedAtMs ?: return current)
        if (age < HOURLY_AFTER_MS) return current
        val h = hourAt(nowMs) ?: return current
        return current.copy(tempC = h.tempC, code = h.code, isDay = h.isDay, apparentC = null)
    }

    /** Up to [count] hours from the current hour on (past hours of an older payload are dropped). */
    fun upcomingHours(nowMs: Long, count: Int = 24): List<Hour> =
        hourly.filter { h -> (h.at?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE) + 3_600_000 > nowMs }.take(count)

    /** Days from today (in the location's time zone) on. */
    fun upcomingDays(nowMs: Long, count: Int = 7): List<Day> {
        val today = Instant.ofEpochMilli(nowMs).atOffset(offset).toLocalDate()
        return daily.filter { d -> d.localDate?.let { !it.isBefore(today) } ?: false }.take(count)
    }

    fun today(nowMs: Long): Day? = upcomingDays(nowMs, 1).firstOrNull()

    companion object {
        const val STALE_MS = 6 * 3_600_000L
        private const val HOURLY_AFTER_MS = 90 * 60_000L

        /** Parses a stored or received payload; null when absent or not understood. */
        fun parse(json: String?): Weather? = json?.let { runCatching { KinJson.decodeFromString(serializer(), it) }.getOrNull() }

        fun parseOffset(s: String?): OffsetDateTime? = s?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    }
}

/** Unit handling for `locale.temperature_unit` (`c` or `f`); the payload is always metric. */
object Units {
    fun fahrenheit(unit: String?): Boolean = unit.equals("f", ignoreCase = true)

    fun temp(c: Double, unit: String?): Double = if (fahrenheit(unit)) c * 9.0 / 5.0 + 32.0 else c

    /** Whole degrees, never "-0". */
    fun tempRounded(c: Double, unit: String?): Int {
        val r = Math.round(temp(c, unit)).toInt()
        return if (r == 0) 0 else r
    }

    /** "18°" (the unit letter is implied by the frame's setting, like on any weather display). */
    fun tempLabel(c: Double, unit: String?): String = "${tempRounded(c, unit)}°"

    /** Wind speed in km/h, or mph alongside Fahrenheit. */
    fun wind(kmh: Double, unit: String?): Int = (if (fahrenheit(unit)) kmh / 1.609344 else kmh).roundToInt()

    fun windUnit(unit: String?): String = if (fahrenheit(unit)) "mph" else "km/h"

    /** Compass sector (0 = N, 1 = NE … 7 = NW) for a direction in degrees. */
    fun compassSector(deg: Int): Int = (((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8
}

/**
 * The forecast with the weather station's temperature as the current one, while that reading is fresh (the station
 * widget greys after 30 minutes, the corner falls back to the forecast at the same moment).
 */
fun Weather.withStation(station: app.kinframe.frame.plugins.StationNow?, nowMs: Long): Weather {
    val s = station?.takeIf { nowMs - it.atMs <= app.kinframe.frame.plugins.station.WeatherStation.STALE_S * 1000L } ?: return this
    return copy(stationTempC = s.tempC)
}
