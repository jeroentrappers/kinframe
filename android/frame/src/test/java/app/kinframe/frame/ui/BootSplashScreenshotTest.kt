package app.kinframe.frame.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.kinframe.frame.StartupStep
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders the start-up splash at the frames' screen sizes (Robolectric native graphics) and compares it with frame 000
 * of the boot animation build.py made for that screen (web/static/frame-boot/kinframe/bootanimation-<w>x<h>.zip):
 * the picture must not change when Android hands over from the boot animation to the app. Writes both and a
 * difference image to frame/build/screenshots/splash/ for review.
 *
 * The two can't be byte-identical: resvg and Skia anti-alias edges a little differently, and the boot animation's
 * frames are quantized to 256 colours. So this checks the geometry (the shapes' coverage, which moves a lot when
 * anything is off by a pixel) and bounds the edge differences.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class BootSplashScreenshotTest {
    @Test @Config(qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun matchesTheBootAnimation1920x1080() = compare(1920, 1080)

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun matchesTheBootAnimation1280x800() = compare(1280, 800)

    /** A record for review: the status line under the dots, faded in after its delay. */
    @Test @Config(qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun withStatusLine() {
        val shot = render(StartupStep.PreparingPhoto, shownForMs = SplashClock.STATUS_DELAY_MS)
        val dir = File("build/screenshots/splash").apply { mkdirs() }
        File(dir, "splash-status-1920x1080.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Something is drawn below the dots.
        val l = BootSplashGeometry.of(1920, 1080)
        val y0 = (l.statusY - l.statusSize).toInt()
        val y1 = (l.statusY + l.statusSize).toInt()
        assertTrue((y0..y1).any { y -> (700 until 1220).any { x -> shot.getPixel(x, y) and 0xffffff != 0x191512 } })
    }

    private class Diff(val max: Int, val mean: Double, val over32: Int, val pixels: Int)

    private fun compare(w: Int, h: Int) {
        val shot = render()
        assertTrue("rendered ${shot.width}x${shot.height}", shot.width == w && shot.height == h)
        val zip = File("../../web/static/frame-boot/kinframe/bootanimation-${w}x$h.zip")
        val ref = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("part0/000.png")).use { ImageIO.read(it) } }
        val ox = (w - ref.width) / 2
        val oy = (h - ref.height) / 2
        val l = BootSplashGeometry.of(w, h)
        fun splash(x: Int, y: Int) = shot.getPixel(x, y)
        fun boot(x: Int, y: Int) = ref.getRGB(x - ox, y - oy)

        fun diff(x0: Int, y0: Int, x1: Int, y1: Int, out: IntArray? = null): Diff {
            var mx = 0; var sum = 0L; var over = 0; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val a = splash(x, y); val b = boot(x, y)
                val d = max(abs((a shr 16 and 255) - (b shr 16 and 255)), max(abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))))
                mx = max(mx, d); sum += d; n++
                if (d > 32) over++
                out?.set((y - oy) * ref.width + (x - ox), d)
            }
            return Diff(mx, sum.toDouble() / n, over, n)
        }
        val all = IntArray(ref.width * ref.height)
        val frame = diff(ox, oy, ox + ref.width, oy + ref.height, all)
        val mark = diff(l.logoX, l.logoY, l.logoX + l.logoW, l.logoY + kotlin.math.ceil(l.logoH).toInt())
        val dotsTop = (l.dotY - l.dotR - 2).toInt()
        val dots = diff((l.dotX[0] - l.dotR - 2).toInt(), dotsTop, (l.dotX[2] + l.dotR + 3).toInt(), (l.dotY + l.dotR + 3).toInt())
        // Outside the wordmark and the dots the splash is the plain background, like the boot animation.
        val bg = diff(ox, oy, ox + ref.width, l.logoY - 1)
        // Geometry: how much "ink" (distance from the background) each picture has in the wordmark rectangle.
        fun ink(px: (Int, Int) -> Int): Double {
            var s = 0.0
            for (y in l.logoY until l.logoY + kotlin.math.ceil(l.logoH).toInt()) for (x in l.logoX until l.logoX + l.logoW) {
                val p = px(x, y)
                s += abs((p shr 16 and 255) - 0x19) + abs((p shr 8 and 255) - 0x15) + abs((p and 255) - 0x12)
            }
            return s
        }
        val inkRatio = ink(::splash) / ink(::boot)

        val dir = File("build/screenshots/splash").apply { mkdirs() }
        File(dir, "splash-${w}x$h.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ImageIO.write(ref, "png", File(dir, "boot-${w}x$h-000.png"))
        val d = java.awt.image.BufferedImage(ref.width, ref.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (i in all.indices) d.setRGB(i % ref.width, i / ref.width, (minOf(255, all[i] * 4)) * 0x010101)
        ImageIO.write(d, "png", File(dir, "diff-${w}x$h.png"))
        val report = "${w}x$h: frame max ${frame.max} mean ${"%.3f".format(frame.mean)} (>32: ${frame.over32}/${frame.pixels}); " +
            "wordmark max ${mark.max} mean ${"%.3f".format(mark.mean)} (>32: ${mark.over32}/${mark.pixels}), ink ratio ${"%.4f".format(inkRatio)}; " +
            "dots max ${dots.max} mean ${"%.3f".format(dots.mean)}; background max ${bg.max}"
        println(report)
        File(dir, "report-${w}x$h.txt").writeText(report + "\n")

        assertTrue(report, bg.max <= 2)
        assertTrue(report, abs(inkRatio - 1) < 0.01)
        assertTrue(report, mark.mean < 0.3)
        assertTrue(report, dots.mean < 2.5)
    }

    private fun render(step: StartupStep = StartupStep.Ready, settle: java.time.Duration? = null, shownForMs: Long? = null): Bitmap {
        SplashClock.reset()
        // Deterministic: the status line is due from the first frame instead of after a real-time delay.
        shownForMs?.let { SplashClock.pretendShownFor(it) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        activity.setContent { BootSplash(step, showStatus = step != StartupStep.Ready, fixedFrame = 0) }
        shadowOf(Looper.getMainLooper()).idle()
        // The status line's delay is a coroutine delay, which runs on real time (the UI dispatcher has no delay of its
        // own), while the fade runs on Robolectric's clock: step both, in small steps so every frame of the fade happens.
        if (settle != null) {
            val until = System.currentTimeMillis() + settle.toMillis()
            while (System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                Thread.sleep(40)
            }
        }
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        return bmp
    }
}
