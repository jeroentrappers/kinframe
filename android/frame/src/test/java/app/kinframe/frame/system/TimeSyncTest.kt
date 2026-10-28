package app.kinframe.frame.system

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The frame sets its own clock from NTP (README §6.1): the Denver starts in 2021 after a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeSyncTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("time-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }

    /** 2026-10-28 10:52:41.250 UTC. */
    private val trueNow = 1_793_184_761_250L

    /** The device's clock, which a root `date -u` moves. */
    private var wall = trueNow

    private val commands = mutableListOf<String>()

    private fun root(available: Boolean) = object : RootShell() {
        override suspend fun isAvailable() = available
        override suspend fun run(command: String, timeoutMs: Long): Result {
            commands += command
            wall = trueNow + 1_000 // set at the next whole second
            return Result(0, "")
        }
    }

    /** Answers per host: the offset each server reports, null for no answer. */
    private fun ntp(vararg answers: Pair<String, Long?>) = object : TimeSync.Ntp() {
        override suspend fun offset(host: String): Long? = answers.toMap()[host]
    }

    private fun sync(root: RootShell, ntp: TimeSync.Ntp) = TimeSync(root, prefs, ntp, listOf("a", "b", "c"), wallMs = { wall })

    @Test fun aClockThatIsRightIsLeftAlone() = runTest {
        val t = sync(root(true), ntp("a" to 400L))
        assertEquals(TimeSync.Outcome.InSync, t.sync())
        assertTrue(commands.isEmpty())
        assertFalse(t.clockWrong.value)
    }

    @Test fun aResetClockIsSetOnceTwoServersAgree() = runTest {
        wall = 1_621_036_800_000L // 2021-05-15, as the Denver boots
        val off = trueNow - wall
        val t = sync(root(true), ntp("a" to off, "b" to off + 300, "c" to null))
        assertTrue(t.checkPlausible().not())
        assertTrue(t.clockWrong.value)
        assertEquals(TimeSync.Outcome.Corrected, t.sync())
        // toybox date in UTC, MMDDhhmmCCYY.ss, at the next whole second (the two answers averaged: +150 ms).
        assertEquals(listOf("date -u 102810522026.42"), commands)
        assertFalse(t.clockWrong.value)
        // Remembered: should the clock fall back to 2021 again, it is known to be wrong.
        wall = 1_621_036_800_000L
        assertFalse(t.checkPlausible())
    }

    @Test fun oneServerAloneCannotMoveTheClockFar() = runTest {
        val t = sync(root(true), ntp("a" to 5 * 3_600_000L, "b" to -2 * 3_600_000L, "c" to null))
        assertEquals(TimeSync.Outcome.NoAnswer, t.sync())
        assertTrue(commands.isEmpty())
    }

    @Test fun withoutRootTheClockIsOnlyReported() = runTest {
        wall = 1_621_036_800_000L
        val off = trueNow - wall
        val t = sync(root(false), ntp("a" to off, "b" to off))
        assertEquals(TimeSync.Outcome.NoRoot, t.sync())
        assertTrue(commands.isEmpty())
        assertTrue(t.clockWrong.value)
    }

    @Test fun ntpRepliesAreParsedAndChecked() {
        val t1 = trueNow - 5_000 // our clock is 5 s slow
        val reply = ByteArray(48)
        reply[0] = 0x24 // LI 0, version 4, mode 4 (server)
        reply[1] = 2 // stratum
        TimeSync.Ntp.writeTimestamp(reply, 24, t1) // originate: our request
        TimeSync.Ntp.writeTimestamp(reply, 32, trueNow + 20) // received after 20 ms on the wire
        TimeSync.Ntp.writeTimestamp(reply, 40, trueNow + 21)
        val t4 = t1 + 41
        val offset = TimeSync.Ntp.parse(reply, t1, t4)!!
        assertTrue("offset $offset", offset in 4_999..5_001)
        // Not an answer to our request, a kiss-of-death (stratum 0), or a client packet: refused.
        assertNull(TimeSync.Ntp.parse(reply, t1 + 1, t4))
        assertNull(TimeSync.Ntp.parse(reply.copyOf().also { it[1] = 0 }, t1, t4))
        assertNull(TimeSync.Ntp.parse(reply.copyOf().also { it[0] = 0x23 }, t1, t4))
    }
}
