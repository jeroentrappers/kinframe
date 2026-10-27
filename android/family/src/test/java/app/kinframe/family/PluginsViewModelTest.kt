package app.kinframe.family

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.CatalogPlugin
import app.kinframe.core.api.FieldError
import app.kinframe.core.api.FramePlugins
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Problem
import app.kinframe.core.api.SettingsDocument
import app.kinframe.core.plugin.settings.InstanceFieldError
import app.kinframe.core.plugin.settings.PluginSettings
import app.kinframe.family.ui.frame.PluginsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun obj(s: String) = KinJson.parseToJsonElement(s).jsonObject

    private val manifest = obj("""{"id":"kinframe.homewizard-p1","name":"HomeWizard P1 meter","version":"1.0.0","slots":["top-left","top-right"],
        "settings":[{"key":"host","type":"url","label":"Meter address","required":true},{"key":"token","type":"secret","label":"Token"}]}""")
    private val catalog = FramePlugins(listOf(CatalogPlugin(manifest)), catalogAt = "2026-10-27T10:00:00Z")

    /** A settings store with versions, recording the patches it got. */
    private open inner class Server(var doc: JsonObject) : FakeApi() {
        val patches = mutableListOf<JsonObject>()
        var conflictOnce = false
        var reject: Problem? = null
        var pluginCalls = 0
        override suspend fun settings(frameId: String) = SettingsDocument(doc)
        override suspend fun framePlugins(frameId: String): FramePlugins { pluginCalls++; return catalog }
        override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument {
            if (conflictOnce) {
                conflictOnce = false
                doc = obj("""{"version":${version + 1},"slideshow":{"interval_s":60},"plugins":${doc["plugins"] ?: "{}"}}""")
                throw ApiException(412, Problem(code = "version_conflict"))
            }
            reject?.let { throw ApiException(422, it) }
            val current = (doc["version"] as JsonPrimitive).content.toInt()
            assertEquals(current, version)
            patches += patch
            val merged = app.kinframe.core.api.MergePatch.apply(doc, patch).jsonObject
            doc = JsonObject(merged + ("version" to JsonPrimitive(current + 1)))
            return SettingsDocument(doc)
        }
    }

    @Test
    fun addSavesACompleteNewInstanceSwitchedOn() {
        val api = Server(obj("""{"version":3}"""))
        val vm = PluginsViewModel(api, "f1")
        assertEquals(1, vm.state.value.catalog.size)
        vm.add(vm.state.value.catalog.single())
        val e = vm.state.value.editor!!
        assertTrue(e.isNew)
        assertFalse(e.draft.enabled)
        assertTrue(Regex("^p-[a-z0-9]{10}$").matches(e.draft.instanceId))
        vm.update { PluginSettings.withValue(it, "host", JsonPrimitive("192.168.1.50")) }
        vm.update { it.copy(secretWrites = mapOf("token" to "s3cret")) }
        vm.saveEditor()
        assertNull(vm.state.value.editor)
        val sent = (api.patches.single()["plugins"]!!.jsonObject["instances"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive(true), sent["enabled"])
        assertEquals(obj("""{"token":"s3cret"}"""), sent["secrets"])
        assertEquals(1, vm.state.value.instances.size)
    }

    @Test
    fun incompleteNewInstanceIsSavedOff() {
        val api = Server(obj("""{"version":1}"""))
        val vm = PluginsViewModel(api, "f1")
        vm.add(vm.state.value.catalog.single())
        vm.saveEditor()
        val sent = (api.patches.single()["plugins"]!!.jsonObject["instances"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive(false), sent["enabled"])
        assertFalse("secrets" in sent)
    }

    @Test
    fun switchingOnAnIncompleteInstanceOpensItsForm() {
        val api = Server(obj("""{"version":1,"plugins":{"instances":[{"instance_id":"p-1","plugin_id":"kinframe.homewizard-p1","enabled":false,"settings":{}}]}}"""))
        val vm = PluginsViewModel(api, "f1")
        vm.setEnabled("p-1", true)
        assertTrue(api.patches.isEmpty())
        val e = vm.state.value.editor!!
        assertEquals(listOf(InstanceFieldError("host", "settings", "required")), e.errors)
        vm.update { PluginSettings.withValue(it, "host", JsonPrimitive("http://p1.local")) }
        assertTrue(vm.state.value.editor!!.errors.isEmpty())
    }

    @Test
    fun conflictReloadsAndAppliesTheChangeAgain() {
        val api = Server(obj("""{"version":1,"plugins":{"instances":[{"instance_id":"p-1","plugin_id":"kinframe.homewizard-p1","enabled":true,"settings":{"host":"x"},"secrets_set":["token"]}]}}"""))
        val vm = PluginsViewModel(api, "f1")
        api.conflictOnce = true
        vm.setLayout("mode", "band")
        vm.setEnabled("p-1", false)
        assertEquals(2, api.patches.size)
        assertEquals("band", PluginSettings.layout(api.doc).mode)
        assertEquals(JsonPrimitive(60), api.doc["slideshow"]!!.jsonObject["interval_s"])
        val inst = (api.patches[1]["plugins"]!!.jsonObject["instances"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive(false), inst["enabled"])
        assertFalse("secrets_set" in inst)
    }

    @Test
    fun serverFieldErrorsShowAtTheirFields() {
        val api = Server(obj("""{"version":1,"plugins":{"instances":[{"instance_id":"p-0","plugin_id":"kinframe.homewizard-p1","enabled":false,"settings":{}},
            {"instance_id":"p-1","plugin_id":"kinframe.homewizard-p1","enabled":false,"settings":{"host":"x"}}]}}"""))
        val vm = PluginsViewModel(api, "f1")
        vm.edit("p-1")
        api.reject = Problem(code = "validation_failed", errors = listOf(FieldError("plugins.instances.1.settings.host", "type"), FieldError("plugins.instances.0.slot", "enum")))
        vm.saveEditor()
        assertNotNull(vm.state.value.editor)
        assertEquals(listOf(InstanceFieldError("host", "settings", "type")), vm.state.value.editor!!.errors)
    }

    @Test
    fun removeSendsTheListWithoutIt() {
        val api = Server(obj("""{"version":1,"plugins":{"instances":[{"instance_id":"p-1","plugin_id":"kinframe.homewizard-p1","enabled":false}]}}"""))
        val vm = PluginsViewModel(api, "f1")
        vm.edit("p-1")
        assertTrue(api.pluginCalls >= 2)
        vm.askRemove(true)
        assertTrue(vm.state.value.editor!!.confirmRemove)
        vm.remove()
        assertEquals(JsonArray(emptyList()), api.patches.single()["plugins"]!!.jsonObject["instances"])
        assertNull(vm.state.value.editor)
    }
}
