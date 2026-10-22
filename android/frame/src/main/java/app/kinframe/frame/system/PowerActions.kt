package app.kinframe.frame.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import app.kinframe.frame.AppScreen
import app.kinframe.frame.FrameController
import app.kinframe.frame.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Restart and safe power-off (kinframe-additions.md "Power and storage safety").
 *
 * Power off: "Shutting down…" → stop playback → stop downloads and flush acks → checkpoint and close the database
 * → mark the shutdown clean → `sync` → with root, emergency remount of every file system read-only
 * (`/proc/sysrq-trigger` `s` then `u`) → full-screen "It's now safe to unplug" with a dimmed backlight.
 * The frame stays there until power is removed; the power button or the Restart action reboots it.
 */
class PowerActions(private val c: FrameController) {
    private val g = c.g
    @Volatile var remountedReadOnly = false
        private set

    private suspend fun quiesce() {
        c.slideshow.stopPlayback()
        withTimeoutOrNull(6_000) { c.session.value?.shutdown() }
        withContext(Dispatchers.IO) {
            runCatching { g.db.checkpoint() }.onFailure { Log.w(TAG, "checkpoint failed", it) }
            g.sessionMarker.markClean()
            runCatching { Runtime.getRuntime().exec(arrayOf("sync")).waitFor() }
        }
    }

    suspend fun powerOff() {
        if (c.screen.value is AppScreen.ShuttingDown || c.screen.value is AppScreen.SafeToUnplug) return
        c.setScreen(AppScreen.ShuttingDown)
        val started = System.currentTimeMillis()
        quiesce()
        var readOnly = false
        if (g.root.isAvailable()) {
            withContext(Dispatchers.IO) {
                g.root.run("sync; echo s > /proc/sysrq-trigger; sleep 1; echo u > /proc/sysrq-trigger; sleep 1; sync", timeoutMs = 15_000)
                // Verify: /data must now be mounted read-only.
                readOnly = g.root.run("grep ' /data ' /proc/mounts").output.contains(" ro,")
            }
        }
        remountedReadOnly = readOnly
        // Let "Shutting down…" be readable rather than flashing by.
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < 2_500) delay(2_500 - elapsed)
        c.display.dimForSafeToUnplug()
        c.setScreen(AppScreen.SafeToUnplug(readOnly))
    }

    /** Reboots with root, otherwise restarts the app process. */
    suspend fun restart() {
        val fromSafeScreen = c.screen.value is AppScreen.SafeToUnplug
        c.setScreen(AppScreen.Restarting)
        if (!fromSafeScreen) quiesce()
        if (g.root.isAvailable()) {
            withContext(Dispatchers.IO) {
                if (remountedReadOnly) {
                    // File systems are already synced and read-only: an immediate reboot is safe.
                    g.root.run("echo b > /proc/sysrq-trigger", timeoutMs = 5_000)
                } else {
                    g.root.run("sync; reboot", timeoutMs = 15_000)
                }
            }
            delay(5_000)
        }
        relaunchApp(g.app)
    }

    companion object {
        private const val TAG = "KinPower"

        fun relaunchApp(context: Context) {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 800, pi)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}
