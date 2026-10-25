package app.kinframe.frame.plugins

import app.kinframe.core.plugin.FlowView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.ValueView
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.homewizard.HomeWizardData
import app.kinframe.frame.plugins.homewizard.HomeWizardP1
import app.kinframe.frame.plugins.homewizard.HomeWizardViews
import app.kinframe.frame.plugins.homewizard.P1Today
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The built-in HomeWizard P1 widget: the v1 response mapping, its views, and a full render against a fake meter. */
class HomeWizardTest {
    /** A real-world `GET /api/v1/data` from a three-phase P1 meter with a gas meter (HWE-P1, firmware 5.x). */
    private val threePhase = """
        {"wifi_ssid":"Home","wifi_strength":84,"smr_version":50,"meter_model":"Fluvius 253769484_A","unique_id":"3153414731313030303037363333",
         "active_tariff":2,"total_power_import_kwh":13779.338,"total_power_import_t1_kwh":10830.511,"total_power_import_t2_kwh":2948.827,
         "total_power_export_kwh":5114.106,"total_power_export_t1_kwh":1278.065,"total_power_export_t2_kwh":3836.041,
         "active_power_w":1203,"active_power_l1_w":412,"active_power_l2_w":531,"active_power_l3_w":260,
         "active_voltage_l1_v":231.4,"active_current_a":5.2,"total_gas_m3":3204.581,"gas_timestamp":251025141500,"gas_unique_id":"4730303738353635363037343"}
    """.trimIndent()

    private val f = PluginFormat(Locale.forLanguageTag("nl"), ZoneId.of("Europe/Brussels"))
    private val t: (String) -> String = { it }
    private val nbsp = PluginFormat.NBSP

    private fun parse(j: JsonElement) = when (val r = PluginViewParser.parse(j)) {
        is ViewResult.Valid -> r.view
        is ViewResult.Invalid -> { fail("invalid view: $r"); throw AssertionError() }
    }

    @Test fun mapsTheV1Response() {
        val r = HomeWizardData.parse(threePhase)
        assertEquals(1203.0, r.activePowerW!!, 0.0)
        assertEquals(listOf(412.0, 531.0, 260.0), r.phasesW)
        assertEquals(13779.338, r.importKwh!!, 1e-9)
        assertEquals(5114.106, r.exportKwh!!, 1e-9)
        assertEquals(3204.581, r.gasM3!!, 1e-9)
        assertEquals("Fluvius 253769484_A", r.meterModel)
    }

    @Test fun olderFirmwareOnlyHasTariffTotalsAndNoGas() {
        val r = HomeWizardData.parse("""{"total_power_import_t1_kwh":100.5,"total_power_import_t2_kwh":50.25,"total_power_export_t1_kwh":0,"active_power_w":-850,"active_power_l1_w":-850}""")
        assertEquals(150.75, r.importKwh!!, 1e-9)
        assertEquals(0.0, r.exportKwh!!, 1e-9)
        assertNull(r.gasM3)
        assertEquals(listOf(-850.0), r.phasesW)
        // Strings where numbers belong are ignored, not misread.
        assertNull(HomeWizardData.parse("""{"active_power_w":"12"}""").activePowerW)
    }

    @Test fun valueViewToneAndLabel() {
        val using = parse(HomeWizardViews.main(HomeWizardData.parse(threePhase), null, JsonObject(emptyMap()), f, t)) as ValueView
        assertEquals("1,2${nbsp}kW", using.value)
        assertEquals("using", using.label)
        assertEquals("bolt", using.icon)
        assertEquals(Tone.Neutral, using.tone)

        val warn = parse(HomeWizardViews.main(HomeWizardData.parse(threePhase), null, JsonObject(mapOf("warn_w" to JsonPrimitive(1000))), f, t)) as ValueView
        assertEquals(Tone.Warn, warn.tone)

        val export = parse(HomeWizardViews.main(HomeWizardData.parse("""{"active_power_w":-2400}"""), null, JsonObject(emptyMap()), f, t)) as ValueView
        assertEquals("2,4${nbsp}kW", export.value)
        assertEquals("returning", export.label)
        assertEquals(Tone.Good, export.tone)
        assertEquals("sun", export.icon)
    }

    @Test fun flowStyleArrowsFollowTheDirection() {
        val flowSettings = JsonObject(mapOf("style" to JsonPrimitive("flow")))
        val imp = parse(HomeWizardViews.main(HomeWizardData.parse(threePhase), null, flowSettings, f, t)) as FlowView
        assertEquals(listOf("grid", "home"), imp.nodes.map { it.id })
        assertEquals(0, imp.edges.single().from)
        assertEquals(1203f, imp.edges.single().power)
        val exp = parse(HomeWizardViews.main(HomeWizardData.parse("""{"active_power_w":-900}"""), null, flowSettings, f, t)) as FlowView
        assertEquals(1, exp.edges.single().from)
        assertEquals(900f, exp.edges.single().power)
    }

    @Test fun detailHasPhasesTodayAndTheLastHour() {
        val r = HomeWizardData.parse(threePhase)
        val d = parse(HomeWizardViews.detail(r, listOf(800f, null, 1200f), P1Today(6.82, 3.15, 1.24), f, t)) as StackView
        assertEquals(4, d.items.size)
        assertTrue(d.items[1] is SparklineView)
        assertEquals(listOf("L1", "L2", "L3"), (d.items[2] as RowsView).items.map { it.label })
        assertEquals(listOf("6,82${nbsp}kWh", "3,15${nbsp}kWh", "1,24${nbsp}m³"), (d.items[3] as RowsView).items.map { it.value })
    }

    @Test fun rendersFromAFakeMeterAndTracksTheDay() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body(threePhase).build())
            server.enqueue(MockResponse.Builder().body(threePhase.replace("13779.338", "13780.838").replace("\"active_power_w\":1203", "\"active_power_w\":-300")).build())
            server.enqueue(MockResponse.Builder().code(503).build())
            val store = HashMap<String, String>()
            var now = java.time.Instant.parse("2026-10-25T10:00:00Z").toEpochMilli()
            val ctx = PluginContext(
                instanceId = "p1", settings = JsonObject(mapOf("host" to JsonPrimitive("${server.hostName}:${server.port}"))),
                state = HashMap(),
                store = object : PluginStore {
                    override fun get(key: String) = store[key]
                    override fun put(key: String, value: String?) { if (value == null) store.remove(key) else store[key] = value }
                },
                // MockWebServer listens on loopback, which local-only refuses; the DNS rule has its own tests.
                http = PluginHttp(OkHttpClient(), allowed = { true }, localOnly = false),
                format = { f }, t = t, log = {}, now = { now }, zone = { ZoneId.of("Europe/Brussels") }, invalidate = {},
            )
            val p1 = HomeWizardP1()
            assertEquals("1,2${nbsp}kW", (parse(p1.render(ctx)!!) as ValueView).value)
            assertEquals("/api/v1/data", server.takeRequest().url.encodedPath)
            assertTrue(store["day"]!!.startsWith("2026-10-25|13779.338|"))
            now += 5_000
            assertEquals(Tone.Good, (parse(p1.render(ctx)!!) as ValueView).tone)
            val detail = parse(p1.detail(ctx)!!) as StackView
            val today = detail.items.last() as RowsView
            assertEquals("1,50${nbsp}kWh", today.items.first().value)
            try {
                p1.render(ctx); fail("a 503 must fail the render (the host backs off)")
            } catch (e: PluginHttpException) {
                assertEquals(503, e.status)
            }
        } finally {
            server.close()
        }
    }

    @Test fun localOnlyRefusesPublicNames() {
        try {
            LocalAddress.dns.lookup("localhost"); fail("loopback is not the home network")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(e.message!!.contains("home network"))
        }
    }
}
