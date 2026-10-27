package app.kinframe.frame.plugins

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.kinframe.core.plugin.NoneView
import app.kinframe.core.plugin.hasActions
import app.kinframe.core.plugin.imageSources
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
    /** Decoded pictures of the instance's `image` views, by source URL. */
    val images: Map<String, ImageBitmap> = emptyMap(),
)

/**
 * Runs plugin instances (docs/plugins.md §5): all of them on one low-priority background thread, so a plugin never
 * stalls the slideshow. Calls `update` and then `render` every refresh interval (`render` alone after `invalidate()`),
 * validates what comes back, keeps the last good view, backs off on failures, and works out per instance whether it
 * is shown (visibility mode, stale, hidden).
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
        /** Small LRU of decoded pictures; only touched on the plugin thread, published as a copy. */
        val images: LinkedHashMap<String, ImageBitmap> = LinkedHashMap(8, 0.75f, true),
        @Volatile var imagesShown: Map<String, ImageBitmap> = emptyMap(),
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
                is ViewResult.Valid -> if (permitted(r, v.view)) v.view.also { loadImages(r, it) } else null
                is ViewResult.Invalid -> { r.ctx.log("detail: $v"); null }
            }
        }.also { publish() }
    }

    /** A view with buttons from a plugin without `permissions.actions` is refused like an invalid one. */
    private fun permitted(r: Running, view: PluginView): Boolean {
        if (!view.hasActions() || r.instance.allowsActions) return true
        r.error = "actions need permissions.actions in the manifest"
        r.ctx.log(r.error!!)
        return false
    }

    /** Fetches and decodes the pictures [view] shows that aren't cached yet (on the plugin thread). */
    private suspend fun loadImages(r: Running, view: PluginView) {
        val wanted = view.imageSources()
        if (wanted.isEmpty()) return
        for (src in wanted) {
            if (r.images.containsKey(src)) { r.images[src]; continue }
            val bytes = runCatching { withTimeout(CALL_TIMEOUT_MS) { r.instance.image(r.ctx, src) } }
                .onFailure { r.ctx.log("image: ${it.message}") }.getOrNull() ?: continue
            val bmp = runCatching { decodeImage(bytes, MAX_IMAGE_PX) }.getOrNull() ?: continue
            r.images[src] = bmp
            while (r.images.size > MAX_IMAGES) r.images.remove(r.images.keys.first())
        }
        r.imagesShown = HashMap(r.images)
    }

    /**
     * The owner tapped a plugin's button (docs/plugins.md §4.2): runs its `action` handler on the plugin thread. A view
     * it returns is shown at once; either way the instance renders again right after.
     */
    suspend fun action(instanceId: String, actionId: String): PluginView? {
        val r = running[instanceId] ?: return null
        val view = withContext(thread) {
            val json = runCatching { withTimeout(CALL_TIMEOUT_MS) { r.instance.action(r.ctx, actionId) } }
                .onFailure { r.ctx.log("action $actionId failed: ${it.message}") }.getOrNull()
            json?.let { (PluginViewParser.parse(it) as? ViewResult.Valid)?.view }
        }
        if (view != null) {
            if (view != r.view) r.changedAt = now()
            r.view = view
            r.lastGoodAt = now()
        }
        r.wake.trySend(Unit)
        publish()
        return view
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
        // update() keeps its own clock: invalidate() wakes the loop for a render only, however often it comes.
        var nextFetchMs = 0L
        while (true) {
            val fetch = monoMs() >= nextFetchMs
            val json: JsonElement? = try {
                withTimeout(CALL_TIMEOUT_MS) {
                    (if (first) r.instance.start(r.ctx) else null) ?: run {
                        // On the timer: fetch, then draw. After invalidate(): draw from what the plugin has.
                        if (fetch) r.instance.update(r.ctx)
                        r.instance.render(r.ctx)
                    }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) { fail(r, "timed out"); null } else throw e
            } catch (e: Throwable) {
                fail(r, e.message ?: e.javaClass.simpleName); null
            }
            first = false
            if (json != null) when (val v = PluginViewParser.parse(json)) {
                is ViewResult.Valid -> if (permitted(r, v.view)) {
                    loadImages(r, v.view)
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
            if (fetch) nextFetchMs = monoMs() + backoffMs(refreshMs, r.failures)
            kotlinx.coroutines.withTimeoutOrNull((nextFetchMs - monoMs()).coerceAtLeast(1)) { r.wake.receive() }
        }
    }

    private fun monoMs(): Long = System.nanoTime() / 1_000_000

    private fun fail(r: Running, why: String) {
        r.failures++
        r.error = why
        r.ctx.log("failed (${r.failures}): $why")
    }

    private fun publishFromWorker() = scope.launch { publish() }

    private fun publish() {
        val t = now()
        _states.value = running.values.map { r ->
            val refreshMs = r.instance.refreshSeconds.coerceAtLeast(MIN_REFRESH_S) * 1000L
            val age = if (r.lastGoodAt == 0L) Long.MAX_VALUE else t - r.lastGoodAt
            val dataOld = dataIsOld(r.ctx.dataTime, r.instance.staleAfterSeconds, t)
            val status = when {
                r.view != null && age <= maxOf(3 * refreshMs, MIN_STALE_MS) && !dataOld -> InstanceStatus.Ok
                r.view != null -> InstanceStatus.Stale
                r.failures >= 3 -> InstanceStatus.Unavailable
                else -> InstanceStatus.Loading
            }
            val expired = when {
                r.view != null -> age > hideAfterMs(refreshMs)
                status == InstanceStatus.Unavailable -> t - r.startedAt > HIDE_AFTER_MS
                else -> true
            }
            val modeAllows = when (r.config.visibility) {
                Visibility.Always -> true
                Visibility.OnChange -> r.changedAt != 0L && t - r.changedAt < VISIBLE_MS
                Visibility.OnTap -> t < tapUntil
            }
            InstanceState(r.config, r.name, r.view, status, shown = !expired && modeAllows && r.view !is NoneView, lastGoodAt = r.lastGoodAt, error = r.error, images = r.imagesShown)
        }.sortedWith(compareBy({ it.config.slot.ordinal }, { it.config.order }))
    }

    companion object {
        const val MAX_IMAGES = 6
        /** Album art is drawn at most 168 dp (the detail card); this keeps a 2× screen sharp and memory small. */
        const val MAX_IMAGE_PX = 336
        const val VISIBLE_MS = 30_000L
        const val HIDE_AFTER_MS = 15 * 60_000L
        const val CALL_TIMEOUT_MS = 10_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MIN_REFRESH_S = 1

        /** Greyed only after this long without a good view, however often the plugin refreshes (a Wi-Fi hiccup). */
        const val MIN_STALE_MS = 15_000L
        private const val TAG = "KinFramePlugins"

        fun log(id: String, msg: String) = Log.i(TAG, "[$id] $msg")

        /**
         * The wait before the next call: the refresh interval, and after failures 2×, 4×… of it, at most a minute, or
         * at most 4× the interval for plugins that refresh less often than every 15 s (a slow internet API is never
         * asked more often after an error than when it works).
         */
        fun backoffMs(refreshMs: Long, failures: Int): Long {
            if (failures == 0) return refreshMs
            val cap = maxOf(MAX_BACKOFF_MS, refreshMs * 4)
            return (refreshMs shl (failures - 1).coerceAtMost(4)).coerceAtMost(cap)
        }

        /**
         * Decodes [bytes] no larger than about [maxPx] on the long side: subsampled while decoding (cheap on the
         * RK3126), then scaled the rest of the way.
         */
        fun decodeImage(bytes: ByteArray, maxPx: Int): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val long = maxOf(bmp.width, bmp.height)
            val out = if (long > maxPx) {
                val k = maxPx.toFloat() / long
                Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).also { if (it != bmp) bmp.recycle() }
            } else bmp
            return out.asImageBitmap()
        }

        /** A last good view older than this is hidden: 15 minutes, or longer for slow refresh intervals. */
        fun hideAfterMs(refreshMs: Long): Long = maxOf(HIDE_AFTER_MS, 3 * refreshMs + 60_000L)

        /** The source's own measurement is older than the plugin allows ([PluginInstance.staleAfterSeconds]). */
        fun dataIsOld(dataTime: Long?, staleAfterSeconds: Int?, now: Long): Boolean =
            dataTime != null && staleAfterSeconds != null && now - dataTime > staleAfterSeconds * 1000L
    }
}
