package app.kinframe.frame.plugins

import app.kinframe.core.plugin.PluginFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * One running widget, the Kotlin shape of a plugin's `main.js` (docs/plugins.md §3.2). Everything returns **view
 * JSON**, exactly what a JavaScript plugin returns, and the host validates it with
 * [app.kinframe.core.plugin.PluginViewParser]. Step 2 (the QuickJS host) implements this interface by calling into
 * the script, so the built-in HomeWizard widget can be swapped for the plugin without touching the layout.
 */
interface PluginInstance {
    /** `manifest.refresh.default_seconds`. */
    val refreshSeconds: Int

    /** Called once when the instance starts or its settings change. The first view, or null while loading. */
    suspend fun start(ctx: PluginContext): JsonElement? = null

    /** Called every [refreshSeconds] (and after [PluginContext.invalidate]). Throwing counts as a failure. */
    suspend fun render(ctx: PluginContext): JsonElement?

    /** The detail card on tap, or null if the plugin has none. */
    suspend fun detail(ctx: PluginContext): JsonElement? = null

    /** Called before the instance is stopped. */
    suspend fun stop(ctx: PluginContext) {}
}

/** `ctx`: the plugin's whole world (docs/plugins.md §3.2). */
class PluginContext(
    val instanceId: String,
    val settings: JsonObject,
    /** `ctx.state`: kept between calls, lost on restart. */
    val state: MutableMap<String, Any?>,
    val store: PluginStore,
    val http: PluginHttp,
    /** `ctx.format`, created for the frame's current language and clock style. */
    val format: () -> PluginFormat,
    /** `ctx.t(key)`: the plugin's own translated strings. */
    val t: (String) -> String,
    val log: (String) -> Unit,
    val now: () -> Long,
    val zone: () -> ZoneId,
    val invalidate: () -> Unit,
)

class PluginHttpException(val status: Int, message: String) : IOException(message)

/**
 * `ctx.http`: GET only for now, with the host's rules applied before a socket opens: [allowed] checks the URL against
 * the manifest permissions, local-only requests resolve through [LocalAddress.dns], no redirects are followed, a 10 s
 * timeout and a 1 MB response limit.
 */
class PluginHttp(base: OkHttpClient, private val allowed: (okhttp3.HttpUrl) -> Boolean, localOnly: Boolean) {
    private val client = base.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .apply { if (localOnly) dns(LocalAddress.dns) }
        .build()

    suspend fun get(url: okhttp3.HttpUrl): String = withContext(Dispatchers.IO) {
        if (!allowed(url)) throw SecurityException("not allowed: ${url.host}")
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw PluginHttpException(r.code, "HTTP ${r.code}")
            val body = r.body
            val len = body.contentLength()
            if (len > MAX_BYTES) throw IOException("response too large")
            val source = body.source()
            source.request(MAX_BYTES + 1L)
            if (source.buffer.size > MAX_BYTES) throw IOException("response too large")
            source.buffer.readUtf8()
        }
    }

    companion object { const val MAX_BYTES = 1024 * 1024 }
}
