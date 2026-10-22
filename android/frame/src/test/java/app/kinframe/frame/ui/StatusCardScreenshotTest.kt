package app.kinframe.frame.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class StatusCardScreenshotTest {
    private fun all() = shot("status_cards_landscape") {
        FakePhoto()
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FrameStatus.entries.forEach { FrameStatusCard(it, {}, {}) }
        }
    }

    @Test fun cardsLandscape() = all()

    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi")
    fun cardsPortraitDutch() = shot("status_cards_portrait_nl") {
        FakePhoto(1)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FrameStatus.entries.forEach { FrameStatusCard(it, {}, {}) }
        }
    }
}
