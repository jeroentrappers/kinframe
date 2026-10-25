package app.kinframe.frame.plugins

import android.os.Process
import android.util.Log
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.Executors

/** How an instance is doing, for the UI. */
enum class InstanceStatus {
    /** Started, no view yet. */
    Loading,
    Ok,
    /** The last good view is older than 3× the refresh interval: drawn greyed. */
    Stale,
    /** Three failures in a row and nothing good to show: a discreet "unavailable" mark. */
    Unavailable,
}

/** What the UI draws for one instance. [shown] already applies the visibility mode and the 15-minute limit. */
data class InstanceState(
    val config: InstanceConfig,
    val name: String,
    val view: PluginView?,
    val status: InstanceStatus,
    val shown: Boolean,
    val lastGoodAt: Long,
    val error: String?,
)

/**
 * Runs plugin instances (docs/plugins.md §5): all of them on one low-priority background thread, so a plugin never
 * stalls the slideshow. Calls `render` every refresh interval, validates what comes back, keeps the last good view,
 * backs off on failures, and works out per instance whether it is shown (visibility mode, stale, hidden).
 */
class PluginHost(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val contextFor: (InstanceConfig, invalidate: () -> Unit) -> PluginContext,
    private val factory: (InstanceConfig) -> Pair<PluginInstance, String>?,
) {
    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "kinframe-plugins").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private class Running(
        val config: InstanceConfig,
        val name: String,
        val instance: PluginInstance,
        val ctx: PluginContext,
        val job: Job,
        @Volatile var view: PluginView? = null,
        @Volatile var lastGoodAt: Long = 0L,
        @Volatile var changedAt: Long = 0L,
        var startedAt: Long = 0L,
        @Volatile var failures: Int = 0,
        @Volatile var error: String? = null,
        val wake: kotlinx.coroutines.channels.Channel<Unit> = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.CONFLATED),
    )

    private val running = LinkedHashMap<String, Running>()
    private var configs: List<InstanceConfig> = emptyList()
    private var awake = true
    private var active = false
    private var tapUntil = 0L
    private var tickJob: Job? = null

    private val _states = MutableStateFlow<List<InstanceState>>(emptyList())
    val states: StateFlow<List<InstanceState>> = _states

    /** Starts, restarts (changed settings) and stops instances to match [list]. Main thread. */
    fun apply(list: List<InstanceConfig>) {
        configs = list.filter { it.enabled }
        reconcile()
    }

    /** Instances run only while the slideshow is up and the screen is awake (they stop at night and during setup). */
    fun setActive(slideshowUp: Boolean, screenAwake: Boolean) {
        active = slideshowUp
        awake = screenAwake
        reconcile()
    }

    /** A tap on the slideshow: *on tap* instances show for [VISIBLE_MS]. */
    fun tapped() {
        tapUntil = now() + VISIBLE_MS
        publish()
    }

    /** The detail card's view, rendered on the plugin thread. */
    suspend fun detail(instanceId: String): PluginView? {
        val r = running[instanceId] ?: return null
        return withContext(thread) {
            val json = runCatching { withTimeout(CALL_TIMEOUT_MS) { r.instance.detail(r.ctx) } }.getOrNull() ?: return@withContext null
            when (val v = PluginViewParser.parse(json)) {
                is ViewResult.Valid -> v.view
                is ViewResult.Invalid -> { r.ctx.log("detail: $v"); null }
            }
        }
    }

    private fun reconcile() {
        val want = if (active && awake) configs.associateBy { it.instanceId } else emptyMap()
        // Stop what is gone or changed.
        running.values.toList().forEach { r ->
            val w = want[r.config.instanceId]
            if (w == null || w != r.config) {
                r.job.cancel()
                running.remove(r.config.instanceId)
                scope.launch(thread) { runCatching { withTimeout(CALL_TIMEOUT_MS) { r.instance.stop(r.ctx) } } }
                // Keep the last view across a night, so the morning starts with it (greyed until fresh data arrives).
                if (w == null && !(active && awake)) lastViews[r.config.instanceId] = Triple(r.view, r.lastGoodAt, r.changedAt)
            }
        }
        want.values.forEach { cfg ->
            if (cfg.instanceId in running) return@forEach
            val (instance, name) = factory(cfg) ?: return@forEach
            lateinit var r: Running
            val ctx = contextFor(cfg) { r.wake.trySend(Unit) }
            val job = scope.launch(thread, start = kotlinx.coroutines.CoroutineStart.LAZY) { loop(r) }
            r = Running(cfg, name, instance, ctx, job, startedAt = now())
            lastViews.remove(cfg.instanceId)?.let { (v, at, ch) -> r.view = v; r.lastGoodAt = at; r.changedAt = ch }
            running[cfg.instanceId] = r
            job.start()
        }
        if (running.isNotEmpty() && tickJob?.isActive != true) {
            tickJob = scope.launch { while (isActive) { publish(); delay(1_000) } }
        } else if (running.isEmpty()) {
            tickJob?.cancel(); tickJob = null
        }
        publish()
    }

    private val lastViews = HashMap<String, Triple<PluginView?, Long, Long>>()

    private suspend fun loop(r: Running) {
        val refreshMs = r.instance.refreshSeconds.coerceAtLeast(MIN_REFRESH_S) * 1000L
        var first = true
        while (true) {
            val json: JsonElement? = try {
                withTimeout(CALL_TIMEOUT_MS) { if (first) r.instance.start(r.ctx) ?: r.instance.render(r.ctx) else r.instance.render(r.ctx) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) { fail(r, "timed out"); null } else throw e
            } catch (e: Throwable) {
                fail(r, e.message ?: e.javaClass.simpleName); null
            }
            first = false
            if (json != null) when (val v = PluginViewParser.parse(json)) {
                is ViewResult.Valid -> {
                    val t = now()
                    if (v.view != r.view) r.changedAt = t
                    r.view = v.view
                    r.lastGoodAt = t
                    r.failures = 0
                    r.error = null
                }
                // Not drawn; the previous view stays and the reason is logged (and shown to the owner).
                is ViewResult.Invalid -> { r.error = v.toString(); r.ctx.log(v.toString()) }
            }
            publishFromWorker()
            // Back off after failures: 2×, 4×… the interval, at most a minute.
            val wait = if (r.failures == 0) refreshMs else (refreshMs shl (r.failures - 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
            kotlinx.coroutines.withTimeoutOrNull(wait) { r.wake.receive() }
        }
    }

    private fun fail(r: Running, why: String) {
        r.failures++
        r.error = why
        r.ctx.log("render failed (${r.failures}): $why")
    }

    private fun publishFromWorker() = scope.launch { publish() }

    private fun publish() {
        val t = now()
        _states.value = running.values.map { r ->
            val refreshMs = r.instance.refreshSeconds.coerceAtLeast(MIN_REFRESH_S) * 1000L
            val age = if (r.lastGoodAt == 0L) Long.MAX_VALUE else t - r.lastGoodAt
            val status = when {
                r.view != null && age <= 3 * refreshMs -> InstanceStatus.Ok
                r.view != null -> InstanceStatus.Stale
                r.failures >= 3 -> InstanceStatus.Unavailable
                else -> InstanceStatus.Loading
            }
            val expired = when {
                r.view != null -> age > HIDE_AFTER_MS
                status == InstanceStatus.Unavailable -> t - r.startedAt > HIDE_AFTER_MS
                else -> true
            }
            val modeAllows = when (r.config.visibility) {
                Visibility.Always -> true
                Visibility.OnChange -> r.changedAt != 0L && t - r.changedAt < VISIBLE_MS
                Visibility.OnTap -> t < tapUntil
            }
            InstanceState(r.config, r.name, r.view, status, shown = !expired && modeAllows, lastGoodAt = r.lastGoodAt, error = r.error)
        }.sortedWith(compareBy({ it.config.slot.ordinal }, { it.config.order }))
    }

    companion object {
        const val VISIBLE_MS = 30_000L
        const val HIDE_AFTER_MS = 15 * 60_000L
        const val CALL_TIMEOUT_MS = 10_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MIN_REFRESH_S = 2
        private const val TAG = "KinFramePlugins"

        fun log(id: String, msg: String) = Log.i(TAG, "[$id] $msg")
    }
}
