package app.kinframe.frame.weather

import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.LocationSettings
import app.kinframe.core.api.MergePatch
import app.kinframe.core.api.WidgetSettings
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.ValueView
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.FramePlugins
import app.kinframe.frame.plugins.InstanceConfig
import app.kinframe.frame.plugins.station.StationReading
import app.kinframe.frame.plugins.station.StationViews
import app.kinframe.frame.plugins.station.WindUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The forecast and the weather station each have a switch; with both on, the temperature shows once (docs/plugins.md §9.4). */
class WeatherSourcesTest {
    private val ghent = LocationSettings("Gent", 51.05, 3.72)
    private val station = InstanceConfig(FramePlugins.STATION_INSTANCE, FramePlugins.WEATHER_STATION, settings = JsonObject(mapOf("device" to JsonPrimitive("9287156658"))))
    private val p1 = InstanceConfig(FramePlugins.P1_INSTANCE, FramePlugins.HOMEWIZARD_P1)
    private val nl = PluginFormat(Locale.forLanguageTag("nl"), ZoneId.of("Europe/Brussels"))
    private val t: (String) -> String = { it }
    private val r = StationReading(1_792_941_451_000L, tempC = 12.8, humidityPct = 56.0, windMs = 2.3, windDirDeg = 253.0, pressureHpa = 988.8, rainTodayMm = 15.1)

    private fun parse(j: JsonObject) = (PluginViewParser.parse(j) as ViewResult.Valid).view

    @Test fun forecastNeedsTheSwitchAndAPlace() {
        assertTrue(WeatherSources.forecastOn(FrameSettings(widgets = WidgetSettings(weather = true), location = ghent)))
        assertFalse(WeatherSources.forecastOn(FrameSettings(widgets = WidgetSettings(weather = true), location = null)))
        assertFalse(WeatherSources.forecastOn(FrameSettings(widgets = WidgetSettings(weather = false), location = ghent)))
    }

    @Test fun theSwitchSendsAMergePatchOfOneField() {
        val on = WeatherSources.forecastPatch(true)
        assertEquals(setOf("widgets"), on.keys)
        assertEquals(setOf("weather"), on["widgets"]!!.jsonObject.keys)
        assertTrue(on["widgets"]!!.jsonObject["weather"]!!.jsonPrimitive.boolean)
        // Applied like the server does, it changes only that field.
        val before = kotlinx.serialization.json.buildJsonObject {
            put("widgets", kotlinx.serialization.json.buildJsonObject { put("weather", JsonPrimitive(true)); put("clock", JsonPrimitive(true)) })
        }
        val after = MergePatch.apply(before, WeatherSources.forecastPatch(false)).jsonObject["widgets"]!!.jsonObject
        assertFalse(after["weather"]!!.jsonPrimitive.boolean)
        assertTrue(after["clock"]!!.jsonPrimitive.boolean)
    }

    @Test fun withTheForecastOnTheStationLeavesItsTemperatureOut() {
        val both = WeatherSources.forHost(listOf(p1, station), forecastOn = true)
        assertEquals(p1, both[0])
        assertEquals("true", (both[1].settings[WeatherSources.HIDE_TEMP] as JsonPrimitive).content)
        assertEquals("9287156658", (both[1].settings["device"] as JsonPrimitive).content)
        // Switching the forecast off gives the station its temperature back (a changed config restarts the instance).
        val stationOnly = WeatherSources.forHost(both, forecastOn = false)
        assertNull(stationOnly[1].settings[WeatherSources.HIDE_TEMP])
        assertEquals(station, stationOnly[1])
    }

    @Test fun compactWithoutTemperatureLeadsWithTheWind() {
        val v = parse(StationViews.compact(r, null, WindUnit.Ms, nl, t, withTemp = false)) as ValueView
        assertEquals("2,3\u00A0m/s", v.value)
        assertEquals("direction", v.icon)
        assertEquals("56%", v.label)
        // No wind: the humidity on its own; nothing at all: a dash, still a valid view.
        val humidityOnly = parse(StationViews.compact(r.copy(windMs = null, windAvgMs = null), null, WindUnit.Ms, nl, t, withTemp = false)) as ValueView
        assertEquals("56%", humidityOnly.value)
        assertEquals("–", (parse(StationViews.compact(StationReading(r.atMs), null, WindUnit.Ms, nl, t, withTemp = false)) as ValueView).value)
    }

    @Test fun rowsWithoutTemperatureDropOnlyThatRow() {
        val rows = parse(StationViews.rows(r, null, null, WindUnit.Ms, nl, t, withTemp = false)) as RowsView
        assertEquals(listOf("2,3\u00A0m/s", "15,1\u00A0mm", "56%"), rows.items.map { it.value })
        // A station with only a thermometer: a dash.
        assertEquals("–", (parse(StationViews.rows(StationReading(r.atMs, tempC = 3.0), null, null, WindUnit.Ms, nl, t, withTemp = false)) as ValueView).value)
    }

}
