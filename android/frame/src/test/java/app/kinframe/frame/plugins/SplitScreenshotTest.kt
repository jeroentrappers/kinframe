package app.kinframe.frame.plugins

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.Screenshots
import app.kinframe.frame.calendar.CalendarFixtures
import app.kinframe.frame.slideshow.Collage
import app.kinframe.frame.slideshow.CornerReserve
import app.kinframe.frame.slideshow.SlideRenderer
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The split layout at the Denver's size: two photos rendered by the slideshow's own tile renderer, each in the cell its
 * shape fits, and the dashboard in the remaining quarter. `frame/build/screenshots/split_*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class SplitScreenshotTest {
    private val examples = File("../../docs/api/plugin-views/examples")
    private fun view(name: String): PluginView = when (val r = PluginViewParser.parse(examples.resolve("$name.json").readText())) {
        is ViewResult.Valid -> r.view
        is ViewResult.Invalid -> error("$name: $r")
    }

    private fun photo(w: Int, h: Int, seed: Int): File =
        File.createTempFile("split", ".png").also { f -> f.outputStream().use { placeholderPhoto(w, h, seed).asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }

    private fun shot(name: String, side: BandSide, widgets: List<InstanceState>, calendar: Boolean = true) = Screenshots.shot("split_$name") {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val cells = SplitGeometry.cells(w >= h, side)
            val slide = remember {
                // A landscape and a portrait photo, placed as the slideshow places them.
                val aspects = listOf(1.5f, 0.75f)
                val files = listOf(photo(1200, 800, 0), photo(600, 800, 2))
                val placed = Collage.placeInto(listOf(cells.big, cells.small), aspects, w, h)
                val tiles = placed.cells.mapIndexed { i, cell ->
                    SlideRenderer.TileRequest(files[placed.order[i]], 0, null, null, cell, label = listOf("Anna · Yesterday", "Oma · 3 days ago")[i], caption = null)
                }
                SlideRenderer().renderTiles(tiles, w, h, 1f, CornerReserve.None).asImageBitmap()
            }
            Image(slide, null, Modifier.fillMaxSize())
            SplitDashboard(
                states = widgets, clock = "14:20", date = "Thursday 22 October", weather = WeatherFixtures.gent(), nowMs = CalendarFixtures.nowMs,
                unit = "c", calendar = if (calendar) CalendarFixtures.data() else null, zone = CalendarFixtures.zone, h24 = true,
                onWidget = {}, onEvent = {}, onClock = {}, onWeather = {},
                modifier = Modifier.inSplitCell(cells.dashboard, maxWidth, maxHeight),
            )
        }
    }

    /** The Denver's: the weather station (all values) and the P1 dial. */
    private val two get() = listOf(
        previewInstance("a", view("weather-station-rows"), Slot.TopLeft, name = "Station"),
        previewInstance("b", view("gauge-dial-using"), Slot.TopRight, name = "P1"),
    )

    private val three get() = two + previewInstance("c", view("gauge-car"), Slot.TopRight, name = "Car")

    @Test fun landscapeRight() = shot("landscape_right", BandSide.Right, two)

    @Test fun landscapeLeftNoCalendar() = shot("landscape_left_no_calendar", BandSide.Left, two, calendar = false)

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun portraitRight() = shot("portrait_right", BandSide.Right, two)

    @Test fun landscapeThree() = shot("landscape_three", BandSide.Right, three)

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun portraitThree() = shot("portrait_three", BandSide.Left, three)

    @Test fun landscapeOnlyCalendar() = shot("landscape_only_calendar", BandSide.Right, emptyList())
}
