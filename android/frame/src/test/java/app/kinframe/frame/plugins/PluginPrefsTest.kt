package app.kinframe.frame.plugins

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MergePatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The frame's plugin settings live in the frame settings (`plugins`, docs/plugins.md §6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginPrefsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun tearDown() { scope.cancel() }

    private fun doc(json: String) = KinJson.parseToJsonElement(json).jsonObject

    private val serverDoc = doc(
        """{"version": 7, "plugins": {"layout": {"mode": "band", "side": "left"}, "instances": [
            {"instance_id": "p-energy01", "plugin_id": "kinframe.ha-energy", "slot": "top-left", "enabled": true,
             "settings": {"base_url": "http://ha.local:8123", "grid": "sensor.grid"}, "secrets": {"token": "s3cret"}, "secrets_set": ["token"]},
            {"instance_id": "p-broken", "plugin_id": 7}
        ]}}""",
    )

    @Test fun theSettingsDocumentWithSecretsForThePlugin() {
        val layout = PluginSettingsDoc.layout(serverDoc)!!
        assertEquals(PluginLayoutMode.Band, layout.mode)
        assertEquals(BandSide.Left, layout.side)
        val list = PluginSettingsDoc.instances(serverDoc)!!
        assertEquals(listOf("p-energy01"), list.map { it.instanceId })
        val i = list[0]
        assertEquals(Slot.TopLeft, i.slot)
        assertNull("secrets stay out of the plain settings", i.settings["token"])
        assertEquals("s3cret", i.values["token"]!!.jsonPrimitive.content)
        assertEquals("sensor.grid", i.values["grid"]!!.jsonPrimitive.content)
        assertNull(PluginSettingsDoc.layout(doc("""{"version": 1}""")))
        assertNull(PluginSettingsDoc.instances(doc("""{"version": 1}""")))
    }

    @Test fun aPatchSetsTheWholePluginsPartWithTheSecrets() {
        val list = PluginSettingsDoc.instances(serverDoc)!!
        val patch = PluginSettingsDoc.patch(PluginLayoutConfig(mode = PluginLayoutMode.Overlay), list)
        val p = patch["plugins"]!!.jsonObject
        assertEquals(setOf("mode", "side", "background"), p["layout"]!!.jsonObject.keys)
        val sent = p["instances"]!!.jsonArray[0].jsonObject
        assertEquals("s3cret", sent["secrets"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        // Applied on the frame (the outbox merges it into what it shows) it reads back the same.
        val merged = MergePatch.apply(serverDoc, patch).jsonObject
        assertEquals(list, PluginSettingsDoc.instances(merged))
        assertEquals(PluginLayoutMode.Overlay, PluginSettingsDoc.layout(merged)!!.mode)
    }

    /** A frame that ran plugins before step 3: its local settings show until the frame settings have a `plugins` part. */
    @Test fun localSettingsMoveIntoTheFrameSettings() = runBlocking {
        val raw = MutableStateFlow(doc("""{"version": 3}"""))
        val sent = mutableListOf<JsonObject>()
        var session = false
        val edit: (JsonObject) -> Boolean = { patch -> if (session) { sent += patch; raw.value = MergePatch.apply(raw.value, patch).jsonObject; true } else false }
        val prefs = PluginPrefs(context, raw, scope, edit)
        val p1 = InstanceConfig("builtin-homewizard-p1", "kinframe.homewizard-p1", settings = JsonObject(mapOf("host" to JsonPrimitive("192.168.1.50"))))

        // Without a session the change stays on the frame.
        prefs.setLayout(PluginLayoutConfig(mode = PluginLayoutMode.Overlay))
        prefs.upsert(p1)
        assertEquals(listOf(p1), prefs.instances.value)
        assertTrue(sent.isEmpty())

        // A new start reads it back; with a session it moves into the frame settings once.
        val again = PluginPrefs(context, raw, scope, edit)
        assertEquals(listOf(p1), again.instances.value)
        session = true
        again.migrate()
        assertEquals(1, sent.size)
        assertEquals(PluginLayoutMode.Overlay, PluginSettingsDoc.layout(raw.value)!!.mode)
        again.migrate()
        assertEquals(1, sent.size)

        // Settings an older version stored that no manifest declares any more stay behind (the server refuses them).
        val legacy = MutableStateFlow(doc("""{"version": 3}"""))
        val sentLegacy = mutableListOf<JsonObject>()
        val old = PluginPrefs(context, legacy, scope) { patch -> sentLegacy += patch; true }
        legacy.value = doc("""{"version": 3}""")
        session = false
        prefs.upsert(InstanceConfig("builtin-weather-station", "kinframe.weather-station", settings = JsonObject(mapOf(
            "device" to JsonPrimitive("9287156658"), "use_for_weather" to JsonPrimitive(true)))))
        session = true
        PluginPrefs(context, legacy, scope) { patch -> sentLegacy += patch; true }
            .migrate { id -> if (id == "kinframe.weather-station") setOf("device", "source") else null }
        val station = PluginSettingsDoc.instances(sentLegacy.last())!!.single { it.pluginId == "kinframe.weather-station" }
        assertEquals(setOf("device"), station.settings.keys)
        assertTrue(old.instances.value.isNotEmpty())

        // From then on the frame settings win: a change made in an app shows on the frame.
        raw.value = MergePatch.apply(raw.value, doc("""{"plugins": {"instances": []}}""")).jsonObject
        withTimeout(1_000) { again.instances.first { it.isEmpty() } }
        assertEquals(PluginLayoutMode.Overlay, again.layout.value.mode)
        // And a change made on the frame goes into them.
        again.upsert(p1.copy(slot = Slot.TopLeft))
        assertEquals(Slot.TopLeft, PluginSettingsDoc.instances(raw.value)!!.single().slot)
    }
}
