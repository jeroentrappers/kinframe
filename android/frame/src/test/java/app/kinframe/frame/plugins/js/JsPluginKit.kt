package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.PluginContext
import app.kinframe.frame.plugins.PluginStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.fail
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Runs a plugin from the repository's `plugins/` folder (or an inline bundle) in the real QuickJS sandbox on the JVM,
 * the way the frame does, with loopback counted as "home" so MockWebServer can stand in for a device.
 */
class JsPluginKit(
    val bundle: PluginBundle,
    var settings: JsonObject,
    val language: String = "nl",
    val limits: SandboxLimits = SandboxLimits(),
    val policy: LocalPolicy = LoopbackIsHome,
) {
    val store = HashMap<String, String>()
    val logs = ArrayList<String>()
    var now: Long = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli()
    var invalidations = 0
    val format = PluginFormat(Locale.forLanguageTag(language), ZoneId.of("Europe/Brussels"))

    val ctx: PluginContext by lazy {
        PluginContext(
            instanceId = "test", settings = settings, state = HashMap(),
            store = object : PluginStore {
                override fun get(key: String) = store[key]
                override fun put(key: String, value: String?) {
                    val total = store.filterKeys { it != key }.entries.sumOf { it.key.length + it.value.length }
                    require(value == null || total + key.length + value.length <= PluginStore.MAX_BYTES) { "store full" }
                    if (value == null) store.remove(key) else store[key] = value
                }
            },
            http = app.kinframe.frame.plugins.PluginHttp(OkHttpClient(), { false }, localOnly = true),
            format = { format }, t = { it }, log = { logs += it }, now = { now }, zone = { ZoneId.of("Europe/Brussels") },
            invalidate = { invalidations++ },
        )
    }

    val instance: QuickJsPluginInstance by lazy {
        QuickJsPluginInstance(bundle, PRELUDE, PluginNet(OkHttpClient(), PluginPermissions(bundle.manifest, settings), policy), limits, JvmThreadCpu, LIBRARIES)
    }

    /** What the host does on its timer: `update`, then `render`. */
    suspend fun render(): JsonElement? { instance.update(ctx); return instance.render(ctx) }
    /** What the host does after `invalidate()`: `render` alone, from what the plugin already has. */
    suspend fun renderOnly(): JsonElement? = instance.render(ctx)
    suspend fun view(): PluginView = parse(render() ?: error("render returned null"))
    suspend fun detail(): PluginView = parse(instance.detail(ctx) ?: error("detail returned null"))

    fun close() = instance.close()

    companion object {
        /** The JVM's own per-thread CPU time, standing in for `/proc/self/task/<tid>/schedstat`. */
        val JvmThreadCpu = object : ThreadCpu {
            private val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            override fun current(): Long = Thread.currentThread().id
            override fun nanos(thread: Long): Long = bean.getThreadCpuTime(thread)
        }

        val ROOT = File("../..")
        val PRELUDE: String = File("src/main/assets/plugin-host/prelude.js").readText()
        val LIBRARIES = PluginLibraries.fromDirectory(File("src/main/assets/plugin-host/lib"))

        val LoopbackIsHome = LocalPolicy(Dns.SYSTEM) { host -> if (host == "127.0.0.1" || host == "localhost") true else app.kinframe.frame.plugins.LocalAddress.literalIsPrivate(host) }

        fun plugin(id: String): PluginBundle = PluginBundle.fromDirectory(ROOT.resolve("plugins/$id"))

        fun inline(js: String, manifest: String = MANIFEST): PluginBundle = PluginBundle(PluginManifest.parse(manifest), js, emptyMap())

        const val MANIFEST = """{"id":"community.test","name":"Test","version":"1.0.0","api":1,"refresh":{"min_seconds":5,"default_seconds":10},
            "permissions":{"http":["settings.base_url"],"websocket":["settings.base_url"],"actions":true},
            "settings":[{"key":"base_url","type":"url","label":"URL","local_only":true},{"key":"n","type":"number","default":7}]}"""

        fun parse(j: JsonElement): PluginView = when (val r = PluginViewParser.parse(j)) {
            is ViewResult.Valid -> r.view
            is ViewResult.Invalid -> { fail("invalid view: $r"); throw AssertionError() }
        }
    }
}
