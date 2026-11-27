package app.kinframe.frame

import app.kinframe.frame.slideshow.FocalCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalCropTest {
    private val eps = 0.5f

    @Test
    fun fillCropsAroundFocalPoint() {
        // 3:2 photo on a 16:10 screen: full width, crop height 1200 of 1280.
        val r = FocalCrop.fillSource(1920, 1280, 1280, 800, 0.5, 0.5)
        assertEquals(0f, r.left, eps); assertEquals(1920f, r.right, eps)
        assertEquals(40f, r.top, eps); assertEquals(1240f, r.bottom, eps)
        // Focal point at the top: crop sticks to the top edge.
        val top = FocalCrop.fillSource(1920, 1280, 1280, 800, 0.5, 0.0)
        assertEquals(0f, top.top, eps); assertEquals(1200f, top.bottom, eps)
    }

    @Test
    fun fillClampsAtEdges() {
        // Portrait on landscape: crop is 1280 × 800 of 1280 × 1920 (scale 1).
        val r = FocalCrop.fillSource(1280, 1920, 1280, 800, 0.5, 0.95)
        assertEquals(1120f, r.top, eps); assertEquals(1920f, r.bottom, eps)
        val l = FocalCrop.fillSource(4000, 1000, 1280, 800, 0.0, 0.5)
        assertEquals(0f, l.left, eps)
        assertEquals(1600f, l.width, eps)
    }

    @Test
    fun fitLetterboxesCentred() {
        val r = FocalCrop.fitDest(1280, 1920, 1280, 800)
        assertEquals(800f, r.height, eps)
        assertEquals(533.33f, r.width, 1f)
        assertEquals((1280 - 533.33f) / 2, r.left, 1f)
    }

    @Test
    fun sampleSizeNeverGoesBelowScreen() {
        assertEquals(1, FocalCrop.sampleSize(1920, 1280, 1280, 853))
        assertEquals(2, FocalCrop.sampleSize(4000, 3000, 1280, 960))
        assertEquals(4, FocalCrop.sampleSize(8000, 6000, 1280, 960))
        assertEquals(1, FocalCrop.sampleSize(800, 600, 1280, 960))
    }

    @Test
    fun portraitOnLandscapeIsTooAggressiveToFill() {
        assertTrue(FocalCrop.cropTooAggressive(1280, 1920, 1280, 800))
        assertFalse(FocalCrop.cropTooAggressive(1920, 1280, 1280, 800))
        assertFalse(FocalCrop.cropTooAggressive(1600, 1600, 1280, 800))
    }

    // ---- framing (docs/spec/framing.md §3)

    @Test
    fun viewAtZoomOneIsExactlyTheFocalFill() {
        val shapes = listOf(1920 to 1280, 1280 to 1920, 1600 to 1600, 4000 to 1000, 2560 to 1440)
        val screens = listOf(1280 to 800, 800 to 1280, 1024 to 600, 1920 to 1080)
        val points = listOf(0.0, 0.13, 0.5, 0.87, 1.0)
        for ((w, h) in shapes) for ((sw, sh) in screens) for (x in points) for (y in points) {
            assertEquals(FocalCrop.fillSource(w, h, sw, sh, x, y), FocalCrop.viewSource(w, h, sw, sh, x, y, 1.0))
        }
    }

    @Test
    fun zoomShowsAFractionOfTheCoverCropAroundTheCentre() {
        // 4:3 photo on a 16:10 screen: the cover crop is 2560 × 1600; at 2× half of it, 1280 × 800, around (0.4, 0.3).
        val r = FocalCrop.viewSource(2560, 1920, 1280, 800, 0.4, 0.3, 2.0)
        assertEquals(1280f, r.width, eps); assertEquals(800f, r.height, eps)
        assertEquals(0.4f * 2560 - 640, r.left, eps); assertEquals(0.3f * 1920 - 400, r.top, eps)
        // Same shape as the screen: drawn without distortion.
        assertEquals(1280f / 800f, r.width / r.height, 0.001f)
    }

    @Test
    fun zoomedViewNeverLeavesThePhoto() {
        val r = FocalCrop.viewSource(2560, 1920, 1280, 800, 0.98, 0.02, 3.0)
        assertEquals(2560f, r.right, eps); assertEquals(0f, r.top, eps)
        val l = FocalCrop.viewSource(2560, 1920, 1280, 800, 0.0, 1.0, 4.0)
        assertEquals(0f, l.left, eps); assertEquals(1920f, l.bottom, eps)
        assertEquals(2560f / 4, l.width, eps)
    }

    @Test
    fun zoomIsClampedToOneToFour() {
        assertEquals(FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, 4.0), FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, 9.0))
        assertEquals(FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, 1.0), FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, 0.2))
        assertEquals(FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, 1.0), FocalCrop.viewSource(2560, 1920, 1280, 800, 0.5, 0.5, Double.NaN))
    }

    @Test
    fun zoomedViewDecodesEnoughForTheVisiblePart() {
        // §3.3: the screen size times the zoom; a 2560 px master at 2× on 1280 × 800 is decoded whole, not halved.
        assertEquals(2, FocalCrop.sampleSize(2560, 1920, 1280, 960))
        assertEquals(1, FocalCrop.sampleSize(2560, 1920, 1280 * 2, 960 * 2))
    }
}
