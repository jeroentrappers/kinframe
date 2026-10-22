package app.kinframe.frame.system

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The frame's notion of "now". Frames may have no NTP; the server's `hello.server_time` corrects the clock when it
 * is off by more than 2 minutes (README §6.1). We apply the correction as an offset inside the app (clock widget,
 * schedules, ack timestamps) rather than changing the OS clock.
 */
class TrustedClock {
    @Volatile var offsetMs: Long = 0
        private set
    @Volatile var trusted: Boolean = false
        private set
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs
    fun now(): Instant = Instant.ofEpochMilli(nowMs())

    fun onServerTime(serverTime: String?) {
        val server = serverTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        val diff = server - System.currentTimeMillis()
        trusted = true
        offsetMs = if (abs(diff) > MAX_DRIFT_MS) {
            Log.i("KinClock", "clock off by ${diff / 1000}s, correcting in-app")
            diff
        } else 0
    }

    companion object {
        const val MAX_DRIFT_MS = 2 * 60 * 1000L
    }
}
