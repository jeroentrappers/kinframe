package app.kinframe.frame.plugins.js

import app.kinframe.frame.plugins.LocalAddress
import app.kinframe.frame.plugins.PluginHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Which addresses count as the home network. [LocalAddress] on the frame; tests swap in one that accepts loopback,
 * where MockWebServer listens.
 */
class LocalPolicy(val dns: Dns, val literalIsPrivate: (String) -> Boolean?) {
    companion object {
        val Frame = LocalPolicy(LocalAddress.dns, LocalAddress::literalIsPrivate)
    }
}

/**
 * The manifest's network permissions applied to the owner's settings (docs/plugins.md §3.1, §5): every entry is a
 * setting holding an address (`settings.base_url`) or a fixed https origin. A request is allowed when it goes to one of
 * those origins (same scheme, host and port, and under the setting's path). It must stay on the home network when the
 * setting is `local_only` or when it is plain `http://` (home devices rarely have TLS; the internet always does).
 */
class PluginPermissions(manifest: PluginManifest, settings: JsonObject) {
    data class Grant(val base: HttpUrl, val localOnly: Boolean)

    val http: List<Grant> = grants(manifest, manifest.permissions.http, settings)
    val websocket: List<Grant> = grants(manifest, manifest.permissions.websocket, settings)

    /** The grant that allows [url], or null. ws:// and wss:// are checked as http:// and https://. */
    fun check(url: HttpUrl, ws: Boolean = false): Grant? = (if (ws) websocket else http).firstOrNull { g ->
        g.base.scheme == url.scheme && g.base.host.equals(url.host, ignoreCase = true) && g.base.port == url.port &&
            url.encodedPath.startsWith(g.base.encodedPath.trimEnd('/'))
    }

    companion object {
        private fun grants(manifest: PluginManifest, entries: List<String>, settings: JsonObject): List<Grant> = entries.mapNotNull { e ->
            if (e.startsWith("settings.")) {
                val spec = manifest.setting(e.removePrefix("settings.")) ?: return@mapNotNull null
                val typed = (settings[spec.key] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val base = LocalAddress.baseUrl(typed) ?: return@mapNotNull null
                Grant(base, spec.localOnly)
            } else {
                e.toHttpUrlOrNull()?.let { Grant(it, false) }
            }
        }
    }
}

/** What `ctx.http` returns to the script. */
data class NetResponse(val status: Int, val body: String, val contentType: String?)

/**
 * `ctx.http` and `ctx.ws` for one instance: permission-checked, no redirects, 10 s per call, 1 MB per response, the
 * frame's User-Agent, and requests that must stay home resolve only to private addresses.
 */
class PluginNet(
    base: OkHttpClient,
    private val permissions: PluginPermissions,
    private val policy: LocalPolicy = LocalPolicy.Frame,
    private val userAgent: String = PluginHttp.DEFAULT_USER_AGENT,
) {
    private val public = base.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val local = public.newBuilder().dns(policy.dns).build()
    private val socket = local.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    private val publicSocket = public.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()

    /** Throws [SecurityException] when the manifest doesn't allow [url]; returns whether it must stay home. */
    private fun authorize(url: HttpUrl, ws: Boolean): Boolean {
        val g = permissions.check(url, ws) ?: throw SecurityException("not allowed: ${url.scheme}://${url.host}:${url.port}")
        val home = g.localOnly || url.scheme == "http"
        // OkHttp doesn't ask the Dns for IP literals, so those are judged here.
        if (home && policy.literalIsPrivate(url.host) == false) throw SecurityException("${url.host} is not on the home network")
        return home
    }

    suspend fun request(method: String, urlText: String, headers: Map<String, String>, body: String?, contentType: String?): NetResponse =
        withContext(Dispatchers.IO) {
            val url = urlText.toHttpUrlOrNull() ?: throw IllegalArgumentException("not an http(s) URL: $urlText")
            val home = authorize(url, ws = false)
            val req = Request.Builder().url(url).header("User-Agent", userAgent)
            headers.forEach { (k, v) -> if (k.lowercase() !in RESERVED) req.header(k, v) }
            when (method) {
                "GET" -> req.get()
                "POST", "PUT", "PATCH", "DELETE" -> req.method(method, (body ?: "").toRequestBody((contentType ?: "application/json").toMediaTypeOrNull()))
                else -> throw IllegalArgumentException("method $method")
            }
            (if (home) local else public).newCall(req.build()).execute().use { r -> NetResponse(r.code, readLimited(r), r.header("Content-Type")) }
        }

    /** Raw bytes (an image), same rules, at most [maxBytes]. */
    suspend fun bytes(urlText: String, headers: Map<String, String>, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val url = urlText.toHttpUrlOrNull() ?: throw IllegalArgumentException("not an http(s) URL")
        val home = authorize(url, ws = false)
        val req = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (k, v) -> if (k.lowercase() !in RESERVED) req.header(k, v) }
        (if (home) local else public).newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val source = r.body.source()
            source.request(maxBytes + 1L)
            if (source.buffer.size > maxBytes) throw IOException("image too large")
            source.buffer.readByteArray()
        }
    }

    /** Opens the instance's WebSocket; [listener] gets OkHttp's callbacks. */
    fun openSocket(urlText: String, listener: WebSocketListener): WebSocket {
        val http = urlText.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
        val url = http.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a ws(s) URL: $urlText")
        val home = authorize(url, ws = true)
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return (if (home) socket else publicSocket).newWebSocket(req, listener)
    }

    private fun readLimited(r: Response): String {
        val body = r.body
        if (body.contentLength() > MAX_BYTES) throw IOException("response too large")
        val source = body.source()
        source.request(MAX_BYTES + 1L)
        if (source.buffer.size > MAX_BYTES) throw IOException("response too large")
        return source.buffer.readUtf8()
    }

    companion object {
        const val MAX_BYTES = PluginHttp.MAX_BYTES
        private val RESERVED = setOf("host", "user-agent", "cookie", "content-length", "transfer-encoding", "connection")
    }
}
