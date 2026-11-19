package app.kinframe.frame.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.PowerCutNotice
import app.kinframe.frame.R
import kotlinx.coroutines.delay

/**
 * After the frame lost power without shutting down: a calm card (not a full-screen warning) saying that everything
 * was checked, with a tip for next time. A tap dismisses it, and it goes by itself after [PowerCutNotice.SHOW_MS].
 * Shown once the start-up splash has gone, so it doesn't count down behind it.
 */
@Composable
fun PowerCutCard(c: FrameController, modifier: Modifier = Modifier, alignment: Alignment = Alignment.TopStart, hidden: Boolean = false) {
    val notice by c.powerCut.collectAsState()
    val splashUp by c.splashUp.collectAsState()
    val visible = notice != null && !splashUp && !hidden
    LaunchedEffect(visible) {
        if (visible) {
            delay(PowerCutNotice.SHOW_MS)
            c.dismissPowerCut()
        }
    }
    Box(modifier, contentAlignment = alignment) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut() + slideOutVertically { -it / 3 }) {
            val n = notice ?: PowerCutNotice(redownload = false)
            PowerCutCardView(n, onDismiss = { c.dismissPowerCut() })
        }
    }
}

@Composable
fun PowerCutCardView(n: PowerCutNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tip = stringResource(
        R.string.power_cut_tip, stringResource(R.string.menu_power), stringResource(R.string.power_off), stringResource(R.string.safe_title),
    )
    val checked = stringResource(if (n.redownload) R.string.power_cut_body_redownload else R.string.power_cut_body_ok)
    StatusCard(
        KinIcons.Power, stringResource(R.string.power_cut_title), "$checked $tip", Kin.colors.sage, onDismiss,
        modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}
