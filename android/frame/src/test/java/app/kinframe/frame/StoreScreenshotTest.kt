package app.kinframe.frame

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinframeTheme
import app.kinframe.frame.setup.CelebrateStep
import app.kinframe.frame.setup.CodeReadyContent
import app.kinframe.frame.setup.StepScaffold
import app.kinframe.frame.slideshow.Avatars
import app.kinframe.frame.slideshow.Collage
import app.kinframe.frame.slideshow.CornerReserve
import app.kinframe.frame.slideshow.CornerWidgets
import app.kinframe.frame.slideshow.QuickMenuContent
import app.kinframe.frame.slideshow.SlideRenderer
import app.kinframe.frame.ui.Format
import app.kinframe.frame.weather.ForecastOverlay
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.ZoneId

/**
 * The Google Play screenshots of the frame app (docs/play-frame.md): the real slideshow, quick menu, forecast and setup
 * composables with the CC0 / public-domain photos of `ios/tools/storeshots/photos` (CREDITS.md there), in English and
 * Dutch, on a 10-inch (1280 × 800 dp, xhdpi) and a 7-inch (1024 × 600 dp, hdpi) tablet. Written to
 * `frame/build/screenshots/store/<ten|seven>-<lang>-<scene>.png`; `android/tools/storeshots/compose-frame.mjs` adds the
 * headlines and writes the listing images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class StoreScreenshotTest {
    private val photos = File("../../ios/tools/storeshots/photos")
    private val zone = ZoneId.of("Europe/Brussels")
    private val now = WeatherFixtures.nowMs
    private val day = 24 * 3600_000L

    private val devices = mapOf("ten" to "w1280dp-h800dp-land-xhdpi", "seven" to "w1024dp-h600dp-land-hdpi")

    /** The family's words on the frame, per listing language. */
    private class Copy(
        val lang: String,
        val qualifier: String,
        val h24: Boolean,
        val frameName: String,
        val wifi: String,
        val tulips: String,
        val sailing: String,
        val breakfast: String,
    )

    private val copy = mapOf(
        "en" to Copy("en", "en-rUS", h24 = false, "Grandma’s frame", "Home", "The tulips are out!", "Sailing with Grandpa this morning", "Sunday breakfast"),
        "nl" to Copy("nl", "nl", h24 = true, "Fotolijst van oma", "Thuis", "De tulpen staan in bloei!", "Vanochtend zeilen met opa", "Zondagsontbijt"),
    )

    /** The slideshow: a photo with caption and corner, the quick menu, a pair and (10-inch) the forecast. */
    @Test fun slideshow() = each { c, device, name ->
        shot(name("photo")) { Slide(c, "tulip-field", c.tulips, "Emma", now - 2 * 3600_000L) }
        shot(name("menu")) {
            Slide(c, "sailboat", c.sailing, "Lucas", now - day)
            QuickMenuContent(
                visible = true, compact = false, frameName = c.frameName,
                status = stringResource(R.string.status_online, c.wifi), wifiIcon = KinIcons.Wifi, hasPhoto = true,
                reaction = "love", onTouch = {}, onReact = {}, onHide = {}, onDelete = {}, onPower = {}, onSettings = {},
            )
        }
        shot(name("pair")) { Pair(c) }
        // The forecast sheet needs the 10-inch width: at 1024 dp its header wraps letter by letter.
        if (device == "ten") shot(name("forecast")) {
            Slide(c, "mountain-lake", null, null, null, corner = false)
            ForecastOverlay(visible = true, weather = WeatherFixtures.gent(), nowMs = now, unit = "c", h24 = c.h24, onClose = {})
        }
    }

    /**
     * The setup's frame code and (7-inch, in place of the forecast) the welcome once someone claimed it. A test of its
     * own and not settled: after the clock has moved on, their endless animations keep Robolectric's frame loop busy
     * for minutes.
     */
    @Test fun setup() = each { _, device, name ->
        shot(name("code"), settle = 0) {
            Paper()
            StepScaffold(index = 5, onBack = null, primary = null) {
                CodeReadyContent(
                    Format.code("48213907"), "https://kinframe.appmire.be/f/48213907", "kinframe.appmire.be",
                    Format.remaining(LocalContext.current, 83 * 60_000L), landscape = true,
                )
            }
        }
        if (device == "seven") shot(name("celebrate"), settle = 0) { CelebrateStep("Emma") }
    }

    private fun each(block: (Copy, String, (String) -> String) -> Unit) {
        for ((device, size) in devices) for ((lang, c) in copy) {
            RuntimeEnvironment.setQualifiers("${c.qualifier}-$size")
            block(c, device) { scene -> "store/$device-$lang-$scene" }
        }
    }

    /** Like [Screenshots.shot], destroying the activity afterwards. [settle]: 100 ms steps for entry animations. */
    private fun shot(name: String, settle: Int = 20, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        activity.setContent { KinframeTheme { Box(Modifier.fillMaxSize()) { content() } } }
        shadowOf(Looper.getMainLooper()).idle()
        repeat(settle) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/screenshots/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
        controller.pause().stop().destroy()
    }

    @Composable
    private fun Corner(c: Copy, onBlockSize: ((Float, Float) -> Unit)? = null) {
        val locale = Locales.toLocale(c.lang)
        CornerWidgets(
            Format.clock(now, zone, c.h24, locale), Format.longDate(now, zone, locale), WeatherFixtures.gent(), now, "c",
            onPaper = false, onBlockSize = onBlockSize,
        ) {}
    }

    /** One photo as the slideshow renders it: caption and sender drawn into the slide, clear of the [corner] widgets. */
    @Composable
    private fun Slide(c: Copy, photo: String, caption: String?, sender: String?, sentMs: Long?, corner: Boolean = true) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        var reserve by remember { mutableStateOf<CornerReserve?>(null) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val r = reserve ?: CornerReserve.estimate(clockOrDate = true, weather = true)
            val spec = sender?.let {
                SlideRenderer.CaptionSpec(
                    text = caption, meta = "$it · " + Format.relativeDate(context, sentMs ?: now, now, zone), avatar = null,
                    initials = Avatars.initials(it), avatarColor = Avatars.color(it.lowercase()), corner = r,
                )
            }
            val img = remember(r) {
                SlideRenderer().render(
                    SlideRenderer.Request(
                        File(photos, "$photo.jpg"), isVideo = false, screenW = w, screenH = h, fill = true, focalX = null, focalY = null,
                        rotation = 0, blurBars = true, caption = spec, density = density,
                    ),
                ).asImageBitmap()
            }
            Image(img, null, Modifier.fillMaxSize())
            if (corner) Corner(c, onBlockSize = { bw, bh -> reserve = CornerReserve.quantized(bw, bh) })
        }
    }

    /** Two portrait photos side by side, as the slideshow pairs them on a landscape screen. */
    @Composable
    private fun Pair(c: Copy) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        var reserve by remember { mutableStateOf<CornerReserve?>(null) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            reserve?.let { r ->
                val img = remember(r) {
                    val files = listOf(File(photos, "pancakes.jpg"), File(photos, "strawberries.jpg"))
                    val labels = listOf(
                        "Oma · " + Format.relativeDate(context, now - 3 * day, now, zone),
                        "Noah · " + Format.relativeDate(context, now - day, now, zone),
                    )
                    val tiles = Collage.cells(2, landscapeScreen = true).mapIndexed { i, cell ->
                        SlideRenderer.TileRequest(files[i], 0, null, null, cell, label = labels[i], caption = c.breakfast.takeIf { i == 0 })
                    }
                    SlideRenderer().renderTiles(tiles, w, h, density, r).asImageBitmap()
                }
                Image(img, null, Modifier.fillMaxSize())
            }
            Corner(c, onBlockSize = { bw, bh -> reserve = CornerReserve.quantized(bw, bh) })
        }
    }
}
