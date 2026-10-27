package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.ActionsView
import app.kinframe.core.plugin.FlowView
import app.kinframe.core.plugin.GaugeView
import app.kinframe.core.plugin.ImageView
import app.kinframe.core.plugin.NoneView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.ProgressView
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.TextView
import app.kinframe.core.plugin.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The Home Assistant plugins (§9.1, §9.3, now playing) against a fake Home Assistant fed with fixtures recorded from a
 * real installation (P1 meter, Solis + Omnik inverters, Solis battery, SmartEVSE, a Kia EV3 and a second car).
 */
class HomeAssistantPluginsTest {
    private val nbsp = PluginFormat.NBSP
    private val kits = ArrayList<JsPluginKit>()
    private val fakes = ArrayList<FakeHomeAssistant>()

    @After fun down() { kits.forEach { it.close() }; fakes.forEach { it.close() } }

    private fun fake(plugin: String, fixture: String) = FakeHomeAssistant(File("../../plugins/$plugin/fixtures/$fixture")).also { fakes += it }

    private fun kit(plugin: String, ha: FakeHomeAssistant, vararg settings: Pair<String, Any>, token: String = ha.token): JsPluginKit {
        val map = mapOf("base_url" to JsonPrimitive(ha.baseUrl), "token" to JsonPrimitive(token)) + settings.associate { (k, v) ->
            k to when (v) {
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsPluginKit(JsPluginKit.plugin(plugin), JsonObject(map)).also { kits += it }
    }

    private fun energy(ha: FakeHomeAssistant) = kit(
        "kinframe.ha-energy", ha,
        "solar" to listOf("sensor.solis_s6_eh1p_total_pv_power_2", "sensor.omnik_hoofddak_inverter_omnik_hoofddak_current_power_production"),
        "grid" to "sensor.p1_meter_vermogen", "battery" to "sensor.solis_s6_eh1p_battery_power_2",
        "battery_soc" to "sensor.solis_s6_eh1p_battery_soc_2", "home" to "sensor.huisverbruik_vermogen", "ev" to "sensor.ev_laadvermogen_smartevse_w",
    )

    private fun edges(f: FlowView) = f.edges.map { "${f.nodes[it.from].id}>${f.nodes[it.to].id}:${it.power.toInt()}" }.toSet()

    // ------------------------------------------------------------------------------------------- energy

    @Test fun eveningTheBatteryRunsTheHouse() = runBlocking {
        val ha = fake("kinframe.ha-energy", "states-evening.json")
        val f = energy(ha).view() as FlowView
        assertEquals(listOf("solar", "grid", "home", "battery"), f.nodes.map { it.id })
        assertEquals(setOf("battery>home:607", "grid>home:17"), edges(f))
        val battery = f.nodes.first { it.id == "battery" }
        assertEquals(63f, battery.percent!!, 0.01f)
        assertEquals("63%", battery.value!!.replace(nbsp.toString(), ""))
        assertEquals("624${nbsp}W", f.nodes.first { it.id == "home" }.value)
        // The unitless house sensor counts as watts; the solar node is there (configured) even at 0 W.
        assertEquals("0${nbsp}W", f.nodes.first { it.id == "solar" }.value)
    }

    @Test fun sunnyDaySolarFeedsEverythingAndTheCarAppears() = runBlocking {
        val ha = fake("kinframe.ha-energy", "states-sunny.json")
        val f = energy(ha).view() as FlowView
        assertEquals(5, f.nodes.size)
        // 6,6 kW of sun (two inverters summed): the house (minus the car), the car, the battery, and the rest exported.
        assertEquals(setOf("solar>home:1300", "solar>ev:3300", "solar>battery:1200", "solar>grid:800"), edges(f))
        assertEquals("6,6${nbsp}kW", f.nodes.first { it.id == "solar" }.value)
        assertEquals(Tone.Good, f.nodes.first { it.id == "grid" }.tone)
        assertEquals("battery-charging", f.nodes.first { it.id == "battery" }.icon)
    }

    @Test fun webSocketUpdatesReplacePolling() = runBlocking {
        val ha = fake("kinframe.ha-energy", "states-evening.json")
        val k = energy(ha)
        k.instance.start(k.ctx)
        withTimeout(5_000) { while (ha.subscribed.isEmpty()) delay(20) }
        k.now += 60_000
        withTimeout(5_000) { while (k.invalidations == 0) delay(20) }
        val reads = ha.restReads.size
        assertEquals(setOf("battery>home:607", "grid>home:17"), edges(k.view() as FlowView))
        assertEquals("live data, no REST", reads, ha.restReads.size)
        // A change arrives as a "c" event: the battery stops, the grid takes over.
        val before = k.invalidations
        ha.change("sensor.solis_s6_eh1p_battery_power_2", "0")
        ha.change("sensor.p1_meter_vermogen", "624")
        k.now += 60_000
        withTimeout(5_000) { while (k.invalidations < before + 1) delay(20) }
        delay(200)
        assertEquals(setOf("grid>home:624"), edges(k.view() as FlowView))
    }

    @Test fun aWrongTokenSaysSo() = runBlocking {
        val ha = fake("kinframe.ha-energy", "states-evening.json")
        val k = kit("kinframe.ha-energy", ha, "grid" to "sensor.p1_meter_vermogen", token = "wrong")
        try { k.render(); fail() } catch (e: PluginScriptException) { assertTrue(e.message, e.message!!.contains("weigert het token")) }
    }

    @Test fun rowsStyle() = runBlocking {
        val ha = fake("kinframe.ha-energy", "states-sunny.json")
        val k = kit("kinframe.ha-energy", ha, "solar" to listOf("sensor.solis_s6_eh1p_total_pv_power_2"), "grid" to "sensor.p1_meter_vermogen", "style" to "rows")
        val rows = k.view() as RowsView
        assertEquals(listOf("Zon", "Teruglevering", "Huis"), rows.items.map { it.label })
    }

    // ------------------------------------------------------------------------------------------- car

    @Test fun kiaPresetFindsTheSensors() = runBlocking {
        val ha = fake("kinframe.ha-ev", "states.json")
        val k = kit("kinframe.ha-ev", ha, "prefix" to "ev3", "name" to "EV3")
        k.now = Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
        val main = k.view() as StackView
        val gauge = main.items[0] as GaugeView
        assertEquals(100f, gauge.percent)
        assertEquals("car", gauge.icon)
        assertEquals(listOf("Aangesloten", "EV3 · 375${nbsp}km"), (main.items[1] as TextView).lines)
        assertEquals(Instant.parse("2026-10-25T11:55:32Z").toEpochMilli(), k.ctx.dataTime)
        val detail = k.detail() as StackView
        assertEquals("8 u geleden", (detail.items.last() as TextView).lines.single())
    }

    @Test fun chargingShowsWhenItIsFull() = runBlocking {
        val ha = fake("kinframe.ha-ev", "states.json")
        ha.set("binary_sensor.ev3_ev_battery_charge", "on")
        ha.set("sensor.ev3_estimated_charge_duration", "95")
        ha.set("sensor.ev3_ev_battery_level", "64")
        val k = kit("kinframe.ha-ev", ha, "prefix" to "ev3")
        k.now = Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
        val main = k.view() as StackView
        assertEquals(Tone.Good, (main.items[0] as GaugeView).tone)
        assertEquals("Laden · vol om 22:35", (main.items[1] as TextView).lines.first())
    }

    @Test fun anyBatteryWithCustomSensors() = runBlocking {
        val ha = fake("kinframe.ha-ev", "states.json")
        val k = kit("kinframe.ha-ev", ha, "preset" to "custom", "battery_level" to "sensor.de_buzz_accu", "range" to "sensor.de_buzz_elektrisch_bereik", "name" to "De Buzz")
        val main = k.view() as StackView
        assertEquals(43f, (main.items[0] as GaugeView).percent)
        assertEquals(listOf("181${nbsp}km", "De Buzz"), (main.items[1] as TextView).lines)
    }

    // ------------------------------------------------------------------------------------------- now playing

    private fun media(ha: FakeHomeAssistant) = kit("kinframe.ha-media", ha, "entity" to "media_player.woonkamer")

    @Test fun nowPlayingWithArtProgressAndButtons() = runBlocking {
        val ha = fake("kinframe.ha-media", "states-playing.json")
        val k = media(ha)
        val main = k.view() as StackView
        val art = main.items[0] as ImageView
        assertTrue(art.src, art.src.startsWith("${ha.baseUrl}/api/media_player_proxy/media_player.woonkamer?token="))
        assertEquals(listOf("Les Cactus", "Jacques Dutronc"), (main.items[1] as TextView).lines)
        // The picture comes through the plugin's own permission check.
        assertEquals(FakeHomeAssistant.PNG.size, k.instance.image(k.ctx, art.src).size)

        val detail = k.detail() as StackView
        val progress = detail.items[1] as ProgressView
        assertEquals(61f, progress.value); assertEquals(245f, progress.max); assertTrue(progress.running); assertTrue(progress.time)
        assertEquals(Instant.parse("2026-10-25T20:00:00Z").toEpochMilli(), progress.at)
        val actions = detail.items[2] as ActionsView
        assertEquals(listOf("previous", "play_pause", "next", "volume_down", "volume_up"), actions.items.map { it.id })
        assertEquals("pause", actions.items[1].icon)
        assertEquals("Volume 35", actions.items[4].label)
    }

    @Test fun buttonsCallHomeAssistant() = runBlocking {
        val ha = fake("kinframe.ha-media", "states-playing.json")
        val k = media(ha)
        k.render()
        k.instance.action(k.ctx, "next")
        assertEquals("media_player/media_next_track", ha.serviceCalls.last().first)
        assertEquals("media_player.woonkamer", ha.serviceCalls.last().second["entity_id"]!!.jsonPrimitive.content)
        k.instance.action(k.ctx, "volume_up")
        assertEquals("media_player/volume_set", ha.serviceCalls.last().first)
        assertEquals(0.4, ha.serviceCalls.last().second["volume_level"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        val paused = JsPluginKit.parse(k.instance.action(k.ctx, "play_pause")!!) as StackView
        assertEquals("media_player/media_play_pause", ha.serviceCalls.last().first)
        assertTrue(paused.items[1] is TextView)
        assertEquals("play", ((JsPluginKit.parse(k.instance.detail(k.ctx)!!) as StackView).items[2] as ActionsView).items[1].icon)
    }

    @Test fun nothingPlayingHidesTheWidget() = runBlocking {
        val ha = fake("kinframe.ha-media", "states-playing.json")
        ha.set("media_player.woonkamer", "off")
        assertEquals(NoneView, media(ha).view())
    }

    @Test fun pluginsDeclareWhatTheyDo() {
        val media = JsPluginKit.plugin("kinframe.ha-media").manifest
        assertTrue(media.permissions.actions)
        assertFalse(JsPluginKit.plugin("kinframe.ha-energy").manifest.permissions.actions)
        assertTrue(JsPluginKit.plugin("kinframe.ha-energy").manifest.setting("solar")!!.multiple)
        assertNull(JsPluginKit.plugin("kinframe.ha-energy").manifest.refresh.staleSeconds)
    }

    @Suppress("unused") private fun JsonElement.text() = toString()
}
