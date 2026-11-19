package app.kinframe.frame.system

import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The frame's notion of "now". Frames may have no NTP; the server's `hello.server_time` corrects the clock when it
 * is off by more than 2 minutes (README §6.1). We apply the correction inside the app (clock widget, schedules, ack
 * timestamps) rather than changing the OS clock.
 *
 * The server time is kept as an anchor on the monotonic clock ([elapsedMs], which a change of the OS clock doesn't
 * move), and the drift is worked out again on every read. A frame without a backup battery starts at its factory
 * date; when NTP fixes the OS clock after the server's hello, the correction then disappears on its own instead of
 * being added on top of the now-correct OS clock.
 */
class TrustedClock(
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Server time and monotonic time at the last `hello`, or null before the first one. */
    @Volatile private var anchor: Pair<Long, Long>? = null
    @Volatile private var lastLogged: Boolean = false

    @Volatile var trusted: Boolean = false
        private set
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    /** What to add to the OS clock: the server's view of now minus the OS clock, or 0 when within [MAX_DRIFT_MS]. */
    val offsetMs: Long
        get() {
            val (server, at) = anchor ?: return 0
            val diff = server + (elapsedMs() - at) - wallMs()
            val off = abs(diff) > MAX_DRIFT_MS
            if (off != lastLogged) {
                lastLogged = off
                Log.i("KinClock", if (off) "clock off by ${diff / 1000}s, correcting in-app" else "clock back in step with the server")
            }
            return if (off) diff else 0
        }

    fun nowMs(): Long = wallMs() + offsetMs
    fun now(): Instant = Instant.ofEpochMilli(nowMs())

    fun onServerTime(serverTime: String?) {
        val server = serverTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        anchor = server to elapsedMs()
        trusted = true
    }

    companion object {
        const val MAX_DRIFT_MS = 2 * 60 * 1000L
    }
}
