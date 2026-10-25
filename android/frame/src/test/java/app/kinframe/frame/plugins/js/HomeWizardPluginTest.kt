package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.FlowView
import app.kinframe.core.plugin.GaugeStyle
import app.kinframe.core.plugin.GaugeView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.Trend
import app.kinframe.core.plugin.ValueView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `kinframe.homewizard-p1` (plugins/kinframe.homewizard-p1), the JavaScript port of the built-in P1 widget: the same
 * expectations as the Kotlin widget had, now against the plugin running in QuickJS and a fake meter.
 */
class HomeWizardPluginTest {
    /** A real-world `GET /api/v1/data` from a three-phase P1 meter with a gas meter (HWE-P1, firmware 5.x). */
    private val threePhase = """
        {"wifi_ssid":"Home","wifi_strength":84,"smr_version":50,"meter_model":"Fluvius 253769484_A","unique_id":"3153414731313030303037363333",
         "active_tariff":2,"total_power_import_kwh":13779.338,"total_power_import_t1_kwh":10830.511,"total_power_import_t2_kwh":2948.827,
         "total_power_export_kwh":5114.106,"total_power_export_t1_kwh":1278.065,"total_power_export_t2_kwh":3836.041,
         "active_power_w":1203,"active_power_l1_w":412,"active_power_l2_w":531,"active_power_l3_w":260,
         "active_voltage_l1_v":231.4,"active_current_a":5.2,"total_gas_m3":3204.581,"gas_timestamp":251025141500,"gas_unique_id":"4730303738353635363037343"}
    """.trimIndent()
    private val nbsp = PluginFormat.NBSP

    private lateinit var server: MockWebServer
    private val kits = ArrayList<JsPluginKit>()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { kits.forEach { it.close() }; server.close() }

    private fun kit(vararg settings: Pair<String, Any>): JsPluginKit {
        val map = mapOf("host" to JsonPrimitive("${server.hostName}:${server.port}")) +
            settings.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }
        return JsPluginKit(JsPluginKit.plugin("kinframe.homewizard-p1"), JsonObject(map)).also { kits += it }
    }

    private fun meter(body: String) = server.enqueue(MockResponse.Builder().body(body).build())
    private fun power(w: Int) = threePhase.replace("\"active_power_w\":1203", "\"active_power_w\":$w")

    @Test fun valueViewToneAndLabel() = runBlocking {
        meter(threePhase)
        val using = kit().view() as ValueView
        assertEquals("1,2${nbsp}kW", using.value)
        assertEquals("Verbruik", using.label)
        assertEquals("bolt", using.icon)
        assertEquals(Tone.Neutral, using.tone)
        assertEquals("/api/v1/data", server.takeRequest().url.encodedPath)

        meter(threePhase)
        assertEquals(Tone.Warn, (kit("warn_w" to 1000).view() as ValueView).tone)

        meter("""{"active_power_w":-2400}""")
        val export = kit().view() as ValueView
        assertEquals("2,4${nbsp}kW", export.value)
        assertEquals("Teruglevering", export.label)
        assertEquals(Tone.Good, export.tone)
        assertEquals("sun", export.icon)
    }

    @Test fun gaugeStyleIsATwoSidedDialInWatts() = runBlocking {
        meter(threePhase)
        val using = kit("style" to "gauge").view() as GaugeView
        assertEquals(GaugeStyle.Dial, using.style)
        assertEquals("1.203${nbsp}W", using.label)
        assertEquals(1203f / 40f, using.percent, 0.01f)
        assertEquals(Tone.Info, using.tone)
        assertEquals(Tone.Good, using.negativeTone)
        // The first reading of the day: nothing used or returned yet.
        assertEquals(listOf("0,0", "0,0${nbsp}kWh"), using.sub.map { it.value })
        assertEquals(listOf("arrow-up", "arrow-down"), using.sub.map { it.icon })
        assertEquals(listOf(Tone.Good, Tone.Info), using.sub.map { it.tone })

        meter("""{"active_power_w":-900}""")
        val returning = kit("style" to "gauge", "max_w" to 2000).view() as GaugeView
        assertEquals("900${nbsp}W", returning.label)
        assertEquals(-45f, returning.percent, 0.01f)

        meter("""{"active_power_w":5200}""")
        val over = kit("style" to "gauge").view() as GaugeView
        assertEquals(100f, over.percent, 0.01f)
        assertEquals("5.200${nbsp}W", over.label)
    }

    @Test fun flowStyleArrowsFollowTheDirection() = runBlocking {
        meter(threePhase)
        val imp = kit("style" to "flow").view() as FlowView
        assertEquals(listOf("grid", "home"), imp.nodes.map { it.id })
        assertEquals(0, imp.edges.single().from)
        assertEquals(1203f, imp.edges.single().power)
        meter("""{"active_power_w":-900}""")
        val exp = kit("style" to "flow").view() as FlowView
        assertEquals(1, exp.edges.single().from)
        assertEquals(900f, exp.edges.single().power)
    }

    @Test fun tracksTheDayTrendAndDetail() = runBlocking {
        val k = kit()
        meter(threePhase)
        assertEquals("1,2${nbsp}kW", (k.view() as ValueView).value)
        assertTrue(k.store["day"]!!.startsWith("2026-10-25|13779.338|"))
        // Four more readings: steady, then a jump that shows a trend arrow.
        repeat(3) { k.now += 5_000; meter(threePhase); k.view() }
        k.now += 5_000
        meter(power(2600).replace("13779.338", "13780.838"))
        assertEquals(Trend.Up, (k.view() as ValueView).trend)

        val detail = k.detail() as StackView
        assertEquals(4, detail.items.size)
        assertTrue(detail.items[1] is SparklineView)
        assertEquals(listOf("L1", "L2", "L3"), (detail.items[2] as RowsView).items.map { it.label })
        val today = detail.items[3] as RowsView
        assertEquals("1,50${nbsp}kWh", today.items.first().value)
        assertEquals("Vandaag verbruikt", today.items.first().label)

        // The day's baseline survives a restart (it lives in ctx.store).
        k.close()
        val again = JsPluginKit(k.bundle, k.settings).also { kits += it }
        again.store.putAll(k.store)
        again.now = k.now + 5_000
        meter(threePhase.replace("13779.338", "13781.338"))
        again.view()
        assertEquals("2,00${nbsp}kWh", ((again.detail() as StackView).items.last() as RowsView).items.first().value)
    }

    @Test fun olderFirmwareOnlyHasTariffTotalsAndNoGas() = runBlocking {
        val k = kit()
        meter("""{"total_power_import_t1_kwh":100.5,"total_power_import_t2_kwh":50.25,"total_power_export_t1_kwh":0,"active_power_w":-850,"active_power_l1_w":-850}""")
        assertEquals("850${nbsp}W", (k.view() as ValueView).value)
        assertEquals("2026-10-25|150.75|0|", k.store["day"])
        // One phase: no per-phase rows; no gas: no gas row.
        val d = k.detail() as StackView
        assertEquals(listOf("Vandaag verbruikt"), (d.items.last() as RowsView).items.map { it.label })
        // Strings where numbers belong are ignored, not misread.
        meter("""{"active_power_w":"12"}""")
        assertEquals("0${nbsp}W", (kit().view() as ValueView).value)
    }

    @Test fun anHttpErrorFailsTheRender() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        try {
            kit().render(); fail("a 503 must fail the render (the host backs off)")
        } catch (e: PluginScriptException) {
            assertTrue(e.message, e.message!!.contains("HTTP 503"))
        }
    }

    @Test fun noHostNoView() = runBlocking {
        val k = JsPluginKit(JsPluginKit.plugin("kinframe.homewizard-p1"), JsonObject(emptyMap())).also { kits += it }
        assertNull(k.render())
    }
}
