package app.kinframe.frame.weather

import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A realistic autumn payload for Gent, fetched at 14:05 local time on 22 October 2026. */
object WeatherFixtures {
    val offset: ZoneOffset = ZoneOffset.ofHours(2)
    val fetched: OffsetDateTime = OffsetDateTime.of(2026, 10, 22, 14, 5, 0, 0, offset)
    /** 14:20 local, a quarter of an hour after the fetch. */
    val nowMs: Long = fetched.plusMinutes(15).toInstant().toEpochMilli()

    fun gent(code: Int = 2, isDay: Boolean = true, tempC: Double = 14.6): Weather {
        val start = fetched.withMinute(0)
        val temps = listOf(14.6, 15.1, 15.0, 14.2, 13.1, 12.0, 11.2, 10.6, 10.1, 9.7, 9.3, 9.0, 8.8, 8.5, 8.3, 8.2, 8.4, 9.1, 10.2, 11.4, 12.5, 13.3, 13.8, 14.0)
        val codes = listOf(2, 2, 3, 3, 61, 61, 63, 3, 3, 2, 1, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 80, 80, 3)
        val probs = listOf(5, 10, 25, 40, 70, 75, 80, 45, 20, 10, 5, 0, 0, 0, 0, 0, 5, 10, 15, 20, 35, 55, 60, 30)
        val hourly = temps.indices.map { i ->
            val t = start.plusHours(i.toLong())
            Weather.Hour(t.toString(), temps[i], codes[i], isDay = t.hour in 8..18, precipProb = probs[i])
        }
        val days = listOf(
            Triple(8.2, 15.4, 61), Triple(7.1, 13.0, 80), Triple(5.0, 11.2, 3), Triple(3.4, 10.8, 1),
            Triple(4.8, 12.6, 2), Triple(6.9, 16.1, 0), Triple(9.2, 17.4, 95),
        )
        val dailyProbs = listOf(80, 65, 20, 5, 10, 0, 70)
        val daily = days.mapIndexed { i, (mn, mx, code) ->
            val d = fetched.toLocalDate().plusDays(i.toLong())
            Weather.Day(
                date = d.toString(), minC = mn, maxC = mx, code = code, precipMm = if (dailyProbs[i] > 30) 4.2 else 0.0,
                precipProb = dailyProbs[i],
                sunrise = d.atTime(8, 21).atOffset(offset).toString(), sunset = d.atTime(18, 40).atOffset(offset).toString(),
            )
        }
        return Weather(
            fetchedAt = fetched.toInstant().toString(),
            location = Weather.WeatherLocation("Gent", 51.05, 3.72),
            timezone = "Europe/Brussels",
            utcOffsetSeconds = 7200,
            current = Weather.Current(
                time = start.toString(), tempC = tempC, apparentC = tempC - 1.8, code = code, isDay = isDay,
                windKmh = 17.6, windDirDeg = 235, humidityPct = 74, precipMm = 0.0,
            ),
            hourly = hourly,
            daily = daily,
        )
    }
}
