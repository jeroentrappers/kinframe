package app.kinframe.frame.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plugins.md §2.1: a 3:2 photo area (2:3 in portrait) and the band in what is left of the long side. */
class BandGeometryTest {
    private fun band(w: Int, h: Int, density: Float = 1f, side: BandSide = BandSide.Right) =
        BandGeometry.compute(w, h, density, side) as BandGeometry.Band

    @Test fun denverLandscape1280x800() {
        val b = band(1280, 800)
        assertEquals(PxRect(0, 0, 1200, 800), b.photo)
        assertEquals(PxRect(1200, 0, 80, 800), b.band)
        assertEquals(BandGeometry.Edge.Right, b.edge)
        assertEquals(80, b.thickness)
        assertEquals(1.5f, b.photo.width.toFloat() / b.photo.height)
    }

    @Test fun denverLandscapeBandOnTheLeft() {
        val b = band(1280, 800, side = BandSide.Left)
        assertEquals(PxRect(80, 0, 1200, 800), b.photo)
        assertEquals(PxRect(0, 0, 80, 800), b.band)
        assertEquals(BandGeometry.Edge.Left, b.edge)
    }

    @Test fun denverPortrait800x1280() {
        // The strip is always at the top, away from the caption; the side setting does not apply.
        for (side in BandSide.entries) {
            val b = band(800, 1280, side = side)
            assertEquals(PxRect(0, 80, 800, 1200), b.photo)
            assertEquals(PxRect(0, 0, 800, 80), b.band)
            assertEquals(BandGeometry.Edge.Top, b.edge)
            assertTrue(!b.vertical)
        }
    }

    @Test fun fullHd1920x1080GetsAWiderBand() {
        val b = band(1920, 1080, density = 2f)
        assertEquals(PxRect(0, 0, 1620, 1080), b.photo)
        assertEquals(300, b.thickness)
        val p = band(1080, 1920, density = 2f)
        assertEquals(PxRect(0, 300, 1080, 1620), p.photo)
    }

    @Test fun fourByThreeHasNoRoomAndUsesTheCorners() {
        assertEquals(BandGeometry.Overlay, BandGeometry.compute(1024, 768, 1f, BandSide.Right))
        assertEquals(BandGeometry.Overlay, BandGeometry.compute(768, 1024, 1f, BandSide.Right))
    }

    @Test fun aBandThinnerThanTheMinimumFallsBack() {
        // 1280×800 at xhdpi: 80 px is only 40 dp.
        assertEquals(BandGeometry.Overlay, BandGeometry.compute(1280, 800, 2f, BandSide.Right))
        // 1024×600 (another common frame panel): 1024 − 900 = 124 px band.
        assertEquals(124, band(1024, 600).thickness)
        assertEquals(BandGeometry.Overlay, BandGeometry.compute(0, 0, 1f, BandSide.Right))
    }

    @Test fun animatedPhotoAreaFollowsTheFraction() {
        val full = PxRect(0, 0, 1280, 800)
        val out = IntArray(4)
        BandGeometry.animatedPhoto(full, band(1280, 800), 0f, out)
        assertEquals(listOf(0, 0, 1280, 800), out.toList())
        BandGeometry.animatedPhoto(full, band(1280, 800), 1f, out)
        assertEquals(listOf(0, 0, 1200, 800), out.toList())
        BandGeometry.animatedPhoto(full, band(1280, 800, side = BandSide.Left), 0.5f, out)
        assertEquals(listOf(40, 0, 1240, 800), out.toList())
        BandGeometry.animatedPhoto(PxRect(0, 0, 800, 1280), band(800, 1280), 1f, out)
        assertEquals(listOf(0, 80, 800, 1200), out.toList())
    }

    @Test fun coverScale() {
        // A 3:2 render covering the full 16:10 screen while the band closes: scaled by 1280/1200.
        assertEquals(1280f / 1200f, BandGeometry.coverScale(1200, 800, 1280, 800), 1e-6f)
        assertEquals(1f, BandGeometry.coverScale(1280, 800, 1280, 800), 0f)
        assertEquals(1280f / 1200f, BandGeometry.coverScale(800, 1200, 800, 1280), 1e-6f)
    }
}
