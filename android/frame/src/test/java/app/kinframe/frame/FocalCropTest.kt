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
}
