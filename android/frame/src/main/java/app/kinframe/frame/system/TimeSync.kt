package app.kinframe.frame.system

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Keeps the system clock right (product spec, "Clock"). Frames like the Denver have no clock battery: after a restart the clock
 * starts in the past (2021 on the Denver) until Android's own time sync runs, and meanwhile every TLS connection fails
 * ("certificate not valid until …"), so the server's `hello.server_time` ([TrustedClock]) can't help. This asks NTP
 * servers directly (plain UDP, no certificates) at start, when the network comes back, every 6 hours, and every 30
 * seconds while the clock is clearly wrong, and sets the system clock with root when it is more than 2 seconds off.
 * Without root it only reports the clock as wrong ([clockWrong]), so the frame says so instead of "server unreachable".
 */
open class TimeSync(
    private val root: RootShell,
    private val prefs: SharedPreferences,
    private val ntp: Ntp = Ntp(),
    private val servers: List<String> = SERVERS,
    private val wallMs: () -> Long = System::currentTimeMillis,
) {
    private val _clockWrong = MutableStateFlow(false)
    /** The clock is before the last time the frame knew it was right (or before [FLOOR_MS]): it was reset. */
    val clockWrong: StateFlow<Boolean> = _clockWrong
    private val lock = Mutex()

    /** The last time a sync confirmed the clock, or the floor; a clock before it is wrong. */
    private fun lastGoodMs(): Long = maxOf(FLOOR_MS, prefs.getLong(KEY_LAST_GOOD, 0))

    fun checkPlausible(): Boolean = (wallMs() >= lastGoodMs()).also { _clockWrong.value = !it }

    enum class Outcome { InSync, Corrected, NoRoot, NoAnswer, Failed }

    /** One round: ask the servers, set the clock if it is off. Never throws. */
    suspend fun sync(): Outcome = lock.withLock {
        checkPlausible()
        val offset = agreedOffset() ?: return@withLock Outcome.NoAnswer.also { Log.i(TAG, "no NTP answer") }
        val outcome = when {
            abs(offset) <= MAX_DRIFT_MS -> Outcome.InSync
            !root.isAvailable() -> Outcome.NoRoot
            setClock(offset) -> Outcome.Corrected
            else -> Outcome.Failed
        }
        Log.i(TAG, "clock off by ${offset} ms: $outcome")
        if (outcome == Outcome.InSync || outcome == Outcome.Corrected) prefs.edit().putLong(KEY_LAST_GOOD, wallMs()).apply()
        checkPlausible()
        outcome
    }

    /**
     * The clock's offset from NTP time. A small offset may come from one server; a large one (a reset clock, more
     * than a minute) needs a second server to agree within a second, so one bad answer can't move the clock far.
     */
    private suspend fun agreedOffset(): Long? {
        var first: Long? = null
        for (host in servers) {
            val o = ntp.offset(host) ?: continue
            val f = first
            when {
                f == null && abs(o) <= LARGE_MS -> return o
                f == null -> first = o
                abs(o - f) <= AGREE_MS -> return (o + f) / 2
                else -> first = o // they disagree: try the next pair
            }
        }
        return null
    }

    /** Sets the clock to now + [offsetMs] at the next whole second, with toybox `date -u MMDDhhmmCCYY.ss`. */
    private suspend fun setClock(offsetMs: Long): Boolean {
        val trueNow = wallMs() + offsetMs
        val next = (trueNow / 1000 + 1) * 1000
        delay(next - trueNow)
        val stamp = DATE_FORMAT.format(Instant.ofEpochMilli(next))
        val r = root.run("date -u $stamp", timeoutMs = 5_000)
        return r.exitCode == 0
    }

    /** Runs [sync] at start, on [onNetwork], every 6 hours, and every 30 seconds while the clock is wrong. */
    fun start(scope: CoroutineScope, onNetwork: (() -> Unit) -> Unit) {
        scope.launch {
            while (true) {
                val outcome = sync()
                delay(
                    when {
                        clockWrong.value -> RETRY_MS
                        outcome == Outcome.InSync || outcome == Outcome.Corrected -> EVERY_MS
                        else -> 10 * 60_000L // no answer, or off without root: try again later
                    },
                )
            }
        }
        onNetwork { scope.launch { sync() } }
    }

    /** A minimal SNTP client (RFC 4330): one request, the offset from the four timestamps. */
    open class Ntp(private val timeoutMs: Int = 3_000) {
        open suspend fun offset(host: String): Long? = withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val address = InetAddress.getByName(host)
                    val buf = ByteArray(48)
                    buf[0] = 0x23 // LI 0, version 4, mode 3 (client)
                    val t1 = System.currentTimeMillis()
                    val e1 = SystemClock.elapsedRealtime()
                    writeTimestamp(buf, 40, t1)
                    socket.send(DatagramPacket(buf, buf.size, address, 123))
                    val reply = DatagramPacket(ByteArray(48), 48)
                    socket.receive(reply)
                    val t4 = t1 + (SystemClock.elapsedRealtime() - e1)
                    parse(reply.data, t1, t4)
                }
            }.getOrNull()
        }

        companion object {
            /** 1900-01-01 to 1970-01-01 in seconds. */
            private const val EPOCH_OFFSET = 2_208_988_800L

            /** The offset from a 48-byte reply to a request sent at [t1] and answered at [t4], or null if unusable. */
            fun parse(b: ByteArray, t1: Long, t4: Long): Long? {
                val leap = (b[0].toInt() shr 6) and 3
                val mode = b[0].toInt() and 7
                val stratum = b[1].toInt() and 0xff
                if (leap == 3 || mode != 4 || stratum !in 1..15) return null
                val originate = readTimestamp(b, 24)
                if (originate != t1) return null // not the answer to our request
                val t2 = readTimestamp(b, 32)
                val t3 = readTimestamp(b, 40)
                if (t3 == 0L) return null
                return ((t2 - t1) + (t3 - t4)) / 2
            }

            fun readTimestamp(b: ByteArray, at: Int): Long {
                val sec = readU32(b, at)
                val frac = readU32(b, at + 4)
                if (sec == 0L && frac == 0L) return 0
                return (sec - EPOCH_OFFSET) * 1000 + frac * 1000 / 0x1_0000_0000L
            }

            fun writeTimestamp(b: ByteArray, at: Int, ms: Long) {
                val sec = ms / 1000 + EPOCH_OFFSET
                val frac = (ms % 1000) * 0x1_0000_0000L / 1000
                for (i in 0..3) b[at + i] = (sec shr (24 - 8 * i)).toByte()
                for (i in 0..3) b[at + 4 + i] = (frac shr (24 - 8 * i)).toByte()
            }

            private fun readU32(b: ByteArray, at: Int): Long =
                ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
                    ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
        }
    }

    companion object {
        private const val TAG = "KinTime"
        val SERVERS = listOf("time.cloudflare.com", "pool.ntp.org", "time.google.com", "time.android.com")
        const val MAX_DRIFT_MS = 2_000L
        const val LARGE_MS = 60_000L
        const val AGREE_MS = 1_000L
        const val EVERY_MS = 6 * 3_600_000L
        const val RETRY_MS = 30_000L
        /** No frame runs this app before this (2026-10-01): an earlier clock was reset. */
        const val FLOOR_MS = 1_790_812_800_000L
        const val KEY_LAST_GOOD = "time_last_good_ms"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMddHHmmyyyy.ss").withZone(ZoneOffset.UTC)
    }
}
