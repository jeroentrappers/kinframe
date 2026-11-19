package app.kinframe.frame.system

import android.util.Log
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.Distribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Optional root (product spec §5 "Root use"). Detected once with `su 0 id`. Used only where it adds value:
 * true screen-off, read-only remount on power-off, reboot. Everything degrades gracefully without it.
 *
 * The Google Play build never tries: [Distribution.rootAllowed] is false there, so [isAvailable] is false and [run]
 * fails without starting `su` (docs/play-frame.md).
 */
open class RootShell {
    @Volatile private var available: Boolean? = null

    open suspend fun isAvailable(): Boolean {
        if (!BuildConfig.ROOT_ALLOWED) return false
        available?.let { return it }
        val ok = run("id", timeoutMs = 4000).let { it.exitCode == 0 && it.output.contains("uid=0") }
        available = ok
        Log.i(TAG, "root available: $ok")
        return ok
    }

    /** Cached result; false until [isAvailable] ran. */
    val known: Boolean get() = available == true

    data class Result(val exitCode: Int, val output: String)

    /**
     * Runs [command] as root by piping it into `su 0 sh`. Never throws.
     *
     * Not `su 0 sh -c <command>`: the su on Rockchip frame firmware re-parses its arguments, so quoting, redirects,
     * subshells and `&` break (a backgrounded job never ran). Commands on stdin reach sh untouched.
     */
    open suspend fun run(command: String, timeoutMs: Long = 10_000): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ROOT_ALLOWED) return@withContext Result(-1, "root is not used by this build")
        try {
            val p = startSu()
            p.outputStream.bufferedWriter().use { it.write(command); it.write("\nexit\n") }
            val out = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var code: Int? = null
            while (System.nanoTime() < deadline) {
                code = runCatching { p.exitValue() }.getOrNull()
                if (code != null) break
                Thread.sleep(25)
            }
            if (code == null) {
                p.destroy()
                Result(-2, "timeout")
            } else {
                reader.join(500)
                Result(code, out.toString())
            }
        } catch (e: Exception) {
            Result(-1, e.message ?: "error")
        }
    }

    /** Starts `su 0 sh`; only ever called when [BuildConfig.ROOT_ALLOWED]. Open for tests. */
    protected open fun startSu(): Process = ProcessBuilder("su", "0", "sh").redirectErrorStream(true).start()

    companion object {
        private const val TAG = "KinRoot"
    }
}
