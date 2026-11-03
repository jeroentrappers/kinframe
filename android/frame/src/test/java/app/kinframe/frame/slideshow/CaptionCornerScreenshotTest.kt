package app.kinframe.frame.slideshow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.kinframe.frame.Screenshots
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The caption and the clock/weather corner together, as on the frame: the slide is rendered for the corner the UI
 * measured (two passes, like the slideshow's re-render), so the caption keeps clear of it. Neutral drawn photos only;
 * written to `frame/build/screenshots/caption_corner_*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class CaptionCornerScreenshotTest {
    private val dir = File("build/tmp/caption-corner").apply { mkdirs() }
    private val weather = WeatherFixtures.gent(code = 3)
    private val now = WeatherFixtures.nowMs
    private val longMeta = "Line Van Gavere · via Frameo · 5 dagen geleden"

    private fun photo(name: String, w: Int, h: Int, top: Int, bottom: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        val f = File(dir, "$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private val a by lazy { photo("a", 600, 900, 0xFFBFD8D2.toInt(), 0xFF1F3B36.toInt()) }
    private val b by lazy { photo("b", 600, 900, 0xFFF3D9B1.toInt(), 0xFF6B3F2F.toInt()) }
    private val wide by lazy { photo("w", 1500, 1000, 0xFF9CCBF0.toInt(), 0xFF55804A.toInt()) }

    /** [w] × [h] dp photo area (mdpi: px = dp) with the slide rendered for the measured corner and the corner on top. */
    private fun scene(name: String, w: Int, h: Int, render: (CornerReserve) -> Bitmap) = Screenshots.shot("caption_corner_$name") {
        var reserve by remember { mutableStateOf<CornerReserve?>(null) }
        Box(Modifier.size(w.dp, h.dp)) {
            reserve?.let { r ->
                val img = remember(r) { render(r).asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize())
            }
            CornerWidgets(
                "14:20", "Sunday 25 October", weather, now, "c", onPaper = false,
                onBlockSize = { bw, bh -> reserve = CornerReserve.quantized(bw, bh) },
            ) {}
        }
    }

    private fun pair(w: Int, h: Int, r: CornerReserve): Bitmap {
        val landscape = w >= h
        val cells = Collage.cells(2, landscape)
        val files = if (landscape) listOf(a, b) else listOf(wide, wide)
        val labels = listOf("Anna · Yesterday", longMeta)
        val tiles = cells.mapIndexed { i, cell -> SlideRenderer.TileRequest(files[i], 0, null, null, cell, label = labels[i], caption = "Herfstwandeling in het bos".takeIf { i == 1 }) }
        return SlideRenderer().renderTiles(tiles, w, h, 1f, r)
    }

    private fun single(w: Int, h: Int, r: CornerReserve): Bitmap = SlideRenderer().render(
        SlideRenderer.Request(
            wide, isVideo = false, screenW = w, screenH = h, fill = true, focalX = null, focalY = null, rotation = 0, blurBars = true,
            caption = SlideRenderer.CaptionSpec(
                text = "A long caption about the autumn walk in the woods behind the house, with everyone in their boots and the dog far ahead",
                meta = longMeta, avatar = null, initials = "LV", avatarColor = Avatars.color("line"), corner = r,
            ),
            density = 1f,
        ),
    )

    @Test fun pairLandscape() = scene("pair_landscape", 1280, 800) { pair(1280, 800, it) }

    /** How it was: tile labels ignored the corner (for comparison). */
    @Test fun pairLandscapeBefore() = scene("pair_landscape_before", 1280, 800) { pair(1280, 800, CornerReserve.None) }

    /** The 3:2 photo area beside the plugin band (clock not in the band). */
    @Test fun pairBesideTheBand() = scene("pair_band", 1200, 800) { pair(1200, 800, it) }

    @Test fun singleLandscape() = scene("single_landscape", 1280, 800) { single(1280, 800, it) }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun pairPortrait() = scene("pair_portrait", 800, 1280) { pair(800, 1280, it) }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun singlePortrait() = scene("single_portrait", 800, 1280) { single(800, 1280, it) }
}
