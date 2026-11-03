package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.TextView
import app.kinframe.frame.plugins.Slot
import app.kinframe.frame.plugins.Visibility
import app.kinframe.frame.plugins.newInstanceConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** `ctx.location`, `ctx.format.day/ago/until` from JavaScript, and the manifest's defaults and presets (§3.1, §3.2). */
class PluginCtxTest {
    private val kits = ArrayList<JsPluginKit>()
    @After fun down() = kits.forEach { it.close() }

    private fun manifest(permissions: String = "{}", extra: String = "") =
        """{"id":"community.test","name":"Test","version":"1.0.0","api":1,"permissions":$permissions$extra,
            "settings":[{"key":"prefix","type":"text"},{"key":"level","type":"entity"},{"key":"range","type":"entity"},
            {"key":"panels","type":"entity","multiple":true},{"key":"style","type":"choice","options":["ring","bar"]},
            {"key":"max","type":"number"},{"key":"on","type":"boolean"},{"key":"token","type":"secret"}]}"""

    private fun kit(js: String, manifest: String = manifest(), language: String = "nl") =
        JsPluginKit(JsPluginKit.inline(js, manifest), JsonObject(emptyMap()), language = language).also { kits += it }

    private fun line(k: JsPluginKit) = runBlocking { (JsPluginKit.parse(k.render()!!) as TextView).lines.first() }

    private val showLocation = """export default { render(ctx) { const l = ctx.location; return { type: 'text', lines: [l ? l.latitude + ',' + l.longitude : 'none'] }; } }"""

    @Test fun locationOnlyWithThePermissionAndCoarse() {
        val with = kit(showLocation, manifest("""{"location":true}""")).apply { location = 50.912345 to 4.104987 }
        assertEquals("50.91,4.1", line(with))
        val without = kit(showLocation).apply { location = 50.912345 to 4.104987 }
        assertEquals("none", line(without))
        val nowhere = kit(showLocation, manifest("""{"location":true}"""))
        assertEquals("none", line(nowhere))
    }

    @Test fun locationCantBeChanged() {
        val k = kit("""export default { render(ctx) { 'use strict'; let msg = 'kept'; try { ctx.location.latitude = 0; } catch (e) { msg = 'frozen'; } return { type: 'text', lines: [msg + ' ' + ctx.location.latitude] }; } }""",
            manifest("""{"location":true}""")).apply { location = 51.0 to 4.0 }
        assertEquals("frozen 51", line(k))
    }

    @Test fun relativeDatesFromJavaScript() {
        val js = """export default { render(ctx) { const n = ctx.now();
            return { type: 'text', lines: [ctx.format.day(n + 86400000) + '|' + ctx.format.ago(n - 480000) + '|' + ctx.format.until(n + 300000)] }; } }"""
        val nl = kit(js).apply { now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli() }
        assertEquals("Morgen|8 min geleden|over 5 min", line(nl))
        val fr = kit(js, language = "fr").apply { now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli() }
        assertEquals("Demain|il y a 8 min|dans 5 min", line(fr))
    }

    @Test fun defaultVisibility() {
        val m = PluginManifest.parse(manifest(extra = ""","default_visibility":"on_change","slots":["top-left"]"""))
        assertEquals(Visibility.OnChange, m.defaultVisibility)
        val cfg = newInstanceConfig("car-1", m)
        assertEquals(Visibility.OnChange, cfg.visibility)
        assertEquals(Slot.TopLeft, cfg.slot)
        assertEquals("1.0.0", cfg.version)
        val plain = newInstanceConfig("x", PluginManifest.parse(manifest()))
        assertEquals(Visibility.Always, plain.visibility)
        assertEquals(Slot.TopRight, plain.slot)
        bad(""","default_visibility":"sometimes"""")
    }

    @Test fun presetsFillSettingsFromAPrefix() {
        val m = PluginManifest.parse(manifest(extra = ""","presets":[
            {"id":"kia","label":"Kia / Hyundai (Kia Connect)","settings":{"level":"sensor.{prefix}_ev_battery_level","range":"sensor.{prefix}_ev_range","style":"ring"}},
            {"id":"solar","label":"Two inverters","settings":{"panels":["sensor.a_power","sensor.b_power"],"max":5000,"on":true}}]"""))
        assertEquals(listOf("kia", "solar"), m.presets.map { it.id })
        assertEquals(listOf("prefix"), m.preset("kia")!!.inputs)
        assertNull("the prefix is needed first", m.applyPreset("kia", JsonObject(mapOf("prefix" to JsonPrimitive(" ")))))
        val v = m.applyPreset("kia", JsonObject(mapOf("prefix" to JsonPrimitive("ev3"), "max" to JsonPrimitive(80))))!!
        assertEquals(JsonPrimitive("sensor.ev3_ev_battery_level"), v["level"])
        assertEquals(JsonPrimitive("sensor.ev3_ev_range"), v["range"])
        assertEquals(JsonPrimitive("ring"), v["style"])
        assertEquals("the owner's other settings stay", JsonPrimitive(80), v["max"])
        val s = m.applyPreset("solar", JsonObject(emptyMap()))!!
        assertEquals(JsonArray(listOf(JsonPrimitive("sensor.a_power"), JsonPrimitive("sensor.b_power"))), s["panels"])
        assertNull(m.applyPreset("nope", JsonObject(emptyMap())))
    }

    @Test fun presetValidation() {
        val ok = """{"id":"p","label":"P","settings":{"level":"sensor.x"}}"""
        bad(""","presets":{}""")
        bad(""","presets":[{"id":"P-1","label":"P","settings":{"level":"x"}}]""")
        bad(""","presets":[{"id":"p","label":"","settings":{"level":"x"}}]""")
        bad(""","presets":[{"id":"p","label":"P","settings":{}}]""")
        bad(""","presets":[{"id":"p","label":"P","settings":{"nope":"x"}}]""")
        bad(""","presets":[{"id":"p","label":"P","settings":{"token":"abc"}}]""") // never a secret
        bad(""","presets":[{"id":"p","label":"P","settings":{"style":"donut"}}]""") // not an option
        bad(""","presets":[{"id":"p","label":"P","settings":{"max":"5000"}}]""") // a number, not a string
        bad(""","presets":[{"id":"p","label":"P","settings":{"on":1}}]""")
        bad(""","presets":[{"id":"p","label":"P","settings":{"level":["a","b"]}}]""") // level isn't multiple
        bad(""","presets":[{"id":"p","label":"P","settings":{"level":"sensor.{unknown}"}}]""")
        bad(""","presets":[{"id":"p","label":"P","settings":{"level":"sensor.{max}"}}]""") // placeholders are text settings
        bad(""","presets":[{"id":"p","label":"P","settings":{"prefix":"a","level":"sensor.{prefix}"}}]""")
        bad(""","presets":[$ok,$ok]""")
        bad(""","presets":[${List(13) { i -> ok.replace("\"p\"", "\"p$i\"") }.joinToString(",")}]""")
        bad("", permissions = """{"location":"yes"}""")
        PluginManifest.parse(manifest(extra = ""","presets":[$ok]"""))
    }

    private fun bad(extra: String, permissions: String = "{}") {
        val m = manifest(permissions, extra)
        try { PluginManifest.parse(m); fail("accepted: $m") } catch (_: ManifestException) {}
    }
}
