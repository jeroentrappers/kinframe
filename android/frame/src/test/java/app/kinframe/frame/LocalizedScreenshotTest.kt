package app.kinframe.frame

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinframeTheme
import app.kinframe.frame.setup.CelebrateStep
import app.kinframe.frame.setup.CodeReadyContent
import app.kinframe.frame.setup.LanguageStepContent
import app.kinframe.frame.setup.ServerCheck
import app.kinframe.frame.setup.ServerStepContent
import app.kinframe.frame.setup.SetupState
import app.kinframe.frame.setup.SetupStep
import app.kinframe.frame.setup.StepScaffold
import app.kinframe.frame.slideshow.CornerWidgets
import app.kinframe.frame.slideshow.EmptyMessage
import app.kinframe.frame.slideshow.EmptyState
import app.kinframe.frame.slideshow.NewPhotosPillContent
import app.kinframe.frame.slideshow.QuickMenuContent
import app.kinframe.frame.slideshow.Snack
import app.kinframe.frame.slideshow.UndoSnackbar
import app.kinframe.frame.ui.Format
import app.kinframe.frame.ui.PowerDialogContent
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
import java.time.ZoneId

/**
 * Renders the setup wizard and the slideshow overlays in languages that stress the layout — Arabic (right to left),
 * Japanese (CJK line breaking), German and Russian (long words) — on the frame's 1024 × 600 screen, to PNGs in
 * `frame/build/screenshots/l10n/` for review (records, not golden comparisons).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class LocalizedScreenshotTest {
    private val languages = listOf("ar", "ja", "de", "ru", "en")
    private val now = WeatherFixtures.nowMs

    @Test fun setupLanguage() = each("setup_language") { lang ->
        StepScaffold(index = 0, onBack = null, primary = { KinButton(stringResource(R.string.action_continue), {}) }) {
            LanguageStepContent(lang) {}
        }
    }

    @Test fun setupServer() = each("setup_server") {
        val s = SetupState(SetupStep.Server, useCustomServer = true, customUrl = "https://frames.example.org", check = ServerCheck.Failed(ServerCheck.Reason.Unreachable))
        StepScaffold(index = 2, onBack = {}, primary = { KinButton(stringResource(R.string.server_check), {}) }) {
            ServerStepContent(s, {}, {}, {})
        }
    }

    @Test fun setupCode() = each("setup_code") {
        StepScaffold(index = 3, onBack = null, primary = null) {
            CodeReadyContent(Format.code("K7Q4PX2M"), "https://kinframe.example.org/f/K7Q4PX", "kinframe.example.org", "59 min", landscape = true)
        }
    }

    @Test fun setupCelebrate() = each("setup_celebrate") { CelebrateStep("Maximiliaan") }

    @Test fun slideshowOverlays() = each("slideshow_overlays") { lang ->
        Photo()
        val locale = Locales.toLocale(lang)
        CornerWidgets(
            Format.clock(now, ZoneId.of("Europe/Brussels"), true, locale),
            Format.longDate(now, ZoneId.of("Europe/Brussels"), locale),
            WeatherFixtures.gent(), now, "c", onPaper = false,
        ) {}
        QuickMenuContent(
            visible = true, compact = false, frameName = "Maximiliaan", status = stringResource(R.string.status_online, "Woonkamer-5G"),
            wifiIcon = KinIcons.Wifi, hasPhoto = true, reaction = "love",
            onTouch = {}, onReact = {}, onHide = {}, onDelete = {}, onPower = {}, onSettings = {},
        )
        Box(Modifier.fillMaxSize().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) { NewPhotosPillContent(3) {} }
        Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            UndoSnackbar(Snack(R.string.snack_deleted, "p", 1, hidden = false), Modifier, {}, {})
        }
    }

    @Test fun powerDialog() = each("power_dialog") {
        Photo()
        PowerDialogContent({}, {}, {}, {})
    }

    @Test fun emptyDownloading() = each("empty_downloading") {
        Box(Modifier.fillMaxSize().background(Kin.colors.paper).padding(72.dp)) { EmptyMessage(EmptyState.Downloading(23)) }
    }

    private fun each(name: String, content: @Composable (lang: String) -> Unit) {
        for (lang in languages) {
            val qualifier = Locales.toLocale(lang).let { if (it.country.isNotEmpty()) "${it.language}-r${it.country}" else it.language }
            RuntimeEnvironment.setQualifiers("$qualifier-w1024dp-h600dp-land-mdpi")
            shot("${name}_$lang") { content(lang) }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        activity.setContent { KinframeTheme { Box(Modifier.fillMaxSize().background(Kin.colors.paper)) { content() } } }
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/screenshots/l10n/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
        controller.pause().stop().destroy()
    }
}

@Composable
private fun Photo() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to Color(0xFF7FA7C9), 0.6f to Color(0xFFD9C7A5), 1f to Color(0xFF6B5B45)))
    }
}
