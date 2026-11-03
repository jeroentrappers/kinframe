package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.ActionsView
import app.kinframe.core.plugin.GaugeView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.TextView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.ValueView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `kinframe.homewizard-water` and `kinframe.homewizard-socket` (§9.6, §9.7) against a fake device serving the local
 * API v1 from the plugins' fixtures.
 */
class HomeWizardDevicesTest {
    private val nbsp = PluginFormat.NBSP
    private val kits = ArrayList<JsPluginKit>()
    private val fakes = ArrayList<FakeHomeWizard>()

    @After fun down() { kits.forEach { it.close() }; fakes.forEach { it.close() } }

    private fun device(plugin: String, data: String, state: String? = null) =
        FakeHomeWizard(File("../../plugins/$plugin/fixtures"), data, state).also { fakes += it }

    private fun kit(plugin: String, d: FakeHomeWizard, vararg settings: Pair<String, Any>): JsPluginKit {
        val map = mapOf("host" to JsonPrimitive(d.host)) + settings.associate { (k, v) ->
            k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
        }
        return JsPluginKit(JsPluginKit.plugin(plugin), JsonObject(map)).also { kits += it }
    }

    /** The water meter's reading with [m3] on the counter and [lpm] flowing. */
    private fun water(m3: Double, lpm: Double) = """{"wifi_ssid":"Thuis","wifi_strength":78,"total_liter_m3":$m3,"active_liter_lpm":$lpm,"total_liter_offset_m3":0}"""

    // ------------------------------------------------------------------------------------------------ water meter

    @Test fun waterShowsTodaysLitresAndTheFlowWhileItRuns() = runBlocking {
        val d = device("kinframe.homewizard-water", "data-still.json")
        val k = kit("kinframe.homewizard-water", d)
        val still = k.view() as ValueView
        assertEquals("0${nbsp}l", still.value)
        assertEquals("Vandaag", still.label)
        assertEquals("water", still.icon)
        assertEquals("GET /api/v1/data", d.requests.single())
        assertTrue(k.store["day"]!!.startsWith("2026-10-25|512.384"))

        k.now += 2_000
        d.data = water(512.3963, 7.6)
        val running = k.view() as ValueView
        assertEquals("7,6", running.value)
        assertEquals("l/min", running.unit)
        assertEquals("Vandaag 12${nbsp}l", running.label)
        assertEquals(Tone.Water, running.tone)
    }

    @Test fun waterAsARingAgainstATypicalDay() = runBlocking {
        val d = device("kinframe.homewizard-water", "data-running.json")
        val k = kit("kinframe.homewizard-water", d, "style" to "gauge", "typical_l" to 200)
        k.view()
        k.now += 2_000
        d.data = water(512.434, 7.6) // 50 l since the first reading
        val ring = k.view() as GaugeView
        assertEquals(25f, ring.percent, 0.01f)
        assertEquals("50${nbsp}l", ring.label)
        assertEquals("Vandaag", ring.caption)
        assertEquals(Tone.Water, ring.tone)
        assertEquals(listOf("7,6${nbsp}l/min"), ring.sub.map { it.value })

        k.now += 2_000
        d.data = water(512.634, 0.0) // 250 l: past the typical day, and nothing flows
        val over = k.view() as GaugeView
        assertEquals(100f, over.percent, 0.01f)
        assertEquals(Tone.Warn, over.tone)
        assertTrue(over.sub.isEmpty())
    }

    @Test fun waterDetailHasTheLastHourAndTheNumbers() = runBlocking {
        val d = device("kinframe.homewizard-water", "data-running.json")
        val k = kit("kinframe.homewizard-water", d)
        var m3 = 512.384
        repeat(40) { i -> k.now += 5_000; m3 += 0.0006; d.data = water(m3, if (i % 10 < 5) 7.2 else 0.0); k.render() }
        val card = k.detail() as StackView
        assertTrue(card.items[0] is GaugeView)
        val spark = card.items[1] as SparklineView
        assertEquals(0f, spark.min)
        assertTrue(spark.points.count { !it.isNaN() } >= 2)
        val rows = card.items[2] as RowsView
        assertEquals(listOf("Debiet nu", "Vandaag", "Meterstand"), rows.items.map { it.label })
        assertEquals("512${nbsp}m³", rows.items[2].value)
    }

    @Test fun waterBaselineSurvivesARestartAndStartsAgainTomorrow() = runBlocking {
        val d = device("kinframe.homewizard-water", "data-still.json")
        val k = kit("kinframe.homewizard-water", d)
        k.view()
        k.close()
        val again = JsPluginKit(k.bundle, k.settings).also { kits += it }
        again.store.putAll(k.store)
        again.now = k.now + 60_000
        d.data = water(512.484, 0.0)
        assertEquals("100${nbsp}l", (again.view() as ValueView).value)
        again.now += 24 * 3_600_000L
        assertEquals("0${nbsp}l", (again.view() as ValueView).value)
        assertTrue(again.store["day"]!!.startsWith("2026-10-26|512.484"))
    }

    @Test fun waterWithoutAConfiguredMeterDrawsNothing() = runBlocking {
        val k = JsPluginKit(JsPluginKit.plugin("kinframe.homewizard-water"), JsonObject(emptyMap())).also { kits += it }
        assertNull(k.render())
    }

    // ------------------------------------------------------------------------------------------------ energy socket

    @Test fun socketShowsThePowerAndWhatIsPluggedIn() = runBlocking {
        val d = device("kinframe.homewizard-socket", "data.json", "state-on.json")
        val v = kit("kinframe.homewizard-socket", d).view() as ValueView
        assertEquals("1,8${nbsp}kW", v.value)
        assertEquals("Vandaag 0${nbsp}Wh", v.label)
        assertEquals("plug", v.icon)

        val named = kit("kinframe.homewizard-socket", d, "label" to "Wasmachine", "icon" to "washing-machine").view() as ValueView
        assertEquals("Wasmachine", named.label)
        assertEquals("washing-machine", named.icon)
    }

    @Test fun socketReadsItsSwitchEveryTenSecondsNotEverySecond() = runBlocking {
        val d = device("kinframe.homewizard-socket", "data.json", "state-on.json")
        val k = kit("kinframe.homewizard-socket", d)
        repeat(12) { k.render(); k.now += 1_000 }
        assertEquals(12, d.requests.count { it == "GET /api/v1/data" })
        assertEquals(2, d.requests.count { it == "GET /api/v1/state" })
    }

    @Test fun socketSwitchesFromTheDetailCard() = runBlocking {
        val d = device("kinframe.homewizard-socket", "data.json", "state-on.json")
        val k = kit("kinframe.homewizard-socket", d)
        k.render()
        val card = k.detail() as StackView
        val rows = card.items.filterIsInstance<RowsView>().single()
        assertEquals(listOf("Vandaag", "Spanning", "Stroom"), rows.items.map { it.label })
        assertEquals(listOf("0${nbsp}Wh", "232${nbsp}V", "8,0${nbsp}A"), rows.items.map { it.value })
        val button = (card.items.last() as ActionsView).items.single()
        assertEquals("power", button.id)
        assertEquals("Uitschakelen", button.label)

        k.now += 2_000 // past refresh.min_seconds, so the invalidate goes through at once
        val after = JsPluginKit.parse(k.instance.action(k.ctx, "power")!!) as ValueView
        assertEquals("Uit", after.value)
        assertTrue(d.requests.contains("""PUT /api/v1/state {"power_on":false}"""))
        assertEquals(false, (d.state!!["power_on"] as JsonPrimitive).booleanOrNull)
        assertEquals(1, k.invalidations)
        assertEquals("Inschakelen", ((k.detail() as StackView).items.last() as ActionsView).items.single().label)
    }

    @Test fun aLockedSocketShowsWhyInsteadOfAButton() = runBlocking {
        val d = device("kinframe.homewizard-socket", "data.json", "state-locked.json")
        val k = kit("kinframe.homewizard-socket", d)
        k.render()
        val card = k.detail() as StackView
        assertTrue(card.items.none { it is ActionsView })
        assertEquals("lock", (card.items.last() as TextView).icon)
        // Even a tap that slipped through doesn't switch it.
        assertNull(k.instance.action(k.ctx, "power"))
        assertTrue(d.requests.none { it.startsWith("PUT") })
    }

    @Test fun theOwnerCanTurnTheSwitchOff() = runBlocking {
        val d = device("kinframe.homewizard-socket", "data.json", "state-on.json")
        val k = kit("kinframe.homewizard-socket", d, "switch" to false)
        k.render()
        assertTrue((k.detail() as StackView).items.none { it is ActionsView || it is TextView })
        assertNull(k.instance.action(k.ctx, "power"))
        assertTrue(d.requests.none { it.startsWith("PUT") })
    }
}
