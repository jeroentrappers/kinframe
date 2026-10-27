package app.kinframe.core.plugin.settings

import app.kinframe.core.api.CatalogPlugin
import app.kinframe.core.api.DiscoveredEntity
import app.kinframe.core.api.FieldError
import app.kinframe.core.api.FramePlugins
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PluginInstanceStatus
import app.kinframe.core.api.Problem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PluginSettingsTest {
    /** The repository's `plugins/` folder (tests run in `android/core`). */
    private val pluginsDir: File = listOf(File("../../plugins"), File("../plugins"), File("plugins")).first { it.isDirectory }

    private fun real(id: String): FormPlugin {
        val dir = File(pluginsDir, id).takeIf { it.isDirectory } ?: File(pluginsDir, "examples/$id")
        val manifest = KinJson.parseToJsonElement(File(dir, "plugin.json").readText()).jsonObject
        val locales = File(dir, "locales").listFiles { f -> f.name.endsWith(".json") }.orEmpty().associate { f ->
            f.name.removeSuffix(".json") to KinJson.parseToJsonElement(f.readText()).jsonObject.mapValues { (_, v) -> (v as JsonPrimitive).content }
        }
        return FormPlugin.read(CatalogPlugin(manifest, locales, builtin = true))!!
    }

    private fun obj(s: String) = KinJson.parseToJsonElement(s).jsonObject

    @Test
    fun readsTheRealManifests() {
        val energy = real("kinframe.ha-energy")
        assertEquals("kinframe.ha-energy", energy.id)
        assertEquals(listOf("base_url", "token", "solar", "grid"), energy.settings.take(4).map { it.key })
        val url = energy.setting("base_url")!!
        assertEquals(SettingType.URL, url.type)
        assertEquals("_home-assistant._tcp", url.discover)
        assertTrue(url.required && url.localOnly && url.mustFill)
        assertTrue(energy.setting("solar")!!.multiple)
        assertEquals("W", energy.setting("grid")!!.unit)
        assertEquals("sensor", energy.setting("grid")!!.domain)
        assertEquals(listOf("import", "export"), energy.setting("grid_sign")!!.options)
        assertFalse(energy.setting("grid_sign")!!.mustFill)
        assertTrue(energy.hosts.isEmpty())
        assertFalse(energy.location)

        val p1 = real("kinframe.homewizard-p1")
        assertEquals(SettingType.NUMBER, p1.setting("max_w")!!.type)
        assertEquals(JsonPrimitive(4000), p1.setting("max_w")!!.default)

        val air = real("community.air-quality")
        assertEquals(listOf("https://air-quality-api.open-meteo.com"), air.hosts)
        assertTrue(air.location)
    }

    @Test
    fun localeFallback() {
        val p1 = real("kinframe.homewizard-p1")
        assertEquals("HomeWizard P1-meter", p1.name("nl"))
        assertEquals("HomeWizard P1-meter", p1.name("nl-BE"))
        assertEquals(p1.name("en"), p1.name("xx"))
        val s = p1.setting("style")!!
        val plugin = p1.copy(locales = mapOf(
            "en" to mapOf("@option.style.gauge" to "Gauge"),
            "nl" to mapOf("@setting.style" to "Toon als", "@setting.style.help" to "Hulp", "@option.style.flow" to "Stroom", "@preset.x" to "Voor X"),
            "zh-CN" to mapOf("@setting.style" to "显示"),
        ))
        assertEquals("Toon als", plugin.label(s, "nl"))
        assertEquals("Show as", plugin.label(s, "de"))
        assertEquals("显示", plugin.label(s, "zh-cn"))
        assertEquals("Hulp", plugin.help(s, "nl"))
        assertEquals("Stroom", plugin.optionLabel(s, "flow", "nl"))
        assertEquals("Gauge", plugin.optionLabel(s, "gauge", "nl"))
        assertEquals("value", plugin.optionLabel(s, "value", "nl"))
        assertEquals("Voor X", plugin.presetLabel(FormPreset("x", "For X", JsonObject(emptyMap())), "nl"))
        assertEquals("192.168.1.50", plugin.placeholder(p1.setting("host")!!, "nl"))
    }

    @Test
    fun newInstance() {
        val ev = real("kinframe.ha-ev")
        val taken = listOf("p-aaaaaaaaaa")
        val i = PluginSettings.newInstance(ev, taken, Random(1))
        assertTrue(i.instanceId, Regex("^p-[a-z0-9]{10}$").matches(i.instanceId))
        assertFalse(i.enabled)
        assertEquals(Slots.TOP_RIGHT, i.slot)
        assertEquals(ev.defaultVisibility, i.visibility)
        assertEquals("1.0.0", i.version)
        val onChange = ev.copy(defaultVisibility = Visibility.ON_CHANGE, slots = listOf(Slots.TOP_LEFT))
        val j = PluginSettings.newInstance(onChange)
        assertEquals(Visibility.ON_CHANGE, j.visibility)
        assertEquals(Slots.TOP_LEFT, j.slot)
        val read = FormPlugin.read(CatalogPlugin(obj("""{"id":"a.b","name":"X","default_visibility":"on_tap"}""")))!!
        assertEquals(Visibility.ON_TAP, read.defaultVisibility)
        assertNull(FormPlugin.read(CatalogPlugin(obj("""{"name":"no id"}"""))))
    }

    @Test
    fun defaultsAndEmptyFieldsLeftOut() {
        val p1 = real("kinframe.homewizard-p1")
        var d = PluginSettings.newInstance(p1)
        val max = p1.setting("max_w")!!
        assertEquals(JsonPrimitive(4000), PluginSettings.displayValue(max, d))
        d = PluginSettings.withValue(d, "max_w", PluginSettings.parseText(max, "5000"))
        assertEquals(JsonPrimitive(5000L), d.settings["max_w"])
        d = PluginSettings.withValue(d, "max_w", PluginSettings.parseText(max, "  "))
        assertFalse("max_w" in d.settings)
        assertEquals(JsonPrimitive(2.5), PluginSettings.parseText(max, "2,5"))
        d = PluginSettings.withValue(d, "host", PluginSettings.parseText(p1.setting("host")!!, " 192.168.1.50 "))
        assertEquals(JsonPrimitive("192.168.1.50"), d.settings["host"])
        d = PluginSettings.withValue(d, "style", JsonPrimitive(""))
        assertFalse("style" in d.settings)
        assertEquals(obj("""{"host":"192.168.1.50"}"""), d.toJson()["settings"])
    }

    @Test
    fun requiredCheckLikeTheServer() {
        val energy = real("kinframe.ha-energy")
        val off = PluginSettings.newInstance(energy)
        assertTrue(PluginSettings.check(energy, off).isEmpty())
        val on = off.copy(enabled = true)
        assertEquals(
            listOf(InstanceFieldError("base_url", "settings", "required"), InstanceFieldError("token", "secrets", "required"), InstanceFieldError("grid", "settings", "required")),
            PluginSettings.check(energy, on),
        )
        assertFalse(PluginSettings.complete(energy, off))
        var d = on.copy(settings = obj("""{"base_url":"http://ha.local:8123","grid":"sensor.grid"}"""), secretWrites = mapOf("token" to "abc"))
        assertTrue(PluginSettings.check(energy, d).isEmpty())
        // A secret set on the server counts; removing it doesn't.
        d = d.copy(secretWrites = emptyMap(), secretsSet = setOf("token"))
        assertTrue(PluginSettings.complete(energy, d))
        assertEquals(listOf("token"), PluginSettings.check(energy, d.copy(secretWrites = mapOf("token" to null))).map { it.key })
        assertEquals(listOf("token"), PluginSettings.check(energy, d.copy(secretWrites = mapOf("token" to ""))).map { it.key })
        // Blank text and an empty list are empty.
        assertEquals(listOf("grid"), PluginSettings.check(energy, d.copy(settings = obj("""{"base_url":"x","grid":"  "}"""))).map { it.key })
        // A required setting with a default needs nothing.
        val withDefault = energy.copy(settings = energy.settings.map { if (it.key == "grid") it.copy(default = JsonPrimitive("sensor.x")) else it })
        assertTrue(PluginSettings.complete(withDefault, d.copy(settings = obj("""{"base_url":"x"}"""))))
        // A number field holding text.
        val p1 = real("kinframe.homewizard-p1")
        val bad = PluginSettings.newInstance(p1).copy(settings = obj("""{"max_w":"lots"}"""))
        assertEquals(listOf(InstanceFieldError("max_w", "settings", "type")), PluginSettings.check(p1, bad))
    }

    @Test
    fun presetsFromHaEv() {
        val ev = real("kinframe.ha-ev")
        val p = ev.presets.single()
        assertEquals("kia_hyundai", p.id)
        assertEquals(listOf("prefix"), p.inputs)
        assertNull(ev.applyPreset("kia_hyundai", obj("""{"base_url":"http://ha"}""")))
        assertNull(ev.applyPreset("nope", obj("""{"prefix":"ev3"}""")))
        val out = ev.applyPreset("kia_hyundai", obj("""{"prefix":" ev3 ","battery_level":"sensor.old","name":"EV3"}"""))!!
        assertEquals(JsonPrimitive("sensor.ev3_ev_battery_level"), out["battery_level"])
        assertEquals(JsonPrimitive("binary_sensor.ev3_ev_battery_plug"), out["plugged"])
        assertEquals(JsonPrimitive("EV3"), out["name"])
        val multi = FormPlugin("a.b", "X", presets = listOf(FormPreset("x", "X", obj("""{"solar":["sensor.{p}_a","sensor.{p}_b"],"n":3}"""))))
        val m = multi.applyPreset("x", obj("""{"p":"roof"}"""))!!
        assertEquals(JsonArray(listOf(JsonPrimitive("sensor.roof_a"), JsonPrimitive("sensor.roof_b"))), m["solar"])
        assertEquals(JsonPrimitive(3), m["n"])
    }

    @Test
    fun entityFilter() {
        val all = listOf(
            DiscoveredEntity("sensor.solar_power", "Solar power", "W", "power"),
            DiscoveredEntity("sensor.grid_power", "Grid", "W"),
            DiscoveredEntity("sensor.battery_soc", "Battery", "%"),
            DiscoveredEntity("binary_sensor.ev_plug", "Car plugged in"),
        )
        val energy = real("kinframe.ha-energy")
        assertEquals(listOf("sensor.solar_power", "sensor.grid_power"), FormPlugin.entities(all, energy.setting("grid")!!).map { it.entityId })
        assertEquals(listOf("sensor.battery_soc"), FormPlugin.entities(all, energy.setting("battery_soc")!!).map { it.entityId })
        assertEquals(listOf("sensor.solar_power"), FormPlugin.entities(all, energy.setting("grid")!!, "SOLAR").map { it.entityId })
        assertEquals(listOf("sensor.grid_power"), FormPlugin.entities(all, energy.setting("grid")!!, "grid").map { it.entityId })
        assertEquals(3, FormPlugin.entities(all, energy.setting("home")!!).size)
        assertEquals(listOf("binary_sensor.ev_plug"), FormPlugin.entities(all, real("kinframe.ha-ev").setting("plugged")!!).map { it.entityId })
        val solar = energy.setting("solar")!!
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), PluginSettings.entityValue(solar, listOf("a", "b", "a")))
        assertEquals(JsonPrimitive("b"), PluginSettings.entityValue(energy.setting("grid")!!, listOf("a", "b")))
        assertEquals(listOf("a", "b"), PluginSettings.entityIds(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
    }

    @Test
    fun patchSendsTheWholeListWithChangedSecretsOnly() {
        val settings = obj("""{"version":4,"plugins":{"layout":{"mode":"band","future":1},"instances":[
            {"instance_id":"p-one","plugin_id":"kinframe.ha-energy","slot":"top-left","order":2,"enabled":true,
             "settings":{"base_url":"http://ha"},"secrets_set":["token"]},
            {"instance_id":"p-two","plugin_id":"kinframe.homewizard-p1","settings":{}}]}}""")
        assertEquals(PluginLayout(mode = "band"), PluginSettings.layout(settings))
        assertEquals(PluginLayout(), PluginSettings.layout(obj("{}")))
        val list = PluginSettings.instances(settings)
        assertEquals(listOf("p-one", "p-two"), list.map { it.instanceId })
        assertEquals(setOf("token"), list[0].secretsSet)
        assertTrue(list[1].enabled)
        val edited = listOf(list[0], list[1].copy(secretWrites = mapOf("key" to "s3cret", "old" to null)))
        val patch = PluginSettings.instancesPatch(edited)
        val sent = patch["plugins"]!!.jsonObject["instances"] as JsonArray
        assertEquals(2, sent.size)
        val one = sent[0].jsonObject
        assertFalse("secrets_set" in one)
        assertFalse("secrets" in one)
        assertEquals(JsonPrimitive(2), one["order"])
        assertEquals(JsonPrimitive("top-left"), one["slot"])
        assertEquals(obj("""{"key":"s3cret","old":null}"""), sent[1].jsonObject["secrets"])
        assertEquals(obj("""{"plugins":{"layout":{"side":"left"}}}"""), PluginSettings.layoutPatch("side", "left"))
    }

    @Test
    fun fieldErrorsMapBackToFields() {
        val sent = listOf(PluginInstanceDraft("p-one", "a.b"), PluginInstanceDraft("p-two", "a.c"))
        val problem = KinJson.decodeFromString(Problem.serializer(), """{"type":"x","title":"invalid settings","status":422,"code":"validation_failed",
            "errors":[{"field":"plugins.instances.1.settings.grid","code":"required"},{"field":"plugins.instances.1.secrets.token","code":"required"},
            {"field":"plugins.instances.0.slot","code":"enum"},{"field":"plugins.instances.7.settings.x","code":"unknown"},{"field":"display.brightness","code":"range"}]}""")
        val errors = PluginSettings.fieldErrors(problem.errors, sent)
        assertEquals(listOf(InstanceFieldError(null, "slot", "enum")), errors["p-one"])
        assertEquals(listOf(InstanceFieldError("grid", "settings", "required"), InstanceFieldError("token", "secrets", "required")), errors["p-two"])
        assertEquals(2, errors.size)
        assertTrue(PluginSettings.fieldErrors(listOf(FieldError("plugins.instances.0.settings.a.b", "type")), sent)["p-one"]!!.single().key == "a.b")
    }

    @Test
    fun statusLine() {
        val d = PluginInstanceDraft("p-one", "a.b")
        val report = FramePlugins(catalogAt = "2026-10-27T10:00:00Z", instances = listOf(PluginInstanceStatus("p-one", running = true)))
        assertEquals(InstanceStatus.Waiting, PluginSettings.status(d, null, true))
        assertEquals(InstanceStatus.Running, PluginSettings.status(d, report, true))
        assertEquals(InstanceStatus.Off, PluginSettings.status(d.copy(enabled = false), report, true))
        assertEquals(InstanceStatus.NotOnFrame, PluginSettings.status(d, report, false))
        assertEquals(InstanceStatus.Waiting, PluginSettings.status(d.copy(instanceId = "p-two"), report, true))
        val failed = report.copy(instances = listOf(PluginInstanceStatus("p-one", running = true, error = "Home Assistant refused the token")))
        assertEquals(InstanceStatus.Failed("Home Assistant refused the token"), PluginSettings.status(d, failed, true))
    }

    @Test
    fun parsesTheEndpoint() {
        val r = KinJson.decodeFromString(FramePlugins.serializer(), """{"catalog":[{"manifest":{"id":"a.b","name":"X"},"locales":{"nl":{"@name":"Y"}},"builtin":true}],
            "catalog_at":null,"instances":[{"instance_id":"p-1","running":true,"error":null,"entities":[{"entity_id":"sensor.a","name":"A","unit":"W","device_class":"power"}]}],
            "services":{"_hwenergy._tcp":[{"name":"P1 meter","url":"http://192.168.1.20"}]},"status_at":"2026-10-27T10:00:00Z"}""")
        assertNull(r.catalogAt)
        assertEquals("Y", FormPlugin.read(r.catalog.single())!!.name("nl"))
        assertEquals("power", r.instances.single().entities!!.single().deviceClass)
        assertEquals("http://192.168.1.20", r.services["_hwenergy._tcp"]!!.single().url)
    }
}
