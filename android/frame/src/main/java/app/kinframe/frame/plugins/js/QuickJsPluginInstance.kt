package app.kinframe.frame.plugins.js

import app.kinframe.frame.plugins.PluginContext
import app.kinframe.frame.plugins.PluginInstance
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale

/** The sandbox's limits (docs/plugins.md §5). */
data class SandboxLimits(
    val memoryBytes: Long = 8L * 1024 * 1024,
    /** JavaScript execution time per call; time spent awaiting `ctx.http` doesn't count. */
    val cpuMsPerCall: Long = 50,
    /** Compiling and running the module once at start. */
    val cpuMsAtStart: Long = 1_000,
    val stackBytes: Long = 256L * 1024,
)

/**
 * A JavaScript plugin (`main.js` of a [PluginBundle]) running in its own QuickJS runtime: one runtime per instance,
 * so instances share nothing, with a memory limit and a CPU budget per call (an endless loop is interrupted and
 * counts as a failure). [PluginHost] schedules it like any built-in: it calls [start], [render], [detail] and [stop],
 * and gets back view JSON to validate.
 *
 * Calls are serialized per instance (a WebSocket message waits for a running render). Results come back through the
 * `__kf.ret`/`__kf.fail` bindings, because evaluating a promise doesn't return its value.
 */
class QuickJsPluginInstance(
    val bundle: PluginBundle,
    private val prelude: String,
    private val net: PluginNet,
    private val limits: SandboxLimits = SandboxLimits(),
) : PluginInstance {
    private val manifest = bundle.manifest
    override val refreshSeconds: Int = manifest.refresh.defaultSeconds
    override val staleAfterSeconds: Int? = manifest.refresh.staleSeconds

    private var js: QuickJs? = null
    private val calls = Mutex()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var result: String? = null
    private var defined = false
    private var error: String? = null

    private var socket: WebSocket? = null
    private var socketUrl: String? = null
    private var socketWanted = false
    private var socketJob: Job? = null
    private val socketEvents = Channel<Pair<String, String?>>(256, BufferOverflow.DROP_OLDEST)

    private var lastRenderAt = 0L
    private var invalidatePending: Job? = null

    /** Milliseconds of JavaScript in the last finished call (for diagnostics and the performance tests). */
    @Volatile var lastCallMs: Double = 0.0
        private set

    /** Wall-clock times of recent renders (awaited I/O included), logged in batches with the heap size. */
    private val renderMs = ArrayList<Double>(RENDER_LOG_EVERY)

    override suspend fun start(ctx: PluginContext): JsonElement? {
        boot(ctx)
        return call("start", ctx)
    }

    override suspend fun render(ctx: PluginContext): JsonElement? {
        if (js == null) boot(ctx)
        lastRenderAt = ctx.now()
        return call("render", ctx)
    }

    override suspend fun detail(ctx: PluginContext): JsonElement? = if (js == null) null else call("detail", ctx)

    override suspend fun action(ctx: PluginContext, id: String): JsonElement? {
        if (!manifest.permissions.actions) throw SecurityException("${manifest.id} has no actions permission")
        if (js == null) return null
        return call("action", ctx, Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(id)))
    }

    override val allowsActions: Boolean get() = manifest.permissions.actions

    override suspend fun image(ctx: PluginContext, src: String): ByteArray = net.bytes(src, emptyMap(), MAX_IMAGE_BYTES)

    override suspend fun stop(ctx: PluginContext) {
        try {
            if (js != null) call("stop", ctx)
        } finally {
            close()
        }
    }

    fun close() {
        socketWanted = false
        socket?.cancel(); socket = null
        scope.cancel()
        js?.close(); js = null
    }

    private suspend fun boot(ctx: PluginContext) {
        close()
        val t0 = System.nanoTime()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val q = QuickJs.create(Dispatchers.IO)
        q.memoryLimit = limits.memoryBytes
        q.maxStackSize = limits.stackBytes
        q.evaluationTimeoutMillis = limits.cpuMsAtStart
        val settingsJson = manifest.withDefaults(ctx.settings).toString()
        q.define("__kf") {
            function("settings") { _ -> settingsJson }
            function("log") { a -> ctx.log(a.firstOrNull()?.toString().orEmpty().take(2_000)) }
            function("now") { _ -> ctx.now().toDouble() }
            function("zone") { _ -> ctx.zone().id }
            function("local_time") { a -> localTime(ctx, (a[0] as Number).toLong()) }
            function("language") { _ -> ctx.format().locale.toLanguageTag() }
            function("t") { a -> translate(ctx, a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1) as? String) }
            function("fmt") { a -> format(ctx, a) }
            function("store_get") { a -> ctx.store.get(a[0].toString()) }
            function("store_put") { a -> ctx.store.put(a[0].toString(), a.getOrNull(1) as? String); null }
            function("data_time") { a -> ctx.dataTime = (a[0] as Number).toLong().takeIf { it >= 0 }; null }
            function("invalidate") { _ -> invalidate(ctx); null }
            function("ret") { a -> result = a[0] as? String; defined = a.getOrNull(1) as? Boolean ?: true; null }
            function("fail") { a -> error = a[0]?.toString() ?: "error"; null }
            function("ws_connect") { a -> connect(ctx, a[0].toString()); null }
            function("ws_send") { a -> socket?.send(a[0].toString()); null }
            function("ws_close") { _ -> socketWanted = false; socket?.close(1000, null); socket = null; null }
            asyncFunction("http") { a -> http(a) }
        }
        js = q
        q.evaluate<Any?>(prelude, "prelude.js")
        q.addModule("main", bundle.mainJs)
        q.evaluate<Any?>("import plugin from 'main'; globalThis.__plugin = plugin;", "boot.js", asModule = true)
        q.evaluationTimeoutMillis = limits.cpuMsPerCall
        ctx.log(String.format(Locale.ROOT, "booted in %.1f ms, heap %d KB", (System.nanoTime() - t0) / 1e6, q.memoryUsage.memoryUsedSize / 1024))
    }

    private suspend fun call(name: String, ctx: PluginContext, arg: String? = null): JsonElement? = calls.withLock {
        val q = js ?: return@withLock null
        result = null; error = null; defined = true
        val t0 = System.nanoTime()
        try {
            q.evaluate<Any?>(if (arg == null) "__kf_call('$name')" else "__kf_call('$name', $arg)", "$name.js")
        } catch (e: QuickJsInterruptedException) {
            throw PluginScriptException("$name: took longer than ${limits.cpuMsPerCall} ms of JavaScript")
        } catch (e: com.dokar.quickjs.QuickJsException) {
            // Out of memory and the like leave the runtime in an unknown state: start over next time.
            if (e.message?.contains("out of memory") == true) close()
            throw PluginScriptException("$name: ${e.message}")
        } finally {
            lastCallMs = (System.nanoTime() - t0) / 1e6
            if (name == "render") logRender(ctx, q)
        }
        // An interrupt inside an async function arrives as a rejected promise, which __kf_call catches.
        error?.let { throw PluginScriptException(if (it == "interrupted") "$name: took longer than ${limits.cpuMsPerCall} ms of JavaScript" else "$name: $it") }
        result?.let { Json.parseToJsonElement(it) }
    }

    private fun logRender(ctx: PluginContext, q: QuickJs) {
        renderMs += lastCallMs
        if (renderMs.size < RENDER_LOG_EVERY) return
        renderMs.sort()
        val heap = runCatching { q.memoryUsage.memoryUsedSize / 1024 }.getOrDefault(-1L)
        ctx.log(String.format(Locale.ROOT, "render p50 %.1f ms, max %.1f ms over %d, heap %d KB", renderMs[renderMs.size / 2], renderMs.last(), renderMs.size, heap))
        renderMs.clear()
    }

    private suspend fun http(a: Array<Any?>): String {
        val method = a[0].toString()
        val url = a[1].toString()
        val o = (a.getOrNull(2) as? String)?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
        val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        val body = (o["body"] as? JsonPrimitive)?.contentOrNull
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull
        val r = net.request(method, url, headers, body, type)
        return buildJsonObject { put("status", r.status); put("body", r.body); put("type", r.contentType) }.toString()
    }

    /** `ctx.invalidate()`: a render now, but not more often than `refresh.min_seconds`. */
    private fun invalidate(ctx: PluginContext) {
        val wait = lastRenderAt + manifest.refresh.minSeconds * 1000L - ctx.now()
        if (wait <= 0) { ctx.invalidate(); return }
        if (invalidatePending?.isActive == true) return
        invalidatePending = scope.launch { delay(wait); ctx.invalidate() }
    }

    private fun connect(ctx: PluginContext, url: String) {
        socketWanted = true
        socketUrl = url
        socket?.cancel()
        if (socketJob?.isActive != true) socketJob = scope.launch {
            for ((type, data) in socketEvents) {
                runCatching {
                    calls.withLock {
                        val q = js ?: return@withLock
                        val arg = data?.let { Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(it)) } ?: "null"
                        q.evaluate<Any?>("__kf_ws_event('$type', $arg)", "ws.js")
                    }
                }.onFailure { ctx.log("ws $type: ${it.message}") }
            }
        }
        open(ctx, 0)
    }

    private fun open(ctx: PluginContext, attempt: Int) {
        val url = socketUrl ?: return
        socket = runCatching {
            net.openSocket(url, object : WebSocketListener() {
                @Volatile private var opened = false
                override fun onOpen(webSocket: WebSocket, response: Response) { opened = true; socketEvents.trySend("open" to null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length <= PluginNet.MAX_BYTES) socketEvents.trySend("message" to text)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed $code")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, t.message ?: "failed")
                private fun lost(ws: WebSocket, why: String) {
                    if (ws !== socket) return
                    socketEvents.trySend("close" to why)
                    // The frame reconnects with backoff and the plugin's onOpen runs again (§3.2).
                    val next = if (opened) 0 else attempt + 1
                    if (socketWanted) scope.launch { delay(reconnectMs(next)); if (socketWanted) open(ctx, next) }
                }
            })
        }.getOrElse { e -> ctx.log("ws: ${e.message}"); null }
    }

    private fun localTime(ctx: PluginContext, ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(ctx.zone())
        return buildJsonObject {
            put("date", z.toLocalDate().toString()); put("year", z.year); put("month", z.monthValue); put("day", z.dayOfMonth)
            put("hour", z.hour); put("minute", z.minute); put("weekday", z.dayOfWeek.value)
        }.toString()
    }

    private fun translate(ctx: PluginContext, key: String, varsJson: String?): String {
        var s = bundle.strings(ctx.format().locale.toLanguageTag())[key] ?: key
        val vars = varsJson?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
        vars?.forEach { (k, v) -> s = s.replace("{$k}", (v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
        return s
    }

    private fun format(ctx: PluginContext, a: Array<Any?>): String {
        val f = ctx.format()
        fun d(i: Int) = (a.getOrNull(i) as? Number)?.toDouble() ?: Double.NaN
        fun i(i: Int) = (a.getOrNull(i) as? Number)?.toInt() ?: 0
        return when (a[0]) {
            "power" -> f.power(d(1))
            "energy" -> f.energy(d(1))
            "volume" -> f.volume(d(1))
            "percent" -> f.percent(d(1))
            "temperature" -> f.temperature(d(1), decimals = i(2).coerceIn(0, 3))
            "number" -> f.number(d(1), i(2).coerceIn(0, 6), a.getOrNull(3) as? String)
            "duration" -> f.duration(d(1).toLong())
            "time" -> f.time(d(1).toLong())
            else -> throw IllegalArgumentException("format.${a[0]}")
        }
    }

    companion object {
        /** An `image` view's picture: album art and thumbnails, not photos. */
        const val MAX_IMAGE_BYTES = 768 * 1024
        const val RENDER_LOG_EVERY = 60

        fun reconnectMs(attempt: Int): Long = (2_000L shl attempt.coerceAtMost(5)).coerceAtMost(60_000L)
    }
}

class PluginScriptException(message: String) : RuntimeException(message)
