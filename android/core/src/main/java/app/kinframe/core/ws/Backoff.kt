package app.kinframe.core.ws

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential back-off with jitter (README §6: 1 s up to 60 s, ±20 %).
 * Not thread-safe; owned by one reconnect loop.
 */
class Backoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val factor: Double = 2.0,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    /** Delay before the next attempt; grows with each call until [reset]. */
    fun next(): Long {
        val base = min(maxMs.toDouble(), initialMs * Math.pow(factor, attempt.toDouble()))
        attempt = min(attempt + 1, 30)
        val j = 1.0 + (random.nextDouble() * 2 - 1) * jitter
        return (base * j).toLong().coerceAtLeast(1)
    }

    fun reset() { attempt = 0 }
}
