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

    /**
     * How old the *data* may be before the view is greyed, for sources that report when they measured
     * ([PluginContext.dataTime]); null = only the host's rule (no good view for 3× the refresh interval).
     */
    val staleAfterSeconds: Int? get() = null

    /** Called once when the instance starts or its settings change. The first view, or null while loading. */
    suspend fun start(ctx: PluginContext): JsonElement? = null

    /**
     * Fetches what [render] shows, every [refreshSeconds], right before [render]. The only call that waits for the
     * network on a timer, so drawing never does. Throwing counts as a failure (and skips that [render]).
     */
    suspend fun update(ctx: PluginContext) {}

    /** Called after every [update] and after [PluginContext.invalidate]. Throwing counts as a failure. */
    suspend fun render(ctx: PluginContext): JsonElement?

    /** The detail card on tap, or null if the plugin has none. */
    suspend fun detail(ctx: PluginContext): JsonElement? = null

    /**
     * The owner tapped a button the plugin drew (an `action` in its view, docs/plugins.md §4.2). Returns a fresh view
     * to show at once (the host still calls [render] afterwards), or null.
     */
    suspend fun action(ctx: PluginContext, id: String): JsonElement? = null

    /** Called before the instance is stopped. */
    suspend fun stop(ctx: PluginContext) {}

    /** Whether its views may contain `actions` (the manifest's `permissions.actions`). */
    val allowsActions: Boolean get() = false

    /** The bytes of an `image` view's [src], fetched with the plugin's own network permissions; null if it has none. */
    suspend fun image(ctx: PluginContext, src: String): ByteArray? = null
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
    /**
     * The frame's place from its weather settings (latitude, longitude), or null when unset. The host gives it only to
     * plugins with `permissions.location`, rounded to about a kilometre ([PluginLocation.coarse]).
     */
    val location: () -> Pair<Double, Double>? = { null },
) {
    /**
     * `ctx.dataTime`: when the source measured what the last view shows (epoch ms), for sources that only update every
     * few minutes (a weather station). The host greys the view once this is older than
     * [PluginInstance.staleAfterSeconds], even while fetching still works.
     */
    @Volatile var dataTime: Long? = null
}

class PluginHttpException(val status: Int, message: String) : IOException(message)

/**
 * `ctx.http`: GET only for now, with the host's rules applied before a socket opens: [allowed] checks the URL against
 * the manifest permissions, local-only requests resolve through [LocalAddress.dns], no redirects are followed, a 10 s
 * timeout and a 1 MB response limit.
 */
class PluginHttp(
    base: OkHttpClient,
    private val allowed: (okhttp3.HttpUrl) -> Boolean,
    localOnly: Boolean,
    /** Sent with every request, so a service can tell who is calling (and contact the project if it misbehaves). */
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val client = base.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .apply { if (localOnly) dns(LocalAddress.dns) }
        .build()

    /** GET [url]; [headers] may add request headers (not `Host`, `Cookie` or `Authorization`-like ones the host sets). */
    suspend fun get(url: okhttp3.HttpUrl, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        if (!allowed(url)) throw SecurityException("not allowed: ${url.host}")
        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", userAgent)
        headers.forEach { (k, v) -> if (k.lowercase() !in RESERVED) req.header(k, v) }
        client.newCall(req.build()).execute().use { r ->
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

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val DEFAULT_USER_AGENT = "kinframe-frame (+https://github.com/jeroentrappers/kinframe)"
        private val RESERVED = setOf("host", "user-agent", "cookie", "content-length", "transfer-encoding", "connection")
    }
}

/** `ctx.location`: coarse on purpose, two decimals (about 1 km), which is all a forecast or air quality needs. */
object PluginLocation {
    fun coarse(lat: Double, lon: Double): Pair<Double, Double> = round2(lat) to round2(lon)
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
