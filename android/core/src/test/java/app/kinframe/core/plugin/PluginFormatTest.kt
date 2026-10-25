package app.kinframe.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class PluginFormatTest {
    private val nbsp = PluginFormat.NBSP
    private val en = PluginFormat(Locale.ENGLISH, ZoneId.of("Europe/Brussels"))
    private val nl = PluginFormat(Locale.forLanguageTag("nl"), ZoneId.of("Europe/Brussels"))

    @Test fun power() {
        assertEquals("850${nbsp}W", en.power(850.0))
        assertEquals("1.2${nbsp}kW", en.power(1234.0))
        assertEquals("1,2${nbsp}kW", nl.power(1234.0))
        assertEquals("12${nbsp}kW", nl.power(12_345.0))
        assertEquals("-2,4${nbsp}kW", nl.power(-2400.0))
        assertEquals("0${nbsp}W", en.power(-0.3))
        assertEquals("1,5${nbsp}MW", nl.power(1_500_000.0))
        // 999.6 W rounds up into kW, never "1000 W".
        assertEquals("1.0${nbsp}kW", en.power(999.6))
    }

    @Test fun energyAndVolume() {
        assertEquals("450${nbsp}Wh", en.energy(0.45))
        assertEquals("3,45${nbsp}kWh", nl.energy(3.45))
        assertEquals("12,3${nbsp}kWh", nl.energy(12.34))
        assertEquals("1.234${nbsp}kWh", nl.energy(1234.0))
        assertEquals("1,24${nbsp}m³", nl.volume(1.24))
    }

    @Test fun timeAndDuration() {
        assertEquals("23:10", en.time(java.time.Instant.parse("2025-10-25T21:10:00Z").toEpochMilli())) // CEST
        assertEquals("2${nbsp}h 15${nbsp}min", en.duration(8100))
        assertEquals("45${nbsp}s", en.duration(45))
    }

    @Test fun numbersAndTemperatures() {
        assertEquals("2,3${nbsp}m/s", nl.number(2.26, 1, "m/s"))
        assertEquals("989${nbsp}hPa", nl.number(988.8, 0, "hPa"))
        assertEquals("1.024", nl.number(1024.0))
        assertEquals("0", en.number(-0.2))
        assertEquals("12,8°", nl.temperature(12.84, decimals = 1))
        assertEquals("13°", nl.temperature(12.84))
        // The frame's unit is the default; a plugin can still ask for one explicitly.
        val us = PluginFormat(Locale.US, ZoneId.of("America/New_York"), h24 = false, temperatureUnit = "f")
        assertEquals("55°", us.temperature(12.8))
        assertEquals("13°", us.temperature(12.8, unit = "c"))
        assertEquals("W/m²", PluginFormat.splitUnit(nl.number(31.7, 0, "W/m²")).second)
        assertEquals("mm/h", PluginFormat.splitUnit(nl.number(2.0, 1, "mm/h")).second)
    }

    @Test fun splitUnit() {
        assertEquals("1,2" to "kW", PluginFormat.splitUnit("1,2${nbsp}kW"))
        assertEquals("1,24" to "m³", PluginFormat.splitUnit("1,24 m³"))
        assertEquals("68%" to null, PluginFormat.splitUnit("68%"))
        assertEquals("12 34" to null, PluginFormat.splitUnit("12 34"))
        assertEquals("Charging" to null, PluginFormat.splitUnit("Charging"))
    }
}
