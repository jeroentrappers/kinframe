package app.kinframe.frame.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.PowerCutNotice
import app.kinframe.frame.Screenshots
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The card after a power cut, over a photo, to `frame/build/screenshots/` for review (records, not goldens). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class PowerCutCardScreenshotTest {
    @androidx.compose.runtime.Composable
    private fun cards() {
        FakePhoto(1)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PowerCutCardView(PowerCutNotice(redownload = false), onDismiss = {})
            PowerCutCardView(PowerCutNotice(redownload = true), onDismiss = {})
        }
    }

    @Test fun english() = Screenshots.shot("power_cut_card") { cards() }

    @Test @Config(qualifiers = "de-w1280dp-h800dp-land-mdpi")
    fun german() = Screenshots.shot("power_cut_card_de") { cards() }

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-land-mdpi")
    fun arabic() = Screenshots.shot("power_cut_card_ar") { cards() }
}
