package app.kinframe.frame.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCREEN_FADE_MS = 500

@Composable
fun FrameRoot(c: FrameController) {
    val screen by c.screen.collectAsState()
    val settings by c.g.settings.settings.collectAsState()
    val splashUp by c.splashUp.collectAsState()
    val step by c.startup.collectAsState()
    // Leaving the start-up splash for another screen than the slideshow (which fades the splash out over its first
    // photo itself): the splash is gone once the cross-fade below has run.
    LaunchedEffect(screen::class) {
        if (splashUp && screen != AppScreen.Loading && screen != AppScreen.Slideshow) {
            delay(SCREEN_FADE_MS + 100L)
            c.onSplashGone()
        }
    }
    KinframeTheme(textScale = settings.display.textScale.toFloat().coerceIn(1f, 1.3f)) {
        Crossfade(
            targetState = screen::class, animationSpec = tween(SCREEN_FADE_MS), label = "screen",
            // While the splash is up the window keeps its background (MainActivity), the splash's colour: a cross-fade
            // never lets black show through.
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
                AppScreen.Loading::class -> BootSplash(step)
                else -> Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
        if (screen == AppScreen.Setup) PowerCutCard(c, Modifier.fillMaxSize().padding(24.dp), Alignment.TopEnd)
        UpdateNotice(c.update)
        val portalOpen by c.portalOpen.collectAsState()
        if (portalOpen) CaptivePortalScreen(c)
        app.kinframe.frame.remotehelp.RemoteHelpBadge(c)
        OwnerPinPrompt(c)
    }
}
