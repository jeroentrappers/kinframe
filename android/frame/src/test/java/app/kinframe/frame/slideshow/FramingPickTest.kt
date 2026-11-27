package app.kinframe.frame.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which view a screen shows (docs/spec/framing.md §3.1) and Ken Burns inside a chosen view (§3.4). */
class FramingPickTest {
    private val item = """{"landscape":{"x":0.2,"y":0.3,"zoom":2},"portrait":{"x":0.7,"y":0.6,"zoom":1.5}}"""
    private val override = """{"portrait":{"x":0.1,"y":0.9,"zoom":3}}"""

    private fun pick(
        landscape: Boolean, framing: String? = null, framingOverride: String? = null, focalOverridden: Boolean = false,
        focalX: Double? = null, focalY: Double? = null, video: Boolean = false,
    ) = FramingPick.pick(video, framingOverride, focalOverridden, framing, focalX, focalY, landscape)

    @Test
    fun squareScreensAreLandscape() {
        assertTrue(FramingPick.isLandscape(1280, 800))
        assertTrue(FramingPick.isLandscape(1000, 1000))
        assertFalse(FramingPick.isLandscape(800, 1280))
    }

    @Test
    fun frameOwnersOverrideComesFirstForItsOrientation() {
        assertEquals(FramingPick.View(0.1, 0.9, 3.0, chosen = true), pick(false, item, override, focalOverridden = true, focalX = 0.5, focalY = 0.5))
        // The override has no landscape view: the focal override (at 1×) comes before the item's framing.
        assertEquals(FramingPick.View(0.5, 0.4, 1.0, chosen = false), pick(true, item, override, focalOverridden = true, focalX = 0.5, focalY = 0.4))
    }

    @Test
    fun itemFramingThenFocalPointThenCentre() {
        assertEquals(FramingPick.View(0.2, 0.3, 2.0, chosen = true), pick(true, item, focalX = 0.25, focalY = 0.3))
        assertEquals(FramingPick.View(0.7, 0.6, 1.5, chosen = true), pick(false, item, focalX = 0.25, focalY = 0.3))
        val onlyLandscape = """{"landscape":{"x":0.2,"y":0.3,"zoom":2}}"""
        assertEquals(FramingPick.View(0.25, 0.3, 1.0, chosen = false), pick(false, onlyLandscape, focalX = 0.25, focalY = 0.3))
        assertEquals(FramingPick.View(0.5, 0.5, 1.0, chosen = false), pick(true))
    }

    @Test
    fun videosAndBadDocumentsIgnoreFraming() {
        assertEquals(FramingPick.View(0.3, 0.5, 1.0, chosen = false), pick(true, item, override, focalX = 0.3, video = true))
        assertEquals(FramingPick.View(0.5, 0.5, 1.0, chosen = false), pick(true, "not json"))
        // Out of range values from a newer or broken server are kept inside the photo and 1..4.
        assertEquals(FramingPick.View(1.0, 0.0, 4.0, chosen = true), pick(true, """{"landscape":{"x":3,"y":-1,"zoom":12}}"""))
    }

    @Test
    fun onlyAZoomedChosenViewStaysOutOfPairs() {
        assertTrue(pick(true, item).zoomed)
        assertFalse(pick(true, """{"landscape":{"x":0.2,"y":0.3,"zoom":1}}""").zoomed)
        assertFalse(pick(true, focalX = 0.2, focalY = 0.3).zoomed)
    }

    @Test
    fun kenBurnsInsideAViewStartsAtItAndStaysClose() {
        for (i in 0 until 50) {
            val p = KenBurns.inside("placement-$i")
            assertEquals(KenBurns.Key(1f, 0f, 0f), p.at(0f))
            var t = 0f
            while (t < p.keys.size) {
                val k = p.at(t)
                assertTrue("scale ${k.scale}", k.scale in 1f..KenBurns.INSIDE_MAX)
                assertTrue("drift ${k.x}", kotlin.math.abs(k.x) <= KenBurns.INSIDE_DRIFT + 1e-6f)
                assertTrue("drift ${k.y}", kotlin.math.abs(k.y) <= KenBurns.INSIDE_DRIFT + 1e-6f)
                // The photo always covers the screen.
                assertTrue(kotlin.math.abs(k.x) <= (k.scale - 1f) / 2f + 1e-6f)
                t += 0.05f
            }
        }
        assertEquals(KenBurns.inside("abc"), KenBurns.inside("abc"))
        // Two keys: the view and a slight push in (1.04..1.08), one key every half the hold, so it ends at the view.
        for (i in 0 until 50) {
            val p = KenBurns.inside("p$i")
            assertEquals(2, p.keys.size)
            assertTrue(p.keys[1].scale in 1.04f..KenBurns.INSIDE_MAX)
            assertEquals(15_000L, p.segmentMs(30_000))
            assertEquals(KenBurns.Key(1f, 0f, 0f), p.at(2f))
        }
        assertEquals(KenBurns.SEGMENT_MS, KenBurns.params("abc", null, null).segmentMs(30_000))
    }
}
