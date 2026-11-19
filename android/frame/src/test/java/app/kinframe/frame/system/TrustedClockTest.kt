package app.kinframe.frame.system

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** A frame without a backup battery starts at its factory date and gets the right time from NTP a minute later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrustedClockTest {
    /** 2026-11-18 19:08:50 UTC. */
    private val trueNow = Instant.parse("2026-11-18T19:08:50Z").toEpochMilli()

    /** 2021-05-21 00:00 UTC, the Denver's factory date. */
    private val factory = Instant.parse("2021-05-21T00:00:00Z").toEpochMilli()

    private var wall = factory
    private var elapsed = 30_000L
    private val clock = TrustedClock({ wall }, { elapsed })

    private fun advance(ms: Long) { wall += ms; elapsed += ms }

    @Test fun `no server time means the OS clock`() {
        assertFalse(clock.trusted)
        assertEquals(factory, clock.nowMs())
    }

    @Test fun `server time corrects a clock still on the factory date`() {
        clock.onServerTime(Instant.ofEpochMilli(trueNow).toString())
        assertTrue(clock.trusted)
        assertEquals(trueNow, clock.nowMs())
        advance(90_000)
        assertEquals(trueNow + 90_000, clock.nowMs())
    }

    @Test fun `NTP fixing the OS clock after the hello is not counted twice`() {
        clock.onServerTime(Instant.ofEpochMilli(trueNow).toString())
        advance(50_000)
        wall = trueNow + 50_000 // NTP sets the OS clock; the monotonic clock doesn't jump
        assertEquals(0, clock.offsetMs)
        assertEquals(trueNow + 50_000, clock.nowMs())
    }

    @Test fun `small drift is left to the OS clock`() {
        wall = trueNow + 30_000
        clock.onServerTime(Instant.ofEpochMilli(trueNow).toString())
        assertEquals(0, clock.offsetMs)
        assertEquals(trueNow + 30_000, clock.nowMs())
    }

    @Test fun `garbage server time is ignored`() {
        clock.onServerTime("not a time")
        clock.onServerTime(null)
        assertFalse(clock.trusted)
        assertEquals(factory, clock.nowMs())
    }
}
