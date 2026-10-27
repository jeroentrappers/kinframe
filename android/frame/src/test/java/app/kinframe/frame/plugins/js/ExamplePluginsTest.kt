package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.GaugeView
import app.kinframe.core.plugin.NoneView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.RowsView
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

/**
 * The community-style examples in plugins/examples (not shipped in the APK): their view code, run in QuickJS on
 * responses recorded from the real public APIs. They exist to find what API 1 lacks (docs/plugins.md §11).
 */
class ExamplePluginsTest {
    private val nbsp = PluginFormat.NBSP
    private val kits = ArrayList<JsPluginKit>()
    @After fun down() = kits.forEach { it.close() }

    /** The plugin's own module, with a default export that feeds [call] the fixture instead of fetching it. */
    private fun kit(id: String, call: String, fixture: String, settings: Map<String, Any> = emptyMap(), language: String = "nl"): JsPluginKit {
        val dir = File("../../plugins/examples/$id")
        val real = PluginBundle.fromDirectory(dir)
        val data = dir.resolve("fixtures/$fixture").readText()
        val literal = if (fixture.endsWith(".json")) data else Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(data))
        val js = real.mainJs.replace("export default {", "const live = {") + "\nexport default { async render(ctx) { const v = $call; return v; } };\nconst FIXTURE = $literal;\n"
        val bundle = PluginBundle(real.manifest, js, real.locales)
        val s = JsonObject(settings.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        return JsPluginKit(bundle, s, language = language).also { kits += it }
    }

    @Test fun departuresAsRows() = runBlocking {
        val k = kit("community.irail-departures", "board(FIXTURE, ctx)", "liveboard-denderleeuw.json", mapOf("count" to 3))
        val rows = k.view() as RowsView
        assertEquals(3, rows.items.size)
        assertEquals("22:20", rows.items[0].value)
        assertEquals("Brussels Airport - Za… · spoor 2", rows.items[0].label)
        assertEquals("22:23 +3", rows.items[1].value)
        // The destination lives in the label, which the band doesn't show: API 1 has no list component (§11).
    }

    @Test fun departuresTowards() = runBlocking {
        val k = kit("community.irail-departures", "board(FIXTURE, ctx)", "liveboard-denderleeuw.json", mapOf("towards" to "gent"))
        val rows = k.view() as RowsView
        assertEquals(listOf("Gent-Sint-Pieters · spoor 4"), rows.items.map { it.label })
    }

    @Test fun airQuality() = runBlocking {
        val g = kit("community.air-quality", "view(FIXTURE, ctx)", "affligem.json").view() as GaugeView
        assertEquals(24f, g.percent)
        assertEquals(Tone.Good, g.tone)
        assertEquals("Redelijk · PM2.5 4${nbsp}µg/m³", g.caption)
    }

    @Test fun airQualityWhereTheFrameIs() = runBlocking {
        val show = "({ type: 'text', lines: [JSON.stringify(place(ctx))] })"
        val frame = kit("community.air-quality", show, "affligem.json").apply { location = 50.9087 to 4.1065 }
        assertEquals("""{"latitude":50.91,"longitude":4.11}""", (frame.view() as TextView).lines.first())
        val typed = kit("community.air-quality", show, "affligem.json", mapOf("latitude" to 51.05, "longitude" to 3.72)).apply { location = 50.9 to 4.1 }
        assertEquals("""{"latitude":51.05,"longitude":3.72}""", (typed.view() as TextView).lines.first())
        val nowhere = kit("community.air-quality", show, "affligem.json")
        assertEquals("null", (nowhere.view() as TextView).lines.first())
    }

    @Test fun electricityPricesFitTheSparkline() = runBlocking {
        val k = kit("community.energy-prices", "view(FIXTURE, ctx).detail", "be-2026-10-25.json")
        k.now = Instant.ofEpochSecond(1792879200L + 900 * 10).toEpochMilli()
        val d = k.view() as StackView
        val now = d.items[0] as ValueView
        assertTrue(now.value, now.value.endsWith("€/kWh"))
        // 100 quarter hours on the day the clocks go back, averaged down to the sparkline's 96.
        assertEquals(96, (d.items[1] as SparklineView).points.size)
    }

    @Test fun binDayOnlyTheDayBefore() = runBlocking {
        val k = kit("community.bin-calendar", "view(parseIcs(FIXTURE), ctx)", "afvalkalender.ics")
        k.now = Instant.parse("2026-10-25T18:00:00Z").toEpochMilli()
        val t = k.view() as TextView
        assertEquals(listOf("Morgen", "PMD, Papier en karton"), t.lines) // ctx.format.day, the frame's own words
        k.now = Instant.parse("2026-10-28T18:00:00Z").toEpochMilli()
        assertEquals(NoneView, k.view())
    }

    @Test fun examplesHaveValidManifestsAndStayOutOfTheApk() {
        File("../../plugins/examples").listFiles { f -> f.isDirectory }!!.forEach { dir ->
            val b = PluginBundle.fromDirectory(dir)
            assertTrue(b.manifest.id.startsWith("community."))
        }
    }
}
