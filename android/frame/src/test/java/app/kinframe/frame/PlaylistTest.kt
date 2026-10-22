package app.kinframe.frame

import app.kinframe.frame.slideshow.PlayOrder
import app.kinframe.frame.slideshow.Playlist
import app.kinframe.frame.slideshow.PlaylistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {
    private val day = 86_400_000L
    private val now = 1000 * day
    private fun items(n: Int) = (1..n).map { PlaylistEntry("p$it", receivedAt = now - it * day, takenAt = now - (n - it) * day) }

    private fun Playlist.take(n: Int) = List(n) { next()!! }

    @Test
    fun receivedNewestFirstAndReverse() {
        val p = Playlist(1) { now }
        p.update(items(4), PlayOrder.RECEIVED, reverse = false)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p1"), p.take(5))
        val r = Playlist(1) { now }
        r.update(items(4), PlayOrder.RECEIVED, reverse = true)
        assertEquals(listOf("p4", "p3", "p2", "p1"), r.take(4))
    }

    @Test
    fun takenOrderUsesCaptureTime() {
        val p = Playlist(1) { now }
        p.update(items(3), PlayOrder.TAKEN, reverse = false)
        assertEquals(listOf("p3", "p2", "p1"), p.take(3))
    }

    @Test
    fun shuffleHasNoRepeatsWithinACycleAndNoBackToBackAcrossCycles() {
        val n = 25
        val p = Playlist(12345) { now }
        p.update(items(n), PlayOrder.SHUFFLE, reverse = false)
        var last: String? = null
        repeat(6) { cycle ->
            val c = p.take(n)
            assertEquals("cycle $cycle has repeats", n, c.toSet().size)
            if (last != null) assertNotEquals("repeat across cycle boundary", last, c.first())
            last = c.last()
        }
    }

    @Test
    fun shuffleIsWeightedTowardRecentItems() {
        // p1..p10 are recent (days old), p11..p20 are a year old.
        val list = (1..20).map { PlaylistEntry("p$it", receivedAt = now - (if (it <= 10) it else 365 + it) * day, takenAt = null) }
        var recentInFirstHalf = 0
        for (seed in 1L..300L) {
            val p = Playlist(seed) { now }
            p.update(list, PlayOrder.SHUFFLE, false)
            recentInFirstHalf += p.take(10).count { it.removePrefix("p").toInt() <= 10 }
        }
        val share = recentInFirstHalf / 3000.0
        assertTrue("recent share in first half was $share", share > 0.6)
    }

    @Test
    fun peekNextMatchesNext() {
        val p = Playlist(7) { now }
        p.update(items(9), PlayOrder.SHUFFLE, false)
        repeat(30) {
            val peek = p.peekNext()
            assertEquals(peek, p.next())
        }
    }

    @Test
    fun newItemsPlayFirstThenOrderResumes() {
        val p = Playlist(1) { now }
        p.update(items(4), PlayOrder.RECEIVED, false)
        assertEquals(listOf("p1", "p2"), p.take(2))
        val withNew = items(4) + PlaylistEntry("n1", now, null, isNew = true) + PlaylistEntry("n2", now + 1, null, isNew = true)
        p.update(withNew, PlayOrder.RECEIVED, false)
        assertEquals(listOf("n1", "n2"), p.pendingNew())
        assertEquals(listOf("n1", "n2", "p3", "p4"), p.take(4))
        assertTrue(p.pendingNew().isEmpty())
    }

    @Test
    fun previousGoesBackThroughHistory() {
        val p = Playlist(1) { now }
        p.update(items(5), PlayOrder.RECEIVED, false)
        p.take(3)
        assertEquals("p2", p.previous())
        assertEquals("p1", p.previous())
    }

    @Test
    fun removedCurrentItemIsSkippedAndJumpWorks() {
        val p = Playlist(1) { now }
        p.update(items(4), PlayOrder.RECEIVED, false)
        p.take(2)
        p.update(items(4).filter { it.id != "p3" }, PlayOrder.RECEIVED, false)
        assertEquals("p4", p.next())
        assertTrue(p.jumpTo("p1"))
        assertEquals("p1", p.current)
    }

    @Test
    fun singleItemAndEmpty() {
        val p = Playlist(1) { now }
        p.update(emptyList(), PlayOrder.SHUFFLE, false)
        assertEquals(null, p.next())
        p.update(items(1), PlayOrder.SHUFFLE, false)
        assertEquals(listOf("p1", "p1"), p.take(2))
    }
}
