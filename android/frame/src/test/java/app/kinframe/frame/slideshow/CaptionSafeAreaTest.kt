package app.kinframe.frame.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captions never run under the clock and weather block (bottom-right): single photos, smart pairs and collages, in
 * landscape and portrait, at mdpi (Denver, 1280×800) and xhdpi.
 */
class CaptionSafeAreaTest {
    /** Clock, divider and "Bewolkt · 7° – 14°" weather, as measured on the Denver (dp, margins included). */
    private val clockAndWeather = CornerReserve.quantized(452f + 40f, 104f + 28f)

    @Test fun quantizesUpSoTheClockTickingDoesNotReRender() {
        assertEquals(CornerReserve(504f, 144f), clockAndWeather)
        assertEquals(clockAndWeather, CornerReserve.quantized(470f + 40f - 7f, 100f + 28f))
        assertEquals(CornerReserve.None, CornerReserve.quantized(0f, 0f))
        assertTrue(CornerReserve.None.isNone)
    }

    @Test fun pairRightCaptionGoesAboveTheClockOrEndsBeforeIt() {
        // Landscape 1280×800, two portraits side by side: the right tile is 640…1280 (minus the 8 dp gaps). Beside a
        // clock-and-weather block there is ~90 dp left: too little, so the label moves up above the block.
        val w = 1280; val h = 800; val d = 1f
        val left = 644f; val right = 1272f; val bottom = 792f
        val box = CaptionSafeArea.tile(left, right, bottom, w, h, 16f, clockAndWeather, d)
        assertTrue(box.lifted)
        assertTrue(box.bottom <= h - (clockAndWeather.heightDp + CaptionSafeArea.LIFT_GAP_DP) * d)
        // A smaller block (weather only): the label stays on the bottom line and ends GAP_DP before the block.
        val weatherOnly = CornerReserve.quantized(220f + 40f, 92f + 28f)
        val narrow = CaptionSafeArea.tile(left, right, bottom, w, h, 16f, weatherOnly, d)
        assertFalse(narrow.lifted)
        assertEquals(bottom - 16f, narrow.bottom)
        assertTrue(left + 16f + narrow.maxWidth <= w - (weatherOnly.widthDp + CaptionSafeArea.GAP_DP) * d)
        // The left tile is untouched: full width.
        val l = CaptionSafeArea.tile(8f, 636f, bottom, w, h, 16f, clockAndWeather, d)
        assertFalse(l.lifted)
        assertEquals((636f - 8f - 32f).toInt(), l.maxWidth)
    }

    @Test fun narrowTileUnderTheClockLiftsItsLabelAboveIt() {
        // A six-photo collage: the bottom-right tile is only a third of the width, all of it under the block.
        val box = CaptionSafeArea.tile(857f, 1272f, 792f, 1280, 800, 16f, clockAndWeather, 1f)
        assertTrue(box.lifted)
        assertEquals((1272f - 857f - 32f).toInt(), box.maxWidth)
        assertTrue(box.bottom <= 800 - clockAndWeather.heightDp)
        // A top tile in the same column is above the block: unchanged.
        val top = CaptionSafeArea.tile(857f, 1272f, 396f, 1280, 800, 16f, clockAndWeather, 1f)
        assertFalse(top.lifted)
        assertEquals(396f - 16f, top.bottom)
    }

    @Test fun portraitPairBottomTile() {
        // Portrait 800×1280: two landscapes stacked; the bottom one is full width. Beside clock and weather only
        // 248 dp would be left: lifted. Beside a smaller block it stays down and ends before it.
        val box = CaptionSafeArea.tile(8f, 792f, 1272f, 800, 1280, 16f, clockAndWeather, 1f)
        assertTrue(box.lifted)
        assertEquals(800 - 32 - 16, box.maxWidth)
        val small = CornerReserve.quantized(220f + 40f, 92f + 28f)
        val beside = CaptionSafeArea.tile(8f, 792f, 1272f, 800, 1280, 16f, small, 1f)
        assertFalse(beside.lifted)
        assertEquals((800 - 264 - 24 - 8 - 16), beside.maxWidth)
    }

    @Test fun singleCaptionKeepsClearOrLifts() {
        val textLeft = 36f + 52f + 16f
        // Landscape: beside the block.
        val land = CaptionSafeArea.single(1280, 800, textLeft, 768f, clockAndWeather, 1f)
        assertFalse(land.lifted)
        assertEquals((1280 - 504 - 24 - textLeft).toInt(), land.maxWidth)
        // Portrait 800 wide with a wide block: 800 − 528 − 104 = 168 dp is too narrow, so it goes above the block.
        val port = CaptionSafeArea.single(800, 1280, textLeft, 1248f, clockAndWeather, 1f)
        assertTrue(port.lifted)
        assertEquals((800 - 36 - textLeft).toInt(), port.maxWidth)
        assertTrue(port.bottom <= 1280 - clockAndWeather.heightDp)
        // Nothing in the corner: the full width up to the caption's own margin.
        val none = CaptionSafeArea.single(1280, 800, textLeft, 768f, CornerReserve.None, 1f)
        assertEquals((1280 - 36 - textLeft).toInt(), none.maxWidth)
        assertEquals(768f, none.bottom)
    }

    @Test fun scalesWithDensity() {
        // 1920×1080 at xhdpi (2×): 960×540 dp. A single caption ends (504 + 24) dp before the right edge.
        val textLeft = (36f + 52f + 16f) * 2
        val box = CaptionSafeArea.single(1920, 1080, textLeft, 1016f, clockAndWeather, 2f)
        assertFalse(box.lifted)
        assertEquals((1920 - (504 + 24) * 2 - textLeft).toInt(), box.maxWidth)
    }

    @Test fun theOldFixedReserveIsTheDefaultBeforeMeasuring() {
        // 340 dp was kept free for the clock before; with the gap that is the same estimate.
        assertEquals(340f, CornerReserve.estimate(clockOrDate = true, weather = false).widthDp + CaptionSafeArea.GAP_DP)
        assertTrue(CornerReserve.estimate(clockOrDate = false, weather = false).isNone)
    }
}
