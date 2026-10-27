package app.kinframe.frame.plugins.js

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JavaScript time per render of each first-party plugin with its data already in hand (no network: awaiting I/O
 * doesn't count against the budget anyway), printed for docs/plugins.md and checked against the 50 ms budget with a
 * wide margin. The RK3126 (Cortex-A7, 1.2 GHz) runs QuickJS roughly 10–20× slower than a desktop core.
 */
class PluginTimingTest {
    /** The plugin's module with a default export that runs [call] on the fixture. */
    private fun kit(id: String, call: String, fixture: String?, settings: Map<String, Any>): JsPluginKit {
        val real = JsPluginKit.plugin(id)
        val data = fixture?.let { File("../../plugins/$id/fixtures/$it").readText() } ?: "null"
        val js = real.mainJs.replace("export default {", "const live = {") + """
            const FIXTURE = $data;
            const ENTS = {};
            for (const e of (Array.isArray(FIXTURE) ? FIXTURE : [])) ENTS[e.entity_id] = { state: e.state, attributes: e.attributes || {}, updated: Date.parse(e.last_updated) };
            export default { async render(ctx) { return $call; } };
        """
        val s = JsonObject(settings.mapValues { (_, v) -> if (v is List<*>) JsonArray(v.map { JsonPrimitive(it as String) }) else JsonPrimitive(v.toString()) })
        return JsPluginKit(PluginBundle(real.manifest, js, real.locales), s)
    }

    private fun measure(name: String, kit: JsPluginKit, n: Int = 200): Double = runBlocking {
        repeat(20) { kit.render() } // warm up
        val times = (1..n).map { kit.now += 5_000; kit.render(); kit.instance.lastCallMs }.sorted()
        println("timing $name: render p50 %.3f ms, p90 %.3f ms, max %.3f ms".format(times[n / 2], times[n * 9 / 10], times.last()))
        kit.close()
        times[n / 2]
    }

    @Test fun everyPluginRendersFarInsideTheBudget() {
        val p1 = """{"active_power_w":1203,"active_power_l1_w":412,"active_power_l2_w":531,"active_power_l3_w":260,"total_power_import_kwh":13779.3,"total_power_export_kwh":5114.1,"total_gas_m3":3204.5}"""
        val results = listOf(
            measure("kinframe.homewizard-p1 (gauge, detail)", kit("kinframe.homewizard-p1",
                "(() => { record(ctx, parse($p1)); const c = current(ctx); main(c.r, c.trend, ctx.settings, ctx, c.day); return detail(c.r, c.hour, c.day, ctx); })()",
                null, mapOf("style" to "gauge"))),
            measure("kinframe.ha-energy (flow)", kit("kinframe.ha-energy", "view(snapshot(ctx.settings, ENTS), ctx, false)", "states-sunny.json", mapOf(
                "solar" to listOf("sensor.solis_s6_eh1p_total_pv_power_2", "sensor.omnik_hoofddak_inverter_omnik_hoofddak_current_power_production"),
                "grid" to "sensor.p1_meter_vermogen", "battery" to "sensor.solis_s6_eh1p_battery_power_2", "battery_soc" to "sensor.solis_s6_eh1p_battery_soc_2",
                "home" to "sensor.huisverbruik_vermogen", "ev" to "sensor.ev_laadvermogen_smartevse_w"))),
            measure("kinframe.ha-ev (detail)", kit("kinframe.ha-ev", "views(read(ctx.settings, ENTS, ctx.now()), ctx).detail", "states.json", mapOf("prefix" to "ev3"))),
            measure("kinframe.homewizard-water (gauge, detail)", kit("kinframe.homewizard-water",
                "(() => { record(ctx, parse(FIXTURE)); const c = current(ctx); main(c, ctx.settings, ctx); return detail(c, ctx.settings, ctx); })()",
                "data-running.json", mapOf("style" to "gauge"))),
            measure("kinframe.homewizard-socket (detail)", kit("kinframe.homewizard-socket",
                "(() => { record(ctx, parse(FIXTURE)); recordState(ctx, { on: true, locked: false }); const c = current(ctx); main(c, ctx.settings, ctx); return detail(c, ctx.settings, ctx); })()",
                "data.json", emptyMap())),
            measure("kinframe.ha-media (detail)", kit("kinframe.ha-media", "views(read(ENTS['media_player.woonkamer'], 'http://ha:8123', ctx.now()), ctx).detail", "states-playing.json", mapOf("entity" to "media_player.woonkamer"))),
        )
        results.forEach { assertTrue("render took $it ms", it < 5.0) }
    }
}
