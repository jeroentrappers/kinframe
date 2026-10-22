package app.kinframe.frame.weather

import app.kinframe.frame.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeatherTest {
    // ------------------------------------------------------------------ WMO codes

    @Test fun glyphsFollowWmoGroupsWithDayNightVariants() {
        assertEquals(WeatherGlyph.Sun, WeatherCodes.glyph(0, isDay = true))
        assertEquals(WeatherGlyph.Moon, WeatherCodes.glyph(0, isDay = false))
        assertEquals(WeatherGlyph.PartlyCloudyDay, WeatherCodes.glyph(1, true))
        assertEquals(WeatherGlyph.PartlyCloudyNight, WeatherCodes.glyph(2, false))
        assertEquals(WeatherGlyph.Cloud, WeatherCodes.glyph(3, true))
        listOf(45, 48).forEach { assertEquals(WeatherGlyph.Fog, WeatherCodes.glyph(it, true)) }
        listOf(51, 53, 55).forEach { assertEquals(WeatherGlyph.Drizzle, WeatherCodes.glyph(it, true)) }
        listOf(56, 57, 66, 67).forEach { assertEquals(WeatherGlyph.Sleet, WeatherCodes.glyph(it, true)) }
        listOf(61, 63, 80, 81).forEach { assertEquals(WeatherGlyph.Rain, WeatherCodes.glyph(it, true)) }
        listOf(65, 82).forEach { assertEquals(WeatherGlyph.HeavyRain, WeatherCodes.glyph(it, true)) }
        listOf(71, 73, 75, 77, 85, 86).forEach { assertEquals(WeatherGlyph.Snow, WeatherCodes.glyph(it, false)) }
        listOf(95, 96, 99).forEach { assertEquals(WeatherGlyph.Thunder, WeatherCodes.glyph(it, false)) }
        // Night only changes the clear and partly cloudy icons.
        assertEquals(WeatherGlyph.Rain, WeatherCodes.glyph(61, false))
    }

    @Test fun unknownCodesFallBackByRange() {
        assertEquals(WeatherGlyph.Fog, WeatherCodes.glyph(44, true))
        assertEquals(WeatherGlyph.Rain, WeatherCodes.glyph(58, true))
        assertEquals(WeatherGlyph.Snow, WeatherCodes.glyph(79, true))
        assertEquals(WeatherGlyph.Thunder, WeatherCodes.glyph(97, true))
        assertEquals(WeatherGlyph.Cloud, WeatherCodes.glyph(-1, true))
        assertEquals(R.string.wmo_snow, WeatherCodes.label(79, true))
        assertEquals(R.string.wmo_overcast, WeatherCodes.label(150, true))
    }

    @Test fun labelsCoverEveryDocumentedCode() {
        val documented = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        documented.forEach { code -> assertTrue("code $code", WeatherCodes.label(code, true) != 0) }
        assertEquals(R.string.wmo_clear_day, WeatherCodes.label(0, true))
        assertEquals(R.string.wmo_clear_night, WeatherCodes.label(0, false))
        assertEquals(R.string.wmo_thunderstorm_hail, WeatherCodes.label(99, true))
        assertEquals(R.string.wmo_freezing_rain, WeatherCodes.label(67, true))
    }

    // ------------------------------------------------------------------ units

    @Test fun temperatureConversionAndRounding() {
        assertEquals(32.0, Units.temp(0.0, "f"), 1e-9)
        assertEquals(212.0, Units.temp(100.0, "F"), 1e-9)
        assertEquals(-40.0, Units.temp(-40.0, "f"), 1e-9)
        assertEquals(21.5, Units.temp(21.5, "c"), 1e-9)
        assertEquals("15°", Units.tempLabel(14.6, "c"))
        assertEquals("58°", Units.tempLabel(14.6, "f"))
        assertEquals("0°", Units.tempLabel(-0.4, "c")) // never "-0°"
        assertEquals("-1°", Units.tempLabel(-0.6, "c"))
        assertEquals("1°", Units.tempLabel(0.5, "c"))
        assertEquals("72°", Units.tempLabel(22.2, "f"))
    }

    @Test fun windFollowsTheTemperatureUnit() {
        assertEquals(18, Units.wind(17.6, "c"))
        assertEquals("km/h", Units.windUnit("c"))
        assertEquals(11, Units.wind(17.6, "f"))
        assertEquals("mph", Units.windUnit("f"))
    }

    @Test fun compassSectors() {
        assertEquals(0, Units.compassSector(0))
        assertEquals(0, Units.compassSector(359))
        assertEquals(0, Units.compassSector(22))
        assertEquals(1, Units.compassSector(23))
        assertEquals(2, Units.compassSector(90))
        assertEquals(5, Units.compassSector(235))
        assertEquals(7, Units.compassSector(-45))
    }

    @Test fun rainChanceRoundsAndHidesNoise() {
        assertNull(WeatherFormat.rainChance(null))
        assertNull(WeatherFormat.rainChance(9))
        assertEquals(10, WeatherFormat.rainChance(10))
        assertEquals(20, WeatherFormat.rainChance(15))
        assertEquals(100, WeatherFormat.rainChance(100))
    }

    // ------------------------------------------------------------------ freshness and windows

    @Test fun staleAfterSixHours() {
        val w = WeatherFixtures.gent()
        val fetched = w.fetchedAtMs!!
        assertFalse(w.isStale(fetched))
        assertFalse(w.isStale(fetched + 6 * 3_600_000L))
        assertTrue(w.isStale(fetched + 6 * 3_600_000L + 1))
        assertTrue(w.copy(fetchedAt = "garbage").isStale(fetched))
    }

    @Test fun currentConditionsComeFromTheForecastOnceOld() {
        val w = WeatherFixtures.gent(code = 2, tempC = 14.6)
        val fetched = w.fetchedAtMs!!
        assertEquals(14.6, w.nowConditions(fetched + 30 * 60_000L).tempC, 1e-9)
        // Four hours later (frame was offline): the 18:00 slot (13.1 °C, rain) is closer to the truth.
        val later = w.nowConditions(fetched + 4 * 3_600_000L)
        assertEquals(13.1, later.tempC, 1e-9)
        assertEquals(61, later.code)
        assertNull(later.apparentC)
        assertEquals(74, later.humidityPct) // non-hourly fields stay
    }

    @Test fun upcomingHoursDropThePast() {
        val w = WeatherFixtures.gent()
        val fetched = w.fetchedAtMs!!
        val now = w.upcomingHours(fetched)
        assertEquals(24, now.size)
        assertEquals(14, now.first().at!!.hour)
        val later = w.upcomingHours(fetched + 3 * 3_600_000L)
        assertEquals(17, later.first().at!!.hour) // 17:05 is inside the 17:00 slot
        assertEquals(21, later.size)
    }

    @Test fun upcomingDaysStartTodayInTheLocationsZone() {
        val w = WeatherFixtures.gent()
        assertEquals(LocalDate.of(2026, 10, 22), w.today(w.fetchedAtMs!!)!!.localDate)
        val nextDay = w.upcomingDays(w.fetchedAtMs!! + 12 * 3_600_000L) // 02:05 on the 23rd, local time
        assertEquals(LocalDate.of(2026, 10, 23), nextDay.first().localDate)
        assertEquals(6, nextDay.size)
    }

    // ------------------------------------------------------------------ parsing

    @Test fun parsesTheServerPayload() {
        val json = """
            {"fetched_at":"2026-10-22T12:05:00Z","location":{"name":"Gent","lat":51.05,"lon":3.72},
             "timezone":"Europe/Brussels","utc_offset_seconds":7200,
             "units":{"temperature":"c","wind_speed":"km/h","precipitation":"mm"},
             "current":{"time":"2026-10-22T14:00:00+02:00","temp_c":14.6,"apparent_c":12.8,"code":2,"is_day":true,
                        "wind_kmh":17.6,"wind_dir_deg":235,"humidity_pct":74,"precip_mm":0},
             "hourly":[{"time":"2026-10-22T14:00:00+02:00","temp_c":14.6,"code":2,"is_day":true,"precip_prob":5}],
             "daily":[{"date":"2026-10-22","min_c":8.2,"max_c":15.4,"code":61,"precip_mm":4.2,"precip_prob":80,
                       "sunrise":"2026-10-22T08:21:00+02:00","sunset":"2026-10-22T18:40:00+02:00"}],
             "something_new":{"ignored":true}}
        """.trimIndent()
        val w = assertNotNullAndGet(Weather.parse(json))
        assertEquals("Gent", w.location?.name)
        assertEquals(12.8, w.current.apparentC!!, 1e-9)
        assertEquals(235, w.current.windDirDeg)
        assertEquals(74, w.current.humidityPct)
        assertEquals(5, w.hourly.single().precipProb)
        assertEquals(80, w.daily.single().precipProb)
        assertEquals(8, Weather.parseOffset(w.daily.single().sunrise)!!.hour)
        assertEquals(7200, w.offset.totalSeconds)
    }

    @Test fun minimalAndBrokenPayloads() {
        val minimal = Weather.parse("""{"fetched_at":"2026-10-22T12:05:00Z","current":{"temp_c":3,"code":71}}""")
        assertNotNull(minimal)
        assertTrue(minimal!!.current.isDay)
        assertTrue(minimal.hourly.isEmpty())
        assertNull(minimal.today(minimal.fetchedAtMs!!))
        assertNull(Weather.parse(null))
        assertNull(Weather.parse("{}"))
        assertNull(Weather.parse("not json"))
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
