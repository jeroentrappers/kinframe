package app.kinframe.frame.system

import app.kinframe.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash-loop protection (product spec 4.10). Every uncaught crash is recorded with the item on screen.
 * - 2 crashes within 5 minutes: that item is skipped from then on.
 * - 4 crashes within 5 minutes: the recovery screen is shown instead of the slideshow.
 */
class CrashGuard(dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, "crashes.json")

    @Volatile var currentItem: String? = null

    data class Crash(val at: Long, val item: String?)

    private var crashes = mutableListOf<Crash>()
    private var skipped = mutableSetOf<String>()
    /** "Try again" on the recovery screen: crashes before this don't count toward a loop. */
    private var clearedAt = 0L

    init { load() }

    @Synchronized
    private fun load() {
        val o = AtomicFile.readTextOrNull(file)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        o.optJSONArray("crashes")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { crashes += Crash(it.optLong("at"), it.optString("item").ifEmpty { null }) }
        }
        o.optJSONArray("skip")?.let { a -> for (i in 0 until a.length()) skipped += a.optString(i) }
        clearedAt = o.optLong("cleared_at", 0L)
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
            .put("crashes", JSONArray().apply { crashes.takeLast(50).forEach { put(JSONObject().put("at", it.at).put("item", it.item ?: "")) } })
            .put("skip", JSONArray(skipped.toList()))
            .put("cleared_at", clearedAt)
        runCatching { AtomicFile.writeText(file, o.toString()) }
    }

    /** Called from the uncaught-exception handler; must be quick and synchronous. */
    @Synchronized
    fun recordCrash() {
        val c = Crash(now(), currentItem)
        crashes += c
        val recent = recentCrashes()
        if (recent.size >= SKIP_AFTER) recent.mapNotNull { it.item }.lastOrNull()?.let { skipped += it }
        save()
    }

    @Synchronized
    fun recentCrashes(): List<Crash> = crashes.filter { now() - it.at in 0..WINDOW_MS && it.at > clearedAt }

    @Synchronized
    fun crashCount24h(): Int = crashes.count { now() - it.at in 0..24 * 3600_000L }

    val inCrashLoop: Boolean get() = recentCrashes().size >= RECOVERY_AFTER

    @Synchronized
    fun skippedItems(): Set<String> = skipped.toSet()

    /** "Try again" on the recovery screen. */
    @Synchronized
    fun clearLoop() {
        clearedAt = now()
        save()
    }

    @Synchronized
    fun clearSkipped() {
        skipped.clear(); save()
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        const val SKIP_AFTER = 2
        const val RECOVERY_AFTER = 4
    }
}
