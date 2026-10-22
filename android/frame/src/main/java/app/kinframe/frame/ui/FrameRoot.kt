package app.kinframe.frame.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.kinframe.core.design.KinframeTheme
import app.kinframe.frame.AppScreen
import app.kinframe.frame.FrameController
import app.kinframe.frame.kiosk.OwnerPinPrompt
import app.kinframe.frame.setup.SetupFlow
import app.kinframe.frame.slideshow.SlideshowScreen
import kotlinx.coroutines.launch

@Composable
fun FrameRoot(c: FrameController) {
    val screen by c.screen.collectAsState()
    val settings by c.g.settings.settings.collectAsState()
    KinframeTheme(textScale = settings.display.textScale.toFloat().coerceIn(1f, 1.3f)) {
        Crossfade(
            targetState = screen::class, animationSpec = tween(500), label = "screen",
            modifier = Modifier.fillMaxSize(),
        ) { kind ->
            when (kind) {
                AppScreen.Setup::class -> SetupFlow(c.setup)
                AppScreen.Slideshow::class -> SlideshowScreen(c)
                AppScreen.Recovery::class -> RecoveryScreen(c)
                AppScreen.ShuttingDown::class -> ShuttingDownScreen(restarting = false)
                AppScreen.Restarting::class -> ShuttingDownScreen(restarting = true)
                AppScreen.SafeToUnplug::class -> SafeToUnplugScreen((screen as? AppScreen.SafeToUnplug)?.rootRemounted ?: false) {
                    c.scope.launch { c.power.restart() }
                }
                else -> Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
        UpdateNotice(c.update)
        OwnerPinPrompt(c)
    }
}
