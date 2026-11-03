package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.NoneView
import app.kinframe.core.plugin.SparklineStyle
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.TextView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.ValueView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** `kinframe.rain-radar` on recorded Buienradar and Open-Meteo answers (docs/plugins.md §9.9). */
class RainRadarPluginTest {
    private val kits = ArrayList<JsPluginKit>()
    @After fun down() = kits.forEach { it.close() }

    private val dir = File("../../plugins/kinframe.rain-radar")

    /** The plugin's module with a default export that feeds [state] (JS, may use FIXTURE) to [call] instead of fetching. */
    private fun kit(fixture: String, state: String, call: String = "view", settings: Map<String, Any> = emptyMap(), language: String = "nl"): JsPluginKit {
        val real = PluginBundle.fromDirectory(dir)
        val data = dir.resolve("fixtures/$fixture").readText()
        val literal = if (fixture.endsWith(".json")) data else Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(data))
        val js = real.mainJs.replace("export default {", "const live = {") +
            "\nexport default { render(ctx) { return $call($state, ctx); }, detail(ctx) { return detail($state, ctx); } };\nconst FIXTURE = $literal;\n"
        val s = JsonObject(settings.mapValues { (_, v) -> if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        return JsPluginKit(PluginBundle(real.manifest, js, real.locales), s, language = language).also {
            it.now = Instant.parse("2026-10-27T09:44:18Z").toEpochMilli()
            kits += it
        }
    }

    private val buienradar = "({ source: 'buienradar', points: parseBuienradar(FIXTURE, ctx.now(), ctx.localTime(ctx.now(), 'Europe/Amsterdam')) })"

    @Test fun aShowerComing() = runBlocking<Unit> {
        val v = kit("buienradar-rain-coming.txt", buienradar).view() as SparklineView
        assertEquals(SparklineStyle.Bars, v.style)
        assertEquals(Tone.Water, v.tone)
        assertEquals(24, v.points.size)
        // 11:50 Dutch time is 11:50 in Brussels: the same zone.
        assertEquals("Regen vanaf", v.label)
        assertEquals("11:50", v.value)
        assertEquals(2f, v.max!!, 0f) // a light shower doesn't fill the chart
    }

    @Test fun rainingNowAndWhenItStops() = runBlocking<Unit> {
        val k = kit("buienradar-raining-now.txt", buienradar, language = "en")
        val v = k.view() as SparklineView
        assertEquals("Dry from", v.label)
        assertEquals("11:20", v.value) // 11:20 is 0.06 mm/h: under the 0.1 that counts as rain
        assertTrue(v.max!! > 10) // value 160 ≈ 39 mm/h: the scale follows a downpour
        val d = k.detail() as StackView
        assertEquals(listOf("Dry from 11:20", "At most 39 mm/h".replace(" mm", "\u00A0mm") + " · 10:45 – 12:40"), (d.items[1] as TextView).lines)
        assertEquals("Source: Buienradar", (d.items[2] as TextView).lines.single())
    }

    @Test fun dryHidesTheWidgetUnlessTheOwnerWantsToSeeIt() = runBlocking<Unit> {
        assertTrue(kit("buienradar-dry.txt", buienradar).view() is NoneView)
        val shown = kit("buienradar-dry.txt", buienradar, settings = mapOf("show_dry" to true)).view() as ValueView
        assertEquals("Droog", shown.value)
        assertEquals("Komende 2 uur", shown.label)
    }

    @Test fun openMeteoQuartersFromNow() = runBlocking<Unit> {
        val state = "({ source: 'open_meteo', points: parseOpenMeteo(FIXTURE, ctx.now()) })"
        assertTrue(kit("open-meteo-dry.json", state).view() is NoneView)
        val k = kit("open-meteo-dry.json", "parseOpenMeteo(FIXTURE, ctx.now())", call = "((p) => ({ type: 'text', lines: [String(p.length), new Date(p[0].t).toISOString()] }))")
        val lines = (k.view() as TextView).lines
        assertEquals("8", lines[0])
        assertEquals("2026-10-27T09:30:00.000Z", lines[1]) // the quarter under way
        // A wet quarter: 0.3 mm in 15 minutes is 1.2 mm/h.
        val wet = kit("open-meteo-dry.json", "({ source: 'open_meteo', points: parseOpenMeteo(Object.assign({}, FIXTURE, { minutely_15: Object.assign({}, FIXTURE.minutely_15, { precipitation: FIXTURE.minutely_15.precipitation.map((x, i) => i == 4 ? 0.3 : x) }) }), ctx.now()) })", language = "en")
        val v = wet.view() as SparklineView
        assertEquals("Rain from", v.label)
        assertEquals("11:15", v.value) // the response starts a quarter back (past_minutely_15=1): 09:15 UTC + 4 × 15 min, in Brussels
        // Rain for the whole two hours: then the value says how hard.
        val all = kit("open-meteo-dry.json", "({ source: 'open_meteo', points: parseOpenMeteo(Object.assign({}, FIXTURE, { minutely_15: Object.assign({}, FIXTURE.minutely_15, { precipitation: FIXTURE.minutely_15.precipitation.map(() => 0.3) }) }), ctx.now()) })", language = "en")
        val w = all.view() as SparklineView
        assertEquals("Rain for the next 2 h", w.label)
        assertEquals("1.2\u00A0mm/h", w.value)
    }

    @Test fun theSourceFollowsTheFramesPlace() = runBlocking<Unit> {
        suspend fun src(lat: Double, lon: Double, setting: String = "auto") =
            (kit("buienradar-dry.txt", "'$setting'", call = "((s) => ({ type: 'text', lines: [sourceFor(s, { latitude: $lat, longitude: $lon })] }))").view() as TextView).lines.single()
        assertEquals("buienradar", src(50.93, 4.13)) // Affligem
        assertEquals("buienradar", src(52.37, 4.9)) // Amsterdam
        assertEquals("open_meteo", src(48.86, 2.35)) // Paris
        assertEquals("open_meteo", src(50.93, 4.13, "open_meteo"))
    }

    @Test fun manifestAndStrings() {
        val b = PluginBundle.fromDirectory(dir)
        assertTrue(b.manifest.permissions.location)
        assertEquals(listOf("https://gpsgadget.buienradar.nl", "https://api.open-meteo.com"), b.manifest.permissions.http)
        listOf("en", "nl", "de", "fr").forEach { tag ->
            val s = b.strings(tag)
            listOf("rain_from", "dry_from", "rain_2h", "dry_2h", "dry", "next_2h", "source", "no_location").forEach { k -> assertTrue("$tag $k", k in s) }
        }
    }
}
