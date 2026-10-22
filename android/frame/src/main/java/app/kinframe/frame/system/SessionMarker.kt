package app.kinframe.frame.system

import android.os.SystemClock
import app.kinframe.core.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Clean-shutdown tracking (kinframe-additions.md §3). While the frame runs, the marker says "running"; a clean
 * power-off or restart writes "clean". On the next boot, "running" means the power was cut.
 *
 * A restart of only the app process (update, crash) within the same boot is not a shutdown, so the marker
 * remembers the boot id and the previous verdict is kept in that case.
 */
class SessionMarker(dir: File) {
    private val file = File(dir, "session.json")

    /** Result for this boot, computed once at startup. */
    var lastShutdownClean: Boolean = true
        private set
    /** True when this process start is the first one after an unclean power loss (run integrity checks). */
    var needsIntegrityCheck: Boolean = false
        private set

    fun onStart() {
        val bootId = bootId()
        val prev = AtomicFile.readTextOrNull(file)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (prev == null) {
            lastShutdownClean = true
        } else if (prev.optString("boot") == bootId) {
            lastShutdownClean = prev.optBoolean("last_clean", true)
        } else {
            lastShutdownClean = prev.optString("state") == "clean"
            needsIntegrityCheck = !lastShutdownClean
        }
        write("running", bootId)
    }

    fun markClean() = write("clean", bootId())

    private fun write(state: String, boot: String) {
        runCatching {
            AtomicFile.writeText(
                file,
                JSONObject().put("state", state).put("boot", boot).put("last_clean", lastShutdownClean)
                    .put("at", System.currentTimeMillis()).toString(),
            )
        }
    }

    private fun bootId(): String = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() }
        ?: ((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000).toString()
}
