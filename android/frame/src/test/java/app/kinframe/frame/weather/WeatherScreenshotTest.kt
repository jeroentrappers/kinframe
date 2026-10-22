package app.kinframe.frame.weather

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.KinframeTheme
import app.kinframe.frame.slideshow.CornerWidgets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the weather widget and forecast panel to PNGs in `frame/build/screenshots/` (Robolectric native
 * graphics, frame-sized mdpi screens) for design review. They are records, not golden comparisons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class WeatherScreenshotTest {
    private val now = WeatherFixtures.nowMs

    @Test fun widgetOnLightPhoto() = shot("widget_light_photo") {
        LightPhoto()
        CornerWidgets("14:20", "Thursday 22 October", WeatherFixtures.gent(), now, "c", onPaper = false) {}
    }

    @Test fun widgetOnDarkPhoto() = shot("widget_dark_photo") {
        DarkPhoto()
        CornerWidgets("21:47", "Thursday 22 October", WeatherFixtures.gent(code = 0, isDay = false, tempC = 9.4), now, "c", onPaper = false) {}
    }

    @Test fun widgetFahrenheitWithoutClock() = shot("widget_fahrenheit_only") {
        LightPhoto()
        CornerWidgets(null, null, WeatherFixtures.gent(code = 63), now, "f", onPaper = false) {}
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun widgetOnPaperDutch() = shot("widget_paper_nl") {
        Box(Modifier.fillMaxSize().background(Color(0xFFFAF5EE)))
        CornerWidgets("14:20", "Donderdag 22 oktober", WeatherFixtures.gent(code = 3), now, "c", onPaper = true) {}
    }

    @Test fun panelLandscape() = shot("forecast_landscape") {
        LightPhoto()
        ForecastOverlay(visible = true, weather = WeatherFixtures.gent(), nowMs = now, unit = "c", h24 = true, onClose = {})
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun panelPortrait() = shot("forecast_portrait") {
        DarkPhoto()
        ForecastOverlay(visible = true, weather = WeatherFixtures.gent(), nowMs = now, unit = "c", h24 = true, onClose = {})
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun panelLandscapeDutchFahrenheit12h() = shot("forecast_landscape_nl_f_12h") {
        DarkPhoto()
        ForecastOverlay(visible = true, weather = WeatherFixtures.gent(code = 95), nowMs = now, unit = "f", h24 = false, onClose = {})
    }

    @Test fun iconSet() = shot("icons") {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF8FB8DE), Color(0xFF1C1714))))) {
            Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                WeatherGlyph.entries.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) { row.forEach { WeatherIcon(it, 160.dp) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { WeatherGlyph.entries.forEach { WeatherIcon(it, 38.dp) } }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        activity.setContent { KinframeTheme { Box(Modifier.fillMaxSize()) { content() } } }
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/screenshots/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
    }
}

/** A bright beach scene: the hardest case for white text. */
@Composable
private fun LightPhoto() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to Color(0xFF9CCBF0), 0.55f to Color(0xFFE9F4FB), 0.62f to Color(0xFFF6EBD3), 1f to Color(0xFFEBD5AE)))
        fun puff(x: Float, y: Float, r: Float) {
            drawCircle(Color.White.copy(alpha = 0.85f), r, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.8f), r * 0.8f, Offset(x + r * 0.9f, y + r * 0.2f))
            drawCircle(Color.White.copy(alpha = 0.8f), r * 0.7f, Offset(x - r * 0.9f, y + r * 0.25f))
        }
        puff(size.width * 0.22f, size.height * 0.2f, 60f)
        puff(size.width * 0.7f, size.height * 0.32f, 80f)
        drawRect(Color(0xFFFFFFFF).copy(alpha = 0.35f), Offset(0f, size.height * 0.6f), Size(size.width, 8f))
        drawCircle(Color(0xFFD9A066).copy(alpha = 0.5f), 120f, Offset(size.width * 0.15f, size.height * 0.95f))
    }
}

/** An evening city scene. */
@Composable
private fun DarkPhoto() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0D1626), Color(0xFF2B2A44), Color(0xFF472F3A))))
        val warm = listOf(Color(0xFFFFC46B), Color(0xFFFF9C5B), Color(0xFFFFE3A3))
        for (i in 0 until 40) {
            val x = (i * 97 % 1000) / 1000f * size.width
            val y = size.height * (0.55f + (i * 37 % 100) / 260f)
            drawCircle(warm[i % 3].copy(alpha = 0.55f), 3f + i % 5, Offset(x, y))
        }
        drawRect(Color(0xFF080A12).copy(alpha = 0.7f), Offset(0f, size.height * 0.82f), Size(size.width, size.height * 0.18f))
    }
}
