package app.kinframe.frame.plugins

import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.TextView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.Trend
import app.kinframe.core.plugin.ValueView
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.station.StationHistory
import app.kinframe.frame.plugins.station.StationReading
import app.kinframe.frame.plugins.station.StationUnits
import app.kinframe.frame.plugins.station.StationViews
import app.kinframe.frame.plugins.station.WeatherStation
import app.kinframe.frame.plugins.station.StationInfo
import app.kinframe.frame.plugins.station.UploadData
import app.kinframe.frame.plugins.station.UploadSource
import app.kinframe.frame.plugins.station.WindUnit
import app.kinframe.frame.weather.WeatherFixtures
import app.kinframe.frame.weather.withStation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The built-in weather station widget (docs/plugins.md §9.4): parsing, units, history, views, and a full render. */
class WeatherStationTest {
    /**
     * `GET /v1/stations/latest` on the frame's server, for the owner's station's upload of 2026-10-23 (the values it
     * showed on Weathercloud that day), the station's own clock included.
     */
    private val latest =
        """{"received_at":"2026-10-23T09:57:35Z","reading":{"station_time":"2026-10-23T09:57:31Z","temp_c":12.8,"humidity_pct":56,"dew_point_c":4.2,"feels_like_c":12.8,"pressure_hpa":988.8,"wind_ms":2.3,"wind_dir_deg":253,"rain_rate_mm_h":0,"rain_today_mm":15.1,"solar_wm2":31.7,"uv_index":0},"today":{"temp_min_c":8.1,"temp_max_c":13.0,"gust_max_ms":6.2}}"""

    private val zone = ZoneId.of("Europe/Brussels")
    private val nl = PluginFormat(Locale.forLanguageTag("nl"), zone)
    private val nbsp = PluginFormat.NBSP
    private val t: (String) -> String = { key -> if (key.startsWith("compass_")) listOf("N", "NO", "O", "ZO", "Z", "ZW", "W", "NW")[key.last() - '0'] else key }
    private val measured = java.time.Instant.parse("2026-10-23T09:57:31Z").toEpochMilli()

    private fun parse(j: JsonElement): PluginView = when (val r = PluginViewParser.parse(j)) {
        is ViewResult.Valid -> r.view
        is ViewResult.Invalid -> { fail("invalid view: $r\n$j"); throw AssertionError() }
    }

    // ------------------------------------------------------------------ parsing

    @Test fun mapsTheLatestReading() {
        val r = UploadData.parse(latest)
        assertEquals(measured, r.atMs)
        assertEquals(12.8, r.tempC!!, 0.0)
        assertEquals(56.0, r.humidityPct!!, 0.0)
        assertEquals(4.2, r.dewPointC!!, 0.0)
        assertEquals(12.8, r.feelsLikeC!!, 0.0)
        assertEquals(988.8, r.pressureHpa!!, 0.0)
        assertEquals(2.3, r.windMs!!, 0.0)
        assertEquals(253.0, r.windDirDeg!!, 0.0)
        assertEquals(15.1, r.rainTodayMm!!, 0.0)
        assertEquals(0.0, r.rainRateMmH!!, 0.0)
        assertEquals(31.7, r.solarWm2!!, 0.0)
        assertEquals(0.0, r.uvIndex!!, 0.0)
        assertNull(r.gustMs)
    }

    @Test fun missingFieldsAndNonsense() {
        // Without the station's clock the reading is as old as the upload; gaps stay gaps.
        val bare = UploadData.parse("""{"received_at":"2026-10-23T09:57:35Z","reading":{"temp_c":-4.5}}""")
        assertEquals(measured + 4_000L, bare.atMs)
        assertEquals(-4.5, bare.tempC!!, 0.0)
        assertNull(bare.humidityPct)
        // Implausible values are dropped, not shown.
        assertNull(UploadData.parse("""{"received_at":"2026-10-23T09:57:35Z","reading":{"temp_c":-999,"humidity_pct":150,"pressure_hpa":0}}""").tempC)
    }

    // ------------------------------------------------------------------ units

    @Test fun beaufort() {
        val cases = listOf(0.0 to 0, 0.4 to 0, 0.5 to 1, 1.5 to 1, 1.55 to 2, 3.3 to 2, 3.4 to 3, 5.4 to 3, 5.5 to 4, 7.9 to 4, 8.0 to 5,
            10.7 to 5, 10.8 to 6, 13.9 to 7, 17.2 to 8, 20.8 to 9, 24.5 to 10, 28.4 to 10, 28.5 to 11, 32.6 to 11, 32.7 to 12, 45.0 to 12)
        cases.forEach { (ms, b) -> assertEquals("$ms m/s", b, StationUnits.beaufort(ms)) }
    }

    @Test fun windUnits() {
        assertEquals("2,3${nbsp}m/s", StationUnits.wind(2.3, WindUnit.Ms, nl))
        assertEquals("8${nbsp}km/h", StationUnits.wind(2.3, WindUnit.Kmh, nl))
        assertEquals("2${nbsp}Bft", StationUnits.wind(2.3, WindUnit.Bft, nl))
        assertEquals("8${nbsp}km/h", StationUnits.wind(2.3, WindUnit.Auto, nl))
        val us = PluginFormat(Locale.US, zone, h24 = false, temperatureUnit = "f")
        assertEquals("5${nbsp}mph", StationUnits.wind(2.3, WindUnit.Auto, us))
        assertEquals("55.0°", us.temperature(12.8, decimals = 1))
        assertEquals(6, StationUnits.sector(253.0)) // WSW: nearest of the 8 points is W
        assertEquals(5, StationUnits.sector(230.0))
        assertEquals(0, StationUnits.sector(359.0))
        assertEquals(WindUnit.Bft, WindUnit.of(JsonObject(mapOf("wind_unit" to JsonPrimitive("bft")))))
        assertEquals(WindUnit.Auto, WindUnit.of(JsonObject(emptyMap())))
    }

    @Test fun asksOncePerMinute() {
        assertEquals(60, WeatherStation(JsonObject(emptyMap()), sourceFor = { null }).refreshSeconds)
    }

    // ------------------------------------------------------------------ history and trends

    private fun reading(minutesAgo: Int, temp: Double?, hpa: Double?, end: Long = measured) =
        StationReading(atMs = end - minutesAgo * 60_000L, tempC = temp, pressureHpa = hpa)

    @Test fun trendsFromLocalHistory() {
        val h = StationHistory()
        assertNull(h.tempTrend()); assertNull(h.pressureTrend())
        // Three hours of readings every 10 minutes: temperature up 1.2 °C in the last hour, pressure down 2 hPa in 3 h.
        for (m in 180 downTo 0 step 10) {
            val temp = if (m >= 60) 11.0 else 11.0 + (60 - m) * 0.02
            assertTrue(h.add(reading(m, temp, 990.0 - (180 - m) / 90.0)))
        }
        assertEquals(Trend.Up, h.tempTrend())
        assertEquals(Trend.Down, h.pressureTrend())
        // The same reading again, or one 2 minutes later, is not a new sample.
        assertFalse(h.add(reading(0, 13.0, 988.0)))
        assertFalse(h.add(reading(-2, 13.0, 988.0)))

        val steady = StationHistory()
        for (m in 190 downTo 0 step 10) steady.add(reading(m, 8.0 + (m % 20) / 100.0, 1013.0 + (m % 20) / 100.0))
        assertNull(steady.tempTrend()) // steady temperature: no arrow
        assertEquals(Trend.Flat, steady.pressureTrend()) // steady pressure is a tendency of its own

        // Too short a history: no trend rather than a guess.
        val short = StationHistory()
        short.add(reading(20, 10.0, 1000.0)); short.add(reading(0, 12.0, 995.0))
        assertNull(short.tempTrend()); assertNull(short.pressureTrend())
    }

    @Test fun historySurvivesARestartAndKeepsADay() {
        val h = StationHistory()
        for (m in 30 * 60 downTo 0 step 10) h.add(reading(m, 10.0 + m / 600.0, if (m % 60 == 0) null else 1000.0))
        assertTrue("pruned to 26 h", h.size <= 26 * 6 + 1)
        val back = StationHistory.decode(h.encode())
        assertEquals(h.encode(), back.encode())
        assertTrue("fits ctx.store", h.encode().length < PluginStore.MAX_BYTES / 4)
        val series = back.tempSeries(measured / 1000)
        assertEquals(StationHistory.BUCKETS, series.size)
        assertTrue(series.count { it != null } >= 90)
        assertEquals(10.0f, series.last()!!, 0.05f)
        // Garbage in the store is skipped, not fatal.
        assertEquals(1, StationHistory.decode("x;1,2;100,12.5,;99,1,1;nonsense").size)
    }

    // ------------------------------------------------------------------ staleness

    @Test fun staleAfterThirtyMinutesOfSilence() {
        assertFalse(PluginHost.dataIsOld(measured, WeatherStation.STALE_S, measured + 29 * 60_000L))
        assertTrue(PluginHost.dataIsOld(measured, WeatherStation.STALE_S, measured + 31 * 60_000L))
        assertFalse("no data time: only the host's own rule", PluginHost.dataIsOld(null, WeatherStation.STALE_S, measured + 99 * 60_000L))
        assertEquals("updated_now", StationViews.updated(measured, measured + 20_000L, nl, t))
        assertEquals("updated_ago 4${nbsp}min", StationViews.updated(measured, measured + 4 * 60_000L + 5_000L, nl, t))
        val now = measured + 45 * 60_000L
        assertEquals("no_reading_since ${nl.time(measured)}", StationViews.updated(measured, now, nl, t))
    }

    @Test fun slowPluginsAreNotHiddenOrHammered() {
        // A 30-minute plugin is not hidden between two good readings, and an error never makes it ask more often.
        assertTrue(PluginHost.hideAfterMs(30 * 60_000L) > 30 * 60_000L)
        assertEquals(15 * 60_000L, PluginHost.hideAfterMs(5_000L))
        assertEquals(300_000L, PluginHost.backoffMs(300_000L, 0))
        assertEquals(600_000L, PluginHost.backoffMs(300_000L, 2))
        assertEquals(1_200_000L, PluginHost.backoffMs(300_000L, 9))
        // The HomeWizard's 5 s keeps its old 2×, 4×… up to a minute.
        assertEquals(40_000L, PluginHost.backoffMs(5_000L, 4))
        assertEquals(60_000L, PluginHost.backoffMs(5_000L, 9))
    }

    @Test fun stationTemperatureInTheWeatherCorner() {
        val w = WeatherFixtures.gent(tempC = 14.6)
        val now = WeatherFixtures.nowMs
        assertEquals(12.8, w.withStation(StationNow(12.8, now - 10 * 60_000L), now).nowConditions(now).tempC, 0.0)
        // The condition stays the forecast's.
        assertEquals(w.nowConditions(now).code, w.withStation(StationNow(12.8, now), now).nowConditions(now).code)
        // Too old, or none: the forecast's temperature.
        assertEquals(14.6, w.withStation(StationNow(12.8, now - 40 * 60_000L), now).nowConditions(now).tempC, 0.0)
        assertEquals(14.6, w.withStation(null, now).nowConditions(now).tempC, 0.0)
    }

    // ------------------------------------------------------------------ views

    private fun history(): StationHistory = StationHistory().apply {
        for (m in 24 * 60 downTo 0 step 10) add(reading(m, 9.0 + 4.0 * kotlin.math.sin(m / 229.0), 992.0 - m / 360.0))
    }

    @Test fun compactView() {
        val r = UploadData.parse(latest)
        val v = parse(StationViews.compact(r, Trend.Up, WindUnit.Ms, nl, t)) as ValueView
        assertEquals("12,8°", v.value)
        // Today's low and high, the wind (253° is WSW, nearest of 8 points: W), then the humidity on a dry day.
        assertEquals("↓8° ↑13° · W 2,3${nbsp}m/s · 56%", v.label)
        assertEquals("thermometer", v.icon)
        assertEquals(Trend.Up, v.trend)
        // While it rains the rain takes the humidity's place.
        val wet = parse(StationViews.compact(r.copy(rainRateMmH = 2.0), null, WindUnit.Ms, nl, t)) as ValueView
        assertEquals("↓8° ↑13° · W 2,3${nbsp}m/s · 2,0${nbsp}mm/h", wet.label)
        // Calm air has no direction; a station with only a thermometer has no line.
        val calm = parse(StationViews.compact(r.copy(windMs = 0.2), null, WindUnit.Ms, nl, t)) as ValueView
        assertEquals("↓8° ↑13° · 0,2${nbsp}m/s · 56%", calm.label)
        val bare = parse(StationViews.compact(StationReading(measured, tempC = 3.0), null, WindUnit.Ms, nl, t)) as ValueView
        assertNull(bare.label)
        assertEquals("–", (parse(StationViews.compact(StationReading(measured), null, WindUnit.Ms, nl, t)) as ValueView).value)
    }

    @Test fun rowsViewSaysWhatMattersNow() {
        val r = UploadData.parse(latest).copy(gustMs = 5.1, rainRateMmH = 2.0, feelsLikeC = 10.9)
        val s = parse(StationViews.rows(r, Trend.Down, Trend.Flat, WindUnit.Kmh, nl, t)) as StackView
        val head = s.items[0] as ValueView
        assertEquals("12,8°", head.value)
        assertEquals("↓8° ↑13° · feels_like 11°", head.label)
        val rows = (s.items[1] as RowsView).items
        // Wind, today's rain (it rained), humidity; the pressure is steady and UV 0 doesn't matter, so neither shows.
        assertEquals(listOf("8${nbsp}km/h", "15,1${nbsp}mm", "56%"), rows.map { it.value })
        val wind = rows[0]
        assertEquals("direction", wind.icon)
        assertEquals(73f, wind.iconRotation!!, 0.01f) // from 253° (WSW): the arrow points to 73°
        assertEquals("W · gusts 18${nbsp}km/h", wind.label)
        assertEquals("rain_today · 2,0${nbsp}mm/h", rows[1].label)
        // A falling barometer and a strong sun do show, in that order, still at most four rows.
        val sunny = UploadData.parse(latest).copy(uvIndex = 7.0)
        val more = ((parse(StationViews.rows(sunny, null, Trend.Down, WindUnit.Kmh, nl, t)) as StackView).items[1] as RowsView).items
        assertEquals(listOf("8${nbsp}km/h", "15,1${nbsp}mm", "56%", "989${nbsp}hPa"), more.map { it.value })
        val dry = ((parse(StationViews.rows(sunny.copy(rainTodayMm = 0.0), null, Trend.Down, WindUnit.Kmh, nl, t)) as StackView).items[1] as RowsView).items
        assertEquals(listOf("8${nbsp}km/h", "56%", "989${nbsp}hPa", "7"), dry.map { it.value })
        assertEquals(Tone.Warn, dry[3].tone)
        // Only a thermometer: the temperature alone.
        assertTrue(parse(StationViews.rows(StationReading(measured, tempC = 3.0), null, null, WindUnit.Auto, nl, t)) is ValueView)
    }

    @Test fun detailView() {
        val r = UploadData.parse(latest).copy(feelsLikeC = 10.9)
        val d = parse(StationViews.detail(r, StationInfo(city = "Affligem", altitudeM = 40.0), history(), WindUnit.Ms, nl, t, measured + 4 * 60_000L)) as StackView
        assertEquals(4, d.items.size)
        val head = d.items[0] as StackView
        assertEquals("↓8° ↑13° · Affligem · 40${nbsp}m", (head.items[0] as ValueView).label)
        assertEquals(listOf("updated_ago 4${nbsp}min", "feels_like 11°"), (head.items[1] as TextView).lines)
        val charts = (d.items[1] as StackView).items.map { it as SparklineView }
        assertEquals(listOf("temperature · 24 h", "pressure · 24 h"), charts.map { it.label })
        assertEquals(listOf(Tone.Heat, Tone.Info), charts.map { it.tone })
        assertTrue(charts.all { it.showRange && it.points.size == StationHistory.BUCKETS })
        // Two tables, side by side in a wide card: the air, then rain, pressure and sun.
        assertEquals(listOf("56%", "4,2°", "2,3${nbsp}m/s"), (d.items[2] as RowsView).items.map { it.value })
        assertEquals(listOf("15,1${nbsp}mm", "989${nbsp}hPa", "32${nbsp}W/m²"), (d.items[3] as RowsView).items.map { it.value })
        // Without history or info the card still validates.
        parse(StationViews.detail(StationReading(measured, tempC = 1.0), null, StationHistory(), WindUnit.Auto, nl, t, measured))
    }

    @Test fun longTranslationsAreCutNotRejected() {
        val long: (String) -> String = { "Eine sehr lange Übersetzung für $it, viel zu lang" }
        val r = UploadData.parse(latest).copy(gustMs = 9.0)
        parse(StationViews.compact(r, null, WindUnit.Bft, nl, long))
        parse(StationViews.rows(r, Trend.Up, Trend.Up, WindUnit.Bft, nl, long))
        parse(StationViews.detail(r, StationInfo(city = "Affligem", altitudeM = 40.0), history(), WindUnit.Bft, nl, long, measured + 50 * 60_000L))
        assertEquals(32, StationViews.fit("x".repeat(40)).length)
    }

    // ------------------------------------------------------------------ the whole widget against a fake kinframe server

    @Test fun rendersFromTheFramesServer() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body(latest).build())
            server.enqueue(MockResponse.Builder().code(404).body("{}").build())
            val store = HashMap<String, String>()
            val now = measured + 3 * 60_000L
            val settings = JsonObject(
                mapOf("source" to JsonPrimitive("upload"), "station_id" to JsonPrimitive("IAFFLI3"), "station_key" to JsonPrimitive("k3y"), "wind_unit" to JsonPrimitive("ms")),
            )
            val latestUrl = UploadSource.latestUrl(server.url("/v1"))
            val ctx = PluginContext(
                instanceId = "ws", settings = settings, state = HashMap(),
                store = object : PluginStore {
                    override fun get(key: String) = store[key]
                    override fun put(key: String, value: String?) { if (value == null) store.remove(key) else store[key] = value }
                },
                http = PluginHttp(OkHttpClient(), allowed = { UploadSource.allows(latestUrl, it) }, localOnly = false, userAgent = "kinframe-frame/test"),
                format = { nl }, t = t, log = {}, now = { now }, zone = { zone }, invalidate = {},
            )
            var seen: StationReading? = null
            val ws = WeatherStation(settings, onReading = { seen = it }, sourceFor = { s -> WeatherStation.sourceFor(s, latestUrl) })
            assertEquals(WeatherStation.STALE_S, ws.staleAfterSeconds)

            val v = parse(ws.render(ctx)!!) as ValueView
            assertEquals("12,8°", v.value)
            assertEquals(measured, ctx.dataTime)
            assertEquals(12.8, seen!!.tempC!!, 0.0)
            val req = server.takeRequest()
            assertEquals("/v1/stations/latest", req.url.encodedPath)
            assertEquals("IAFFLI3", req.headers["X-Station-Id"])
            assertEquals("k3y", req.headers["X-Station-Key"])
            assertNull("the key never goes in the address", req.url.query)
            assertEquals("kinframe-frame/test", req.headers["User-Agent"])
            // The detail card needs nothing more from the server.
            parse(ws.detail(ctx)!!)
            try {
                ws.render(ctx); fail("a 404 (not registered, or silent for 48 h) must fail the render")
            } catch (e: PluginHttpException) {
                assertEquals(404, e.status)
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test fun onlyTheFramesOwnServerIsReachable() {
        val latestUrl = UploadSource.latestUrl("https://kinframe.appmire.be/v1".toHttpUrl())
        assertEquals("https://kinframe.appmire.be/v1/stations/latest", latestUrl.toString())
        assertTrue(UploadSource.allows(latestUrl, latestUrl))
        assertFalse(UploadSource.allows(latestUrl, "https://kinframe.appmire.be/v1/frames".toHttpUrl()))
        assertFalse(UploadSource.allows(latestUrl, "http://kinframe.appmire.be/v1/stations/latest".toHttpUrl()))
        assertFalse(UploadSource.allows(latestUrl, "https://app.weathercloud.net/device/values".toHttpUrl()))
        assertNull("no key: nothing to ask", WeatherStation.sourceFor(JsonObject(mapOf("station_id" to JsonPrimitive("IAFFLI3"))), latestUrl))
        assertEquals(UploadSource.ID, WeatherStation.sourceFor(JsonObject(mapOf("station_id" to JsonPrimitive("IAFFLI3"), "station_key" to JsonPrimitive("k"))), latestUrl)!!.id)
    }

}
