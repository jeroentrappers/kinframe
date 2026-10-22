package app.kinframe.frame.removable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kinframe.frame.FrameController
import app.kinframe.frame.kiosk.OwnerGate
import app.kinframe.frame.ui.Format

/** Opens the storage panel; exporting the whole library is owner business, so the owner PIN guards it when set. */
fun FrameController.openRemovable() = gate.authorize(OwnerGate.Reason.ExternalStorage) { removable.open() }

/** Cards on the slideshow: restore offer, "stick connected", or the background backup pill. */
@Composable
fun RemovableSlideshowCards(c: FrameController, hidden: Boolean, modifier: Modifier = Modifier) {
    val r = c.removable
    val volumes by r.volumes.collectAsState()
    val task by r.task.collectAsState()
    val offer by r.restoreOffer.collectAsState()
    val open by r.panelOpen.collectAsState()
    var dismissed by remember { mutableStateOf<Set<String>>(emptySet()) }
    if (hidden || open) return
    val v = volumes.firstOrNull()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val t = task
        when {
            t is StorageTask.Exporting && t.backup -> BackupPill((t.progress.fraction * 100).toInt())
            offer != null -> offer?.let { o ->
                RestoreCard(o.count, o.frameName, onRestore = { c.gate.authorize(OwnerGate.Reason.ExternalStorage) { r.pickRestore(o) } }, onNotNow = { r.dismissRestore() })
            }
            v != null && v.path.path !in dismissed -> AnimatedVisibility(true, enter = fadeIn() + slideInVertically { -it / 3 }) {
                RemovableCard(v.kind, onOpen = { c.openRemovable() }, onDismiss = { dismissed = dismissed + v.path.path })
            }
        }
    }
}

/** The full-screen storage panel over the slideshow. */
@Composable
fun RemovableHost(c: FrameController) {
    val r = c.removable
    val open by r.panelOpen.collectAsState()
    val volumes by r.volumes.collectAsState()
    val task by r.task.collectAsState()
    val auto by r.autoBackup.collectAsState()
    val ctx = LocalContext.current
    AnimatedVisibility(open, enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 }, exit = fadeOut(tween(200))) {
        val v = when (val t = task) {
            is StorageTask.Exporting -> t.volume
            is StorageTask.Exported -> t.volume
            is StorageTask.Picking -> t.volume
            is StorageTask.Importing -> t.volume
            is StorageTask.Imported -> t.volume
            is StorageTask.Problem -> t.volume ?: volumes.firstOrNull()
            else -> volumes.firstOrNull()
        }
        val last = v?.let { r.lastBackupAt(it) }?.let { Format.relativeDate(ctx, it, c.g.clock.nowMs(), c.g.clock.zone) }
        RemovablePanel(
            PanelState(v, if (v == null && task == StorageTask.Idle) StorageTask.Problem(null, StorageTask.Problem.Kind.NothingFound) else task, auto, last, v != null && r.canEject(v)),
            PanelActions(
                onClose = { r.close() },
                onExport = { v?.let(r::export) },
                onPickImport = { v?.let(r::pickImport) },
                onAutoBackup = r::setAutoBackup,
                onEject = { v?.let(r::eject) },
                onCancel = r::cancel,
                onDone = { r.dismissResult(); if (v == null) r.close() },
                onAllowAccess = { v?.let(r::requestAccess) },
                onImport = { items, restore -> v?.let { r.import(it, items, restore) } },
            ),
        )
    }
}
