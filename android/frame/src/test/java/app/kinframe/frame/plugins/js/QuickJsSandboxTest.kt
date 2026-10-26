package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The plugin host's sandbox (docs/plugins.md §5): permissions, limits, the `ctx` bridge and failure handling. */
class QuickJsSandboxTest {
    private lateinit var server: MockWebServer
    private val kits = ArrayList<JsPluginKit>()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { kits.forEach { it.close() }; server.close() }

    private fun kit(js: String, baseUrl: String = "http://${server.hostName}:${server.port}", policy: LocalPolicy = JsPluginKit.LoopbackIsHome, manifest: String = JsPluginKit.MANIFEST) =
        JsPluginKit(JsPluginKit.inline(js, manifest), JsonObject(mapOf("base_url" to JsonPrimitive(baseUrl))), policy = policy).also { kits += it }

    private fun text(v: Any?) = (JsPluginKit.parse(v as kotlinx.serialization.json.JsonElement) as TextView).lines.first()

    private suspend fun failure(k: JsPluginKit): String = try {
        k.render(); fail("expected a failure"); ""
    } catch (e: PluginScriptException) { e.message!! }

    @Test fun httpOnlyToTheAddressInTheSettings() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"v":3}""").build())
        val ok = kit("""export default { async render(ctx) { const r = await ctx.http.get(ctx.settings.base_url + '/x'); return { type: 'text', lines: ['v' + r.json().v + ' ' + r.status] }; } }""")
        assertEquals("v3 200", text(ok.render()))
        val other = kit("""export default { async render(ctx) { await ctx.http.get('https://example.org/steal?t=1'); return null; } }""")
        assertTrue(failure(other).contains("not allowed"))
    }

    @Test fun localOnlyRefusesPublicAddressesEvenAsIpLiterals() = runBlocking {
        val k = kit("""export default { async render(ctx) { await ctx.http.get(ctx.settings.base_url + '/'); return null; } }""",
            baseUrl = "http://8.8.8.8", policy = LocalPolicy.Frame)
        assertTrue(failure(k).contains("not on the home network"))
    }

    @Test fun localOnlyDnsRefusesLoopback() {
        try {
            app.kinframe.frame.plugins.LocalAddress.dns.lookup("localhost"); fail("loopback is not the home network")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(e.message!!.contains("home network"))
        }
    }

    @Test fun anEndlessLoopIsInterruptedAndTheInstanceKeepsWorking() = runBlocking {
        val k = kit("""let spin = true; export default { async render(ctx) { if (spin) { spin = false; while (true) {} } return { type: 'text', lines: ['alive'] }; } }""")
        val why = failure(k)
        assertTrue(why, why.contains("took longer than 50 ms"))
        assertEquals("alive", text(k.render()))
    }

    @Test fun waitingForTheNetworkDoesNotCountAsJavaScript() = runBlocking {
        // A slow answer (Wi-Fi on the frame) followed by a little work: the 50 ms are for JavaScript, not for the wait.
        repeat(2) { server.enqueue(MockResponse.Builder().body("""{"v":1}""").bodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).build()) }
        val k = kit("""export default { async render(ctx) { const r = await ctx.http.get(ctx.settings.base_url + '/x'); let n = 0; for (let i = 0; i < 20000; i++) n += i % 7; return { type: 'text', lines: ['v' + r.json().v] }; } }""")
        assertEquals("v1", text(k.render()))
        assertEquals("v1", text(k.render()))
    }

    @Test fun anEndlessLoopAfterAnAwaitIsStillInterrupted() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val k = kit("""export default { async render(ctx) { await ctx.http.get(ctx.settings.base_url + '/x'); while (true) {} } }""")
        val why = failure(k)
        assertTrue(why, why.contains("took longer than 50 ms"))
    }

    @Test fun runningOutOfMemoryFailsAndRestartsTheRuntime() = runBlocking {
        val k = kit("""export default { async render(ctx) { if (!ctx.store.get('boom')) { ctx.store.set('boom', '1'); const a = []; for (;;) a.push(new Array(100000).fill(1)); } return { type: 'text', lines: ['fresh ' + ('x' in ctx.state)] }; } }""")
        assertTrue(failure(k).contains("out of memory"))
        assertEquals("fresh false", text(k.render()))
    }

    @Test fun storeIsLimited() = runBlocking {
        val k = kit("""export default { async render(ctx) { ctx.store.set('big', 'x'.repeat(70000)); return null; } }""")
        assertTrue(failure(k).contains("store full"))
    }

    @Test fun theBridge() = runBlocking {
        val k = kit("""export default { async render(ctx) {
            const lt = ctx.localTime(Date.UTC(2026, 9, 25, 23, 30));
            return { type: 'text', lines: [[ctx.settings.n, ctx.format.power(1234), ctx.format.number(2.345, 1, 'm/s'), ctx.t('hello {who}', { who: 'Jo' }),
              ctx.timezone, ctx.language, lt.date, lt.hour, lt.weekday, typeof __kf, typeof fetch, typeof require, typeof XMLHttpRequest].join('|')] };
        } }""")
        val line = ((k.render() as JsonObject)["lines"] as kotlinx.serialization.json.JsonArray)[0].let { (it as JsonPrimitive).content }
        assertEquals("7|1,2\u00a0kW|2,3\u00a0m/s|hello Jo|Europe/Brussels|nl|2026-10-26|0|1|undefined|undefined|undefined|undefined", line)
    }

    @Test fun invalidateIsThrottledToMinSeconds() = runBlocking {
        val k = kit("""export default { async render(ctx) { return { type: 'text', lines: ['r'] }; }, async detail(ctx) { ctx.invalidate(); return null; } }""")
        k.render()
        k.instance.detail(k.ctx) // right after a render: waits for min_seconds
        assertEquals(0, k.invalidations)
        k.now += 6_000
        k.instance.detail(k.ctx)
        assertEquals(1, k.invalidations)
    }

    @Test fun actionsNeedThePermission() = runBlocking {
        val js = """export default { async render(ctx) { return null; }, async action(ctx, id) { return { type: 'text', lines: ['did ' + id] }; } }"""
        val k = kit(js)
        k.render()
        assertEquals("did next", text(k.instance.action(k.ctx, "next")))
        val noPerm = kit(js, manifest = JsPluginKit.MANIFEST.replace("\"actions\":true", "\"actions\":false"))
        noPerm.render()
        try { noPerm.instance.action(noPerm.ctx, "next"); fail() } catch (_: SecurityException) {}
    }

    @Test fun webSocketMessagesReachThePlugin() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("hello")) webSocket.send("""{"v":42}""")
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {}
        }).build())
        val k = kit("""export default {
            async start(ctx) {
              ctx.ws.connect(ctx.settings.base_url.replace(/^http/, 'ws') + '/ws', {
                onOpen: (ws) => ws.send({ hello: true }),
                onMessage: (ws, m) => { ctx.state.v = m.v; ctx.invalidate(); },
              });
              return null;
            },
            async render(ctx) { return { type: 'text', lines: ['v=' + ctx.state.v] }; },
        }""")
        k.instance.start(k.ctx)
        k.now += 60_000 // past min_seconds, so the invalidate goes through at once
        withTimeout(5_000) { while (k.invalidations == 0) delay(20) }
        assertEquals("v=42", text(k.render()))
    }

    @Test fun aThrowIsAFailureWithTheMessage() = runBlocking {
        val k = kit("""export default { async render(ctx) { throw new Error('sensor missing'); } }""")
        assertTrue(failure(k).contains("sensor missing"))
    }

    @Test fun manifestValidation() {
        val bad = listOf(
            """{"id":"Bad Id","name":"x","version":"1.0.0","api":1}""",
            """{"id":"community.x","name":"x","version":"1.0","api":1}""",
            """{"id":"community.x","name":"x","version":"1.0.0","api":2}""",
            """{"id":"community.x","name":"x","version":"1.0.0","api":1,"permissions":{"http":["http://plain.example"]}}""",
            """{"id":"community.x","name":"x","version":"1.0.0","api":1,"permissions":{"http":["settings.nope"]}}""",
            """{"id":"community.x","name":"x","version":"1.0.0","api":1,"settings":[{"key":"a","type":"text"}],"permissions":{"http":["settings.a"]}}""",
            """{"id":"community.x","name":"x","version":"1.0.0","api":1,"refresh":{"min_seconds":1}}""",
        )
        bad.forEach { m -> try { PluginManifest.parse(m); fail("accepted: $m") } catch (_: ManifestException) {} }
        // Every plugin in the repository has a valid manifest.
        JsPluginKit.ROOT.resolve("plugins").listFiles { f -> f.isDirectory && f.resolve("plugin.json").isFile }!!.forEach { dir ->
            val b = PluginBundle.fromDirectory(dir)
            assertEquals(dir.name, b.manifest.id)
        }
    }
}
