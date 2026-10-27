package app.kinframe.frame.plugins

import app.kinframe.frame.plugins.js.FakeHomeAssistant
import app.kinframe.frame.plugins.js.JsPluginKit
import app.kinframe.frame.plugins.js.PluginManifest
import app.kinframe.frame.plugins.js.PluginNet
import app.kinframe.frame.plugins.js.PluginPermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the frame reports about its plugins for the apps' settings forms (docs/plugins.md §6). */
class PluginReportsTest {
    /** The plugin folders the frame app ships (top-level `plugins/<id>/`, as the build copies them). */
    private fun shipped(): List<PluginReports.Source> =
        JsPluginKit.ROOT.resolve("plugins").listFiles { f -> f.isDirectory && f.resolve("plugin.json").isFile }!!.sortedBy { it.name }.map { dir ->
            PluginReports.Source(
                dir.resolve("plugin.json").readText(),
                dir.resolve("locales").listFiles { f -> f.name.endsWith(".json") }.orEmpty().associate { it.name.removeSuffix(".json") to it.readText() },
            )
        }

    @Test fun theCatalogHoldsEveryShippedPluginWithItsManifestTranslations() {
        val catalog = PluginReports.catalog(shipped())
        val plugins = (catalog["plugins"] as JsonArray).map { it.jsonObject }
        val ids = plugins.map { it["manifest"]!!.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("kinframe.ha-energy", "kinframe.ha-ev", "kinframe.ha-media", "kinframe.homewizard-p1", "kinframe.homewizard-socket",
                "kinframe.homewizard-water", "kinframe.rain-radar", "kinframe.weather-station", "kinframe.world-clock"),
            ids,
        )
        val p1 = plugins[ids.indexOf("kinframe.homewizard-p1")]
        val nl = p1["locales"]!!.jsonObject["nl"]!!.jsonObject
        assertEquals("Adres van de meter", nl["@setting.host"]!!.jsonPrimitive.content)
        assertNull("the plugin's own strings stay on the frame", nl["using"])
        assertEquals(JsonPrimitive(true), p1["builtin"])
        // Every manifest key in every translation names a setting, option or preset of its plugin.
        plugins.forEach { p ->
            val m = PluginManifest.parse(p["manifest"].toString())
            (p["locales"] as? JsonObject)?.forEach { (tag, strings) ->
                strings.jsonObject.keys.forEach { k -> assertTrue("${m.id} $tag $k", names(m, k)) }
            }
        }
    }

    private fun names(m: PluginManifest, key: String): Boolean {
        val parts = key.removePrefix("@").split('.')
        return when (parts[0]) {
            "name", "description" -> parts.size == 1
            "setting" -> m.setting(parts[1]) != null && (parts.size == 2 || parts[2] in setOf("help", "placeholder"))
            "option" -> m.setting(parts[1])?.options?.contains(parts.drop(2).joinToString(".")) == true
            "preset" -> m.preset(parts[1]) != null
            else -> false
        }
    }

    @Test fun aBrokenManifestIsLeftOut() {
        val catalog = PluginReports.catalog(listOf(PluginReports.Source("""{"id":"x"}""", emptyMap())) + shipped().take(1))
        assertEquals(1, (catalog["plugins"] as JsonArray).size)
    }

    @Test fun servicesToDiscoverComeFromUrlSettings() {
        val manifests = shipped().map { PluginManifest.parse(it.manifest) }
        assertEquals(setOf("_home-assistant._tcp", "_hwenergy._tcp"), PluginReports.serviceTypes(manifests))
    }

    @Test fun entityLinesAsHomeAssistantRendersThem() {
        // The last line comes trimmed: Home Assistant strips the rendered text.
        val text = "sensor.p1_meter_vermogen\tP1 Meter Vermogen\tW\tpower\nlight.hal\tHal\t\t\nnot an entity\tx\t\t\nsensor.injectieprijs\tinjectieprijs volgend kwartier"
        assertEquals(
            listOf(
                PluginReports.Entity("sensor.p1_meter_vermogen", "P1 Meter Vermogen", "W", "power"),
                PluginReports.Entity("light.hal", "Hal", "", ""),
                PluginReports.Entity("sensor.injectieprijs", "injectieprijs volgend kwartier", "", ""),
            ),
            PluginReports.parseEntities(text),
        )
    }

    @Test fun entitiesAreListedThroughTheInstancesOwnPermissions() = runBlocking {
        val ha = FakeHomeAssistant(File("../../plugins/kinframe.ha-energy/fixtures/states-sunny.json"))
        try {
            val manifest = JsPluginKit.plugin("kinframe.ha-energy").manifest
            val values = JsonObject(mapOf("base_url" to JsonPrimitive(ha.baseUrl), "token" to JsonPrimitive(ha.token)))
            val (base, token) = PluginReports.entityConnection(manifest, values)!!
            val net = PluginNet(OkHttpClient(), PluginPermissions(manifest, values), JsPluginKit.LoopbackIsHome)
            val list = PluginReports.fetchEntities(net, base, token)
            assertEquals(ha.states.size, list.size)
            assertEquals(listOf("sensor.p1_meter_vermogen", "P1 Meter Vermogen", "W"), list[0].let { listOf(it.id, it.name, it.unit) })
            assertEquals(1, ha.templates)
            // A wrong token is an error, not an empty list.
            val refused = runCatching { PluginReports.fetchEntities(net, base, "wrong") }
            assertTrue(refused.isFailure)
            // Not on the home network: refused before anything is sent (the plugin's local_only rule).
            val outside = JsonObject(mapOf("base_url" to JsonPrimitive("http://8.8.8.8:8123"), "token" to JsonPrimitive("t")))
            val away = PluginNet(OkHttpClient(), PluginPermissions(manifest, outside))
            assertTrue(runCatching { PluginReports.fetchEntities(away, "http://8.8.8.8:8123", "t") }.exceptionOrNull() is SecurityException)
        } finally {
            ha.server.close()
        }
    }

    @Test fun onlyPluginsWithEntitySettingsAndAConnectionListEntities() {
        val p1 = JsPluginKit.plugin("kinframe.homewizard-p1").manifest
        val energy = JsPluginKit.plugin("kinframe.ha-energy").manifest
        val full = JsonObject(mapOf("base_url" to JsonPrimitive("http://ha.local:8123"), "token" to JsonPrimitive("t"), "host" to JsonPrimitive("192.168.1.5")))
        assertNull(PluginReports.entityConnection(p1, full))
        assertNull(PluginReports.entityConnection(energy, JsonObject(mapOf("base_url" to JsonPrimitive("http://ha.local:8123")))))
        assertEquals("http://ha.local:8123" to "t", PluginReports.entityConnection(energy, full))
        assertFalse(PluginReports.connectionKey("a", "t1") == PluginReports.connectionKey("a", "t2"))
    }

    @Test fun theStatusReport() {
        val report = PluginReports.status(
            listOf(
                PluginReports.InstanceReport("p1", running = true, error = null, updatedAtMs = 1_767_225_600_000, entities = null),
                PluginReports.InstanceReport("energy", running = false, error = "x".repeat(400), updatedAtMs = null,
                    entities = listOf(PluginReports.Entity("sensor.grid", "Grid", "W", ""))),
            ),
            mapOf("_hwenergy._tcp" to listOf(FoundService("P1 meter", "192.168.1.20", 80, emptyMap()), FoundService("Socket", "192.168.1.21", 8080, emptyMap()))),
        )
        val i = (report["instances"] as JsonArray).map { it.jsonObject }
        assertEquals("2026-01-01T00:00:00Z", i[0]["updated_at"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, i[0]["error"])
        assertNull(i[0]["entities"])
        assertEquals(300, i[1]["error"]!!.jsonPrimitive.content.length)
        assertEquals(JsonObject(mapOf("entity_id" to JsonPrimitive("sensor.grid"), "name" to JsonPrimitive("Grid"), "unit" to JsonPrimitive("W"))), (i[1]["entities"] as JsonArray)[0])
        val found = (report["services"]!!.jsonObject["_hwenergy._tcp"] as JsonArray).map { it.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(listOf("http://192.168.1.20", "http://192.168.1.21:8080"), found)
        // Without times, an update that only moved the clock compares equal.
        val a = PluginReports.status(listOf(PluginReports.InstanceReport("p1", true, null, 1, null)), emptyMap(), withTimes = false)
        val b = PluginReports.status(listOf(PluginReports.InstanceReport("p1", true, null, 2, null)), emptyMap(), withTimes = false)
        assertEquals(a, b)
    }
}
