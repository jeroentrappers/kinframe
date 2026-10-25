package app.kinframe.frame.frameo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeM
import app.kinframe.frame.FrameController
import app.kinframe.frame.R

/** The import's card on the slideshow: progress with Stop while it runs, then a summary. Nothing when idle. */
@Composable
fun FrameoSlideshowCard(c: FrameController, hidden: Boolean, modifier: Modifier = Modifier) {
    val state by c.frameo.state.collectAsState()
    val s = state
    AnimatedVisibility(!hidden && (s is FrameoState.Running || s is FrameoState.Done), modifier, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut()) {
        when (s) {
            // Stopping declines the automatic import: owner business, so the owner PIN guards it when set.
            is FrameoState.Running -> FrameoProgressCard(s, onStop = { c.gate.authorize(app.kinframe.frame.kiosk.OwnerGate.Reason.ExternalStorage) { c.frameo.stop() } })
            is FrameoState.Done -> FrameoDoneCard(s.outcome, onDone = { c.frameo.dismissResult() })
            else -> Unit
        }
    }
}

@Composable
private fun CardShell(content: @Composable () -> Unit) {
    Row(
        Modifier.width(440.dp).clip(KinShapeM).background(Kin.colors.surface.copy(alpha = 0.97f)).border(1.dp, Kin.colors.line, KinShapeM).padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Kin.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            KinIcon(KinIcons.Download, null, tint = Kin.colors.accent, size = 24.dp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp)) { content() }
    }
}

@Composable
internal fun FrameoProgressCard(s: FrameoState.Running, onStop: () -> Unit) = CardShell {
    val p = s.progress
    val shown by animateFloatAsState(p.fraction, label = "frameo")
    Text(stringResource(R.string.frameo_importing), style = Kin.type.bodyStrong, color = Kin.colors.ink)
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(Kin.colors.paperDeep)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(Kin.colors.accent))
    }
    Text(
        if (s.waitingForNetwork) stringResource(R.string.frameo_waiting)
        else if (p.total > 0) stringResource(R.string.frameo_count, p.done, p.total) else stringResource(R.string.frameo_preparing),
        style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 6.dp),
    )
    KinButton(stringResource(R.string.frameo_stop), onStop, Modifier.padding(top = 10.dp), style = KinButtonStyle.Quiet)
}

@Composable
internal fun FrameoDoneCard(o: FrameoImporter.Outcome, onDone: () -> Unit) = CardShell {
    Text(stringResource(R.string.frameo_done_title), style = Kin.type.bodyStrong, color = Kin.colors.ink)
    for (line in outcomeLines(o)) Text(line, style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 4.dp))
    KinButton(stringResource(R.string.action_close), onDone, Modifier.padding(top = 12.dp), style = KinButtonStyle.Secondary)
}

@Composable
private fun outcomeLines(o: FrameoImporter.Outcome): List<String> = buildList {
    add(stringResource(R.string.frameo_done_imported, o.imported))
    if (o.alreadyThere > 0) add(stringResource(R.string.frameo_done_already, o.alreadyThere))
    if (o.failed > 0) add(stringResource(R.string.frameo_done_failed, o.failed))
}

/** "Photos from Frameo" in the frame settings (owner-gated): shown when the frame has Frameo files. */
@Composable
fun FrameoSettingsSection(c: FrameController, card: @Composable (String, @Composable () -> Unit) -> Unit) {
    val state by c.frameo.state.collectAsState()
    val s = state
    if (s is FrameoState.Idle && s.found == 0) return
    card(stringResource(R.string.frameo_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s) {
                is FrameoState.Idle -> {
                    Text(stringResource(R.string.frameo_found, s.found), style = Kin.type.body, color = Kin.colors.inkSoft)
                    KinButton(stringResource(R.string.frameo_import), { c.frameo.importNow() })
                }
                is FrameoState.Running -> {
                    val p = s.progress
                    Text(
                        if (s.waitingForNetwork) stringResource(R.string.frameo_waiting)
                        else if (p.total > 0) stringResource(R.string.frameo_count, p.done, p.total) else stringResource(R.string.frameo_preparing),
                        style = Kin.type.body, color = Kin.colors.inkSoft,
                    )
                    KinButton(stringResource(R.string.frameo_stop), { c.frameo.stop() }, style = KinButtonStyle.Secondary)
                }
                is FrameoState.Done -> {
                    outcomeLines(s.outcome).forEach { Text(it, style = Kin.type.body, color = Kin.colors.inkSoft) }
                    KinButton(stringResource(R.string.action_close), { c.frameo.dismissResult() }, style = KinButtonStyle.Secondary)
                }
                FrameoState.NoPermission -> {
                    Text(stringResource(R.string.frameo_no_permission), style = Kin.type.body, color = Kin.colors.inkSoft)
                    KinButton(stringResource(R.string.action_try_again), { c.frameo.importNow() }, style = KinButtonStyle.Secondary)
                }
            }
        }
    }
}
