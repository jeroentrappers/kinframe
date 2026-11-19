package app.kinframe.frame.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The splash must put everything exactly where the boot animation has it. Expected numbers come from
 * tools/brand/boot/build.py (its Layout, the resvg-rendered wordmark size and anim_frame()), printed with
 * `uv run --no-project --with resvg-py==0.5.0 --with pillow==12.0.0` for both frame screens.
 */
class BootSplashGeometryTest {
    @Test fun referenceScreen1280x800() {
        val l = BootSplashGeometry.of(1280, 800)
        assertEquals(1.0, l.scale, 0.0)
        assertEquals(listOf(359, 336, 562), listOf(l.logoX, l.logoY, l.logoW))
        assertEquals(128.0, l.logoH, 1e-9)
        assertEquals(520.0, l.dotY, 0.0)
        assertEquals(listOf(610.0, 640.0, 670.0), l.dotX)
        assertEquals(6.0, l.dotR, 1e-9)
    }

    @Test fun pff1515Screen1920x1080() {
        val l = BootSplashGeometry.of(1920, 1080)
        assertEquals(1.35, l.scale, 1e-12)
        // resvg renders the wordmark 759 x 173 px (64 * 759 / 281 = 172.87, rounded up, the viewBox stretched over it) at (580, 453).
        assertEquals(listOf(580, 453, 759), listOf(l.logoX, l.logoY, l.logoW))
        assertEquals(173.0, l.logoH, 0.0)
        // The 972 x 454 animation frame sits at (474, 313); the dots at y = 313 + 227 + 86 + 76.
        assertEquals(702.0, l.dotY, 0.0)
        assertEquals(listOf(919.5, 960.0, 1000.5), l.dotX.map { Math.round(it * 1e6) / 1e6 })
        assertEquals(8.1, l.dotR, 1e-9)
    }

    @Test fun dotsPulseLikeTheBootAnimation() {
        fun hex(c: Color) = "#%02x%02x%02x".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        fun frame(i: Int) = (0 until 3).map { hex(BootSplashGeometry.dotColor(BootSplashGeometry.glow(i, it))) }
        assertEquals(listOf("#f09a6d", "#684c3c", "#3a322b"), frame(0))
        assertEquals(listOf("#46392f", "#e49369", "#95664c"), frame(5))
        assertEquals(listOf("#3a322b", "#684c3c", "#f09a6d"), frame(8))
        assertEquals(listOf("#3a322b", "#3a322b", "#46392f"), frame(13))
        assertEquals(1.0, BootSplashGeometry.glow(0, 0), 1e-12)
        assertEquals(6.0 * 0.85, BootSplashGeometry.dotRadius(BootSplashGeometry.of(1280, 800), 0.0), 1e-12)
        // 20 frames a second, 24 frames a loop.
        assertEquals(0, BootSplashGeometry.frameAt(0))
        assertEquals(1, BootSplashGeometry.frameAt(50))
        assertEquals(23, BootSplashGeometry.frameAt(1_199))
        assertEquals(0, BootSplashGeometry.frameAt(1_200))
    }
}
