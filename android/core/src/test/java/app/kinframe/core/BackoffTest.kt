package app.kinframe.core

import app.kinframe.core.ws.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun growsFromOneSecondToSixtyWithTwentyPercentJitter() {
        val b = Backoff(random = Random(42))
        val delays = List(12) { b.next() }
        val bases = listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000)
        delays.zip(bases).forEach { (d, base) ->
            assertTrue("$d not within ±20% of $base", d >= base * 0.8 - 1 && d <= base * 1.2 + 1)
        }
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff(jitter = 0.0)
        repeat(5) { b.next() }
        b.reset()
        assertEquals(1_000L, b.next())
        assertEquals(2_000L, b.next())
    }

    @Test
    fun jitterActuallyVaries() {
        val b = Backoff(random = Random(1))
        val seen = (0 until 20).map { b.reset(); b.next() }.toSet()
        assertTrue(seen.size > 5)
    }
}
