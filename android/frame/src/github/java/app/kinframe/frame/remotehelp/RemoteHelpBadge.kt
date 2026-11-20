package app.kinframe.frame.remotehelp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.StatusPill
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.kiosk.OwnerGate
import app.kinframe.frame.ui.Format
import java.util.Locale

/**
 * "Remote help is on until 18:00" in a corner, on top of every screen, while a session runs (docs/spec/remote-help.md
 * §5.4 step 6). Tapping it asks "The kinframe operator can look at this frame to help. Stop?"; Stop asks for the
 * owner PIN when one is set.
 */
@Composable
fun RemoteHelpBadge(c: FrameController) {
    val endsAt by c.remoteHelp.badge.collectAsState()
    val until = endsAt ?: return
    val settings by c.g.settings.settings.collectAsState()
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val time = Format.clock(until, c.g.clock.zone, settings.widgets.clock24h, locale)
    var asking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomStart) {
        StatusPill(
            stringResource(R.string.remote_help_badge, time),
            Modifier.clickable { asking = true },
            dot = Kin.colors.sun,
        )
    }
    if (asking) {
        StopRemoteHelpDialog(
            onStop = {
                asking = false
                c.gate.authorize(OwnerGate.Reason.StopRemoteHelp) { c.remoteHelp.stopHere() }
            },
            onKeep = { asking = false },
        )
    }
}

@Composable
internal fun StopRemoteHelpDialog(onStop: () -> Unit, onKeep: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onKeep), contentAlignment = Alignment.Center) {
        KinCard(Modifier.widthIn(max = 640.dp).clickable(enabled = false) {}, padding = 32.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.remote_help_title), style = Kin.type.headline, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.remote_help_stop_question), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KinButton(stringResource(R.string.remote_help_keep), onKeep, style = KinButtonStyle.Secondary)
                    KinButton(stringResource(R.string.remote_help_stop), onStop, style = KinButtonStyle.Danger)
                }
            }
        }
    }
}
