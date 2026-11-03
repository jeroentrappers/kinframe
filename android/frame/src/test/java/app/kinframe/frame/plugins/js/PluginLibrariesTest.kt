package app.kinframe.frame.plugins.js

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The host's libraries (docs/plugins.md §13.1) on their own, through small inline plugins: each test's plugin writes
 * what it got to `ctx.store` ("out") for the assertions.
 */
class PluginLibrariesTest {
    private val kits = ArrayList<JsPluginKit>()

    @After fun down() { kits.forEach { it.close() } }

    /** A plugin whose `update` runs [body] with `ctx` and the libraries imported, and stores `out` as JSON. */
    private fun kit(body: String): JsPluginKit {
        val js = """
            import * as ha from 'kinframe/ha';
            import * as hw from 'kinframe/homewizard';
            import { dayTotals } from 'kinframe/day';
            import { series } from 'kinframe/series';
            export default {
              async update(ctx) { let out; $body; ctx.store.set('out', JSON.stringify(out)); },
              render() { return null; },
            };
        """
        return JsPluginKit(JsPluginKit.inline(js), JsonObject(mapOf("base_url" to JsonPrimitive("http://127.0.0.1:9")))).also { kits += it }
    }

    private suspend fun JsPluginKit.run(): String { render(); return store["out"] ?: "undefined" }

    // ------------------------------------------------------------------------------------------------ kinframe/day

    @Test fun dayCountsFromTheFirstReadingOfTheFramesDay() = runBlocking {
        val k = kit("""
            const d = dayTotals(ctx, ['imp', 'gas']);
            const v = ctx.state.v = (ctx.state.v || 0) + 1;
            d.record({ imp: 100 + v, gas: v > 1 ? 5 + v : null });
            out = [d.date, d.since({ imp: 100 + v, gas: v > 1 ? 5 + v : null })];
        """)
        assertEquals("""["2026-10-25",{"imp":0,"gas":null}]""", k.run())
        assertEquals("2026-10-25|101|", k.store["day"])
        // gas appears later in the day: it counts from its first value, not from nothing
        assertEquals("""["2026-10-25",{"imp":1,"gas":0}]""", k.run())
        assertEquals("2026-10-25|101|7", k.store["day"])
        assertEquals("""["2026-10-25",{"imp":2,"gas":1}]""", k.run())
        // the frame's day, not UTC's: 23:30 in Brussels on the 25th is still the 25th…
        k.now = java.time.Instant.parse("2026-10-25T22:30:00Z").toEpochMilli()
        assertEquals("""["2026-10-25",{"imp":3,"gas":2}]""", k.run())
        // …and 00:30 is the next day
        k.now = java.time.Instant.parse("2026-10-25T23:30:00Z").toEpochMilli()
        assertEquals("""["2026-10-26",{"imp":0,"gas":0}]""", k.run())
    }

    @Test fun dayStartsAgainAfterAMeterReset() = runBlocking {
        val k = kit("""
            const d = dayTotals(ctx, ['m3']);
            const v = ctx.state.v = (ctx.state.v === undefined ? 0 : ctx.state.v + 1);
            const total = [500, 500.2, 0.05, 0.1][v];
            d.record({ m3: total });
            out = Math.round(d.since({ m3: total }).m3 * 1000) / 1000;
        """)
        assertEquals("0", k.run())
        assertEquals("0.2", k.run())
        assertEquals("0", k.run()) // replaced meter: from here, not 0 for the rest of the day
        assertEquals("0.05", k.run())
    }

    @Test fun dayBaselineSurvivesARestart() = runBlocking {
        val first = kit("dayTotals(ctx, ['kwh']).record({ kwh: 10 })")
        first.run()
        val k = kit("out = dayTotals(ctx, ['kwh']).since({ kwh: 12.5 }).kwh")
        k.store.putAll(first.store)
        assertEquals("2.5", k.run())
    }

    // ------------------------------------------------------------------------------------------------ kinframe/series

    @Test fun seriesAveragesPerStepAndShowsTrendAndHour() = runBlocking {
        val k = kit("""
            const s = series(ctx, 'w', { stepMs: 5000, samples: 10 });
            const t0 = ctx.now();
            for (let i = 0; i < 5; i++) s.add(100 + i * 10, t0 + i * 1000); // one step: averaged
            const one = [s.count, s.last()];
            for (let i = 1; i <= 6; i++) s.add(100, t0 + i * 5000);
            const flat = s.trend();
            s.add(400, t0 + 7 * 5000);
            const up = s.trend();
            for (let i = 8; i < 20; i++) s.add(50, t0 + i * 5000); // wraps the 10 samples around
            out = { one, flat, up, down: s.trend({ min: 1000 }), count: s.count, hour: s.buckets(60000, 4) };
        """)
        assertEquals("""{"one":[1,120],"flat":null,"up":"up","down":null,"count":10,"hour":[50,50,50,50]}""", k.run())
    }

    @Test fun seriesIgnoresMissingReadings() = runBlocking {
        val k = kit("const s = series(ctx, 'x'); s.add(null, 1); s.add(NaN, 2); s.add('12', 3); out = [s.count, s.last(), s.buckets()]")
        assertEquals("[0,null,[]]", k.run())
    }

    // ------------------------------------------------------------------------------------------------ kinframe/homewizard

    @Test fun homewizardHelpers() = runBlocking {
        val k = kit("""
            out = [hw.baseUrl(' 192.168.1.5/ '), hw.baseUrl('https://meter.local'),
              hw.total({ total_power_import_kwh: 3 }, 'total_power_import'),
              hw.total({ total_power_import_t1_kwh: 1, total_power_import_t2_kwh: 2.5 }, 'total_power_import'),
              hw.total({}, 'total_power_import'), hw.number({ a: '1' }, 'a'),
              hw.phases({ active_power_l1_w: 1, active_power_l3_w: 3 })];
        """)
        assertEquals("""["http://192.168.1.5","https://meter.local",3,3.5,null,null,[1,3]]""", k.run())
    }

    // ------------------------------------------------------------------------------------------------ kinframe/ha

    @Test fun haAppliesSubscribeEntitiesEvents() = runBlocking {
        val k = kit("""
            const e = {};
            ha.applyEvent(e, { a: { 'sensor.p': { s: '12', a: { unit_of_measurement: 'W', x: 1 }, lu: 1761400000.5 } } });
            ha.applyEvent(e, { c: { 'sensor.p': { '+': { s: '15', a: { y: 2 } }, '-': { a: ['x'] } } } });
            ha.applyEvent(e, { a: { 'sensor.q': { s: 'on' } } });
            ha.applyEvent(e, { r: ['sensor.q'] });
            out = e;
        """)
        assertEquals("""{"sensor.p":{"state":"15","attributes":{"unit_of_measurement":"W","y":2},"updated":1761400000500}}""", k.run())
    }

    @Test fun haTokenRefusedFallsBackToEnglish() = runBlocking {
        // An inline plugin has no locales, so ctx.t('token_refused') has no text of its own.
        val k = kit("ha.connection(ctx).error = 'token'; ha.requireToken(ctx)")
        try { k.render(); fail() } catch (e: PluginScriptException) { assertTrue(e.message, e.message!!.endsWith("Home Assistant refused the token")) }
    }
}
