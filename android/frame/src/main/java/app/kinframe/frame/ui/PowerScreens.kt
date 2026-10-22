package app.kinframe.frame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.kiosk.OwnerGate
import kotlinx.coroutines.launch

/** Power menu (product spec 4.7): sleep now, restart, power off. */
@Composable
fun PowerDialog(c: FrameController, onDismiss: () -> Unit) = PowerDialogContent(
    onSleep = { c.display.sleepNow() },
    onRestart = { c.gate.authorize(OwnerGate.Reason.Restart) { c.scope.launch { c.power.restart() } } },
    onPowerOff = { c.gate.authorize(OwnerGate.Reason.PowerOff) { c.scope.launch { c.power.powerOff() } } },
    onDismiss = onDismiss,
)

@Composable
internal fun PowerDialogContent(onSleep: () -> Unit, onRestart: () -> Unit, onPowerOff: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        KinCard(Modifier.clickable(enabled = false) {}, padding = 32.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.power_title), style = Kin.type.headline)
                Spacer(Modifier.height(28.dp))
                val options = @Composable { compact: Boolean ->
                    PowerOption(KinIcons.Moon, stringResource(R.string.power_sleep), stringResource(R.string.power_sleep_body), compact) {
                        onDismiss(); onSleep()
                    }
                    PowerOption(KinIcons.Restart, stringResource(R.string.power_restart), stringResource(R.string.power_restart_body), compact) {
                        onDismiss(); onRestart()
                    }
                    PowerOption(KinIcons.Power, stringResource(R.string.power_off), stringResource(R.string.power_off_body), compact) {
                        onDismiss(); onPowerOff()
                    }
                }
                androidx.compose.foundation.layout.BoxWithConstraints {
                    if (maxWidth >= 860.dp) {
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(20.dp)) { options(false) }
                    } else {
                        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { options(true) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                KinButton(stringResource(R.string.action_cancel), onDismiss, style = KinButtonStyle.Quiet)
            }
        }
    }
}

@Composable
private fun PowerOption(icon: ImageVector, title: String, body: String, compact: Boolean, onClick: () -> Unit) {
    if (compact) {
        KinCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(Kin.colors.accentSoft), contentAlignment = Alignment.Center) {
                    KinIcon(icon, null, tint = Kin.colors.accent, size = 30.dp)
                }
                Column(Modifier.padding(start = 18.dp)) {
                    Text(title, style = Kin.type.title)
                    Text(body, style = Kin.type.caption, color = Kin.colors.inkSoft)
                }
            }
        }
        return
    }
    KinCard(Modifier.width(250.dp).fillMaxHeight(), onClick = onClick, padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Kin.colors.accentSoft), contentAlignment = Alignment.Center) {
                KinIcon(icon, null, tint = Kin.colors.accent, size = 34.dp)
            }
            Text(title, style = Kin.type.title, modifier = Modifier.padding(top = 16.dp))
            Text(body, style = Kin.type.caption, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun ShuttingDownScreen(restarting: Boolean) {
    Box(Modifier.fillMaxSize().background(Kin.colors.night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(52.dp), color = Kin.colors.accentSoft, strokeWidth = 3.dp)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(if (restarting) R.string.restarting_title else R.string.shutting_down_title), style = Kin.type.headline, color = Color.White)
            Text(stringResource(R.string.shutting_down_body), style = Kin.type.body, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Shown until the plug is pulled (kinframe-additions.md). */
@Composable
fun SafeToUnplugScreen(rootRemounted: Boolean, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kin.colors.night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(Kin.colors.sage.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.Check, null, tint = Kin.colors.sageSoft, size = 60.dp)
            }
            Spacer(Modifier.height(36.dp))
            Text(stringResource(R.string.safe_title), style = Kin.type.display, color = Color.White, textAlign = TextAlign.Center)
            Text(
                stringResource(if (rootRemounted) R.string.safe_body else R.string.safe_body_noroot),
                style = Kin.type.body, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, start = 80.dp, end = 80.dp),
            )
            Spacer(Modifier.height(40.dp))
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onRestart).padding(horizontal = 28.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KinIcon(KinIcons.Restart, null, tint = Color.White.copy(alpha = 0.8f), size = 22.dp)
                Text(stringResource(R.string.power_restart), style = Kin.type.label, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
fun RecoveryScreen(c: FrameController) {
    Box(Modifier.fillMaxSize().background(Kin.colors.paper).padding(64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Kin.colors.accentSoft), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.Info, null, tint = Kin.colors.accent, size = 48.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.recovery_title), style = Kin.type.headline, textAlign = TextAlign.Center)
            Text(stringResource(R.string.recovery_body), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, start = 60.dp, end = 60.dp))
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KinButton(stringResource(R.string.action_try_again), { c.recoverTryAgain() })
                KinButton(stringResource(R.string.recovery_clear), { c.recoverClearCache() }, style = KinButtonStyle.Secondary)
                KinButton(stringResource(R.string.power_restart), { c.scope.launch { c.power.restart() } }, style = KinButtonStyle.Quiet)
            }
        }
    }
}
