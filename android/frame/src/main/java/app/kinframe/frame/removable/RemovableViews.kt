package app.kinframe.frame.removable

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeM
import app.kinframe.frame.R
import app.kinframe.frame.ui.StatusCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------------------------- cards on the slideshow

/** "USB stick connected": the calm card that leads to the storage panel. */
@Composable
fun RemovableCard(kind: Volume.Kind, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    StatusCard(
        KinIcons.Storage,
        stringResource(if (kind == Volume.Kind.Usb) R.string.removable_card_usb_title else R.string.removable_card_sd_title),
        stringResource(R.string.removable_card_body),
        Kin.colors.sage, onDismiss, modifier, action = stringResource(R.string.removable_card_open), onAction = onOpen,
    )
}

/** Offered on a frame with an empty library when a stick holds a kinframe export or backup. */
@Composable
fun RestoreCard(count: Int, frameName: String?, onRestore: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.width(440.dp).clip(KinShapeM).background(Kin.colors.surface).border(1.dp, Kin.colors.line, KinShapeM).padding(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconDot(KinIcons.Download, Kin.colors.accent)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(stringResource(R.string.restore_card_title), style = Kin.type.bodyStrong, color = Kin.colors.ink)
            if (frameName != null) {
                Text(stringResource(R.string.restore_card_from, frameName), style = Kin.type.caption, color = Kin.colors.inkFaint, modifier = Modifier.padding(top = 2.dp))
            }
            Text(pluralStringResource(R.plurals.restore_card_body, count, count), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KinButton(stringResource(R.string.restore_action), onRestore)
                KinButton(stringResource(R.string.restore_not_now), onNotNow, style = KinButtonStyle.Quiet)
            }
        }
    }
}

/** Small pill while an automatic backup runs in the background. */
@Composable
fun BackupPill(percent: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
        Text(stringResource(R.string.removable_backing_up_pill, percent), style = Kin.type.caption, color = Color.White)
    }
}

@Composable
private fun IconDot(icon: ImageVector, tone: Color, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        KinIcon(icon, null, tint = tone, size = (size * 0.55f).dp)
    }
}

// ---------------------------------------------------------------------------------------------- the panel

/** Everything the panel shows, so it renders from plain data (and in screenshot tests). */
data class PanelState(
    val volume: Volume?,
    val task: StorageTask,
    val autoBackup: Boolean,
    val lastBackupText: String?,
    val canEject: Boolean,
    val freeBytes: Long = volume?.freeBytes() ?: -1,
    val totalBytes: Long = volume?.totalBytes() ?: -1,
)

class PanelActions(
    val onClose: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onPickImport: () -> Unit = {},
    val onAutoBackup: (Boolean) -> Unit = {},
    val onEject: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onAllowAccess: () -> Unit = {},
    val onImport: (List<ImportCandidate>, Boolean) -> Unit = { _, _ -> },
)

@Composable
fun RemovablePanel(
    state: PanelState,
    actions: PanelActions,
    thumb: @Composable (ImportCandidate) -> Unit = { ImportThumb(it) },
) {
    val kind = state.volume?.kind ?: Volume.Kind.Usb
    Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        when (val t = state.task) {
            is StorageTask.Picking -> ImportPicker(t.candidates, t.restore, onCancel = actions.onDone, onImport = { actions.onImport(it, t.restore) }, thumb = thumb)
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = actions.onClose), contentAlignment = Alignment.Center) {
                        KinIcon(KinIcons.ChevronLeft, stringResource(R.string.action_back))
                    }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            state.volume?.label ?: stringResource(if (kind == Volume.Kind.Usb) R.string.removable_title_usb else R.string.removable_title_sd),
                            style = Kin.type.headline,
                        )
                        if (state.freeBytes >= 0 && state.totalBytes > 0) {
                            val ctx = LocalContext.current
                            Text(
                                stringResource(R.string.removable_free, Formatter.formatShortFileSize(ctx, state.freeBytes), Formatter.formatShortFileSize(ctx, state.totalBytes)),
                                style = Kin.type.caption, color = Kin.colors.inkSoft,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                    when (t) {
                        StorageTask.Idle -> PanelHome(state, actions)
                        is StorageTask.Exporting -> ProgressView(
                            stringResource(if (t.backup) R.string.backup_progress_title else R.string.export_progress_title),
                            stringResource(R.string.export_progress_hint), t.progress, actions.onCancel,
                        )
                        is StorageTask.Importing -> ProgressView(
                            stringResource(if (t.restore) R.string.restore_progress_title else R.string.import_progress_title),
                            stringResource(R.string.import_progress_hint), t.progress, actions.onCancel,
                        )
                        StorageTask.Scanning -> Column(Modifier.padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(40.dp), color = Kin.colors.accent, strokeWidth = 3.dp)
                            Text(stringResource(R.string.import_scanning), style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 20.dp))
                        }
                        is StorageTask.Exported -> ExportDone(t, state.canEject, actions)
                        is StorageTask.Imported -> ImportDone(t, actions)
                        is StorageTask.Problem -> ProblemView(t, actions)
                        is StorageTask.Picking -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHome(state: PanelState, actions: PanelActions) {
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 1100.dp)) {
        val wide = maxWidth > 760.dp
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val export = @Composable { m: Modifier ->
                ActionTile(KinIcons.Storage, stringResource(R.string.removable_export_title), stringResource(R.string.removable_export_body),
                    stringResource(R.string.removable_export_action), actions.onExport, m)
            }
            val import = @Composable { m: Modifier ->
                ActionTile(KinIcons.Image, stringResource(R.string.removable_import_title), stringResource(R.string.removable_import_body),
                    stringResource(R.string.removable_import_action), actions.onPickImport, m, KinButtonStyle.Secondary)
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { export(Modifier.weight(1f)); import(Modifier.weight(1f)) }
            } else {
                export(Modifier.fillMaxWidth()); import(Modifier.fillMaxWidth())
            }
            KinCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconDot(KinIcons.Clock, Kin.colors.sage)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.removable_auto_title), style = Kin.type.bodyStrong)
                        Text(stringResource(R.string.removable_auto_body), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            state.lastBackupText?.let { stringResource(R.string.removable_last_backup, it) } ?: stringResource(R.string.removable_never_backed_up),
                            style = Kin.type.caption, color = Kin.colors.inkFaint, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Switch(
                        state.autoBackup, actions.onAutoBackup,
                        colors = SwitchDefaults.colors(checkedTrackColor = Kin.colors.accent, checkedThumbColor = Kin.colors.onAccent, uncheckedTrackColor = Kin.colors.paperDeep, uncheckedBorderColor = Kin.colors.line, uncheckedThumbColor = Kin.colors.inkFaint),
                    )
                }
            }
            EjectRow(state.canEject, actions.onEject)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, body: String, action: String, onClick: () -> Unit, modifier: Modifier, style: KinButtonStyle = KinButtonStyle.Primary) {
    KinCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconDot(icon, Kin.colors.accent, 52)
            Text(title, style = Kin.type.title)
            Text(body, style = Kin.type.body, color = Kin.colors.inkSoft)
            KinButton(action, onClick, Modifier.padding(top = 6.dp), style = style)
        }
    }
}

@Composable
private fun EjectRow(canEject: Boolean, onEject: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        KinIcon(KinIcons.Info, null, tint = Kin.colors.inkFaint, size = 22.dp)
        Text(stringResource(R.string.removable_safe_remove_hint), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        if (canEject) KinButton(stringResource(R.string.removable_eject), onEject, style = KinButtonStyle.Secondary)
    }
}

@Composable
private fun ProgressView(title: String, hint: String, p: Progress, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val shown by animateFloatAsState(p.fraction, label = "progress")
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconDot(KinIcons.Storage, Kin.colors.accent, 72)
        Text(title, style = Kin.type.headline, modifier = Modifier.padding(top = 24.dp))
        Box(Modifier.padding(top = 28.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(Kin.colors.paperDeep)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(Kin.colors.accent))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (p.total > 0) Text(stringResource(R.string.export_progress_count, p.done, p.total), style = Kin.type.label, color = Kin.colors.ink)
            Spacer(Modifier.weight(1f))
            if (p.bytesTotal > 0) {
                Text(
                    stringResource(R.string.export_progress_bytes, Formatter.formatShortFileSize(ctx, p.bytesDone), Formatter.formatShortFileSize(ctx, p.bytesTotal)),
                    style = Kin.type.caption, color = Kin.colors.inkSoft,
                )
            }
        }
        p.current?.let {
            Text(it, style = Kin.type.caption, color = Kin.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Text(hint, style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 28.dp))
        KinButton(stringResource(R.string.action_cancel), onCancel, Modifier.padding(top = 28.dp), style = KinButtonStyle.Secondary)
    }
}

@Composable
private fun ResultView(icon: ImageVector, tone: Color, title: String, lines: List<String>, buttons: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(top = 40.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        IconDot(icon, tone, 72)
        Text(title, style = Kin.type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        lines.forEach { Text(it, style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
    }
}

@Composable
private fun ExportDone(t: StorageTask.Exported, canEject: Boolean, actions: PanelActions) {
    val r = t.result
    val lines = buildList {
        add(pluralStringResource(R.plurals.export_done_copied, r.copied, r.copied))
        if (r.kept > 0) add(pluralStringResource(R.plurals.export_done_kept, r.kept, r.kept))
        val byReason = r.skipped.groupingBy { it.reason }.eachCount()
        byReason[SkipReason.NOT_ON_FRAME]?.let { add(pluralStringResource(R.plurals.export_skipped_not_on_frame, it, it)) }
        byReason[SkipReason.TOO_LARGE_FOR_FAT32]?.let { add(pluralStringResource(R.plurals.export_skipped_fat32, it, it)) }
        byReason[SkipReason.WRITE_FAILED]?.let { add(pluralStringResource(R.plurals.export_skipped_failed, it, it)) }
        // "kinframe-export" (relative to the stick) reads better than a mount path.
        add(stringResource(R.string.export_done_where, r.dir.removePrefix(t.volume.path.path).trimStart('/').ifEmpty { r.dir }))
        if (r.method == VolumeWriter.Method.AppFolder) add(stringResource(R.string.export_app_folder_note))
        add(stringResource(R.string.removable_safe_remove_hint))
    }
    ResultView(KinIcons.Check, Kin.colors.sage, stringResource(if (t.backup) R.string.backup_done_title else R.string.export_done_title), lines) {
        KinButton(stringResource(R.string.removable_done), actions.onDone)
        if (r.method == VolumeWriter.Method.AppFolder) KinButton(stringResource(R.string.removable_allow_access), actions.onAllowAccess, style = KinButtonStyle.Secondary)
        if (canEject) KinButton(stringResource(R.string.removable_eject), actions.onEject, style = KinButtonStyle.Secondary)
    }
}

@Composable
private fun ImportDone(t: StorageTask.Imported, actions: PanelActions) {
    val r = t.result
    val lines = buildList {
        add(pluralStringResource(R.plurals.import_done_imported, r.imported, r.imported))
        if (r.alreadyThere > 0) add(pluralStringResource(R.plurals.import_done_duplicates, r.alreadyThere, r.alreadyThere))
        if (r.tooLarge > 0) add(pluralStringResource(R.plurals.import_done_too_large, r.tooLarge, r.tooLarge))
        val failed = r.failed + r.notReadable
        if (failed > 0) add(pluralStringResource(R.plurals.import_done_failed, failed, failed))
    }
    ResultView(KinIcons.Check, Kin.colors.sage, stringResource(if (t.restore) R.string.restore_done_title else R.string.import_done_title), lines) {
        KinButton(stringResource(R.string.removable_done), actions.onDone)
    }
}

@Composable
private fun ProblemView(p: StorageTask.Problem, actions: PanelActions) {
    val ctx = LocalContext.current
    val (title, body) = when (p.kind) {
        StorageTask.Problem.Kind.NoWriteAccess -> R.string.problem_no_write_access_title to stringResource(R.string.problem_no_write_access_body)
        StorageTask.Problem.Kind.NoSpace -> R.string.problem_no_space_title to stringResource(
            R.string.problem_no_space_body, Formatter.formatShortFileSize(ctx, p.needBytes), Formatter.formatShortFileSize(ctx, p.freeBytes),
        )
        StorageTask.Problem.Kind.WriteFailed -> R.string.problem_write_failed_title to stringResource(R.string.problem_write_failed_body)
        StorageTask.Problem.Kind.NothingToExport -> R.string.problem_nothing_to_export_title to stringResource(R.string.problem_nothing_to_export_body)
        StorageTask.Problem.Kind.NoPermission -> R.string.problem_no_permission_title to stringResource(R.string.problem_no_permission_body)
        StorageTask.Problem.Kind.Offline -> R.string.problem_offline_title to stringResource(R.string.problem_offline_body)
        StorageTask.Problem.Kind.NothingFound -> R.string.problem_nothing_found_title to stringResource(R.string.problem_nothing_found_body)
        StorageTask.Problem.Kind.ReadFailed -> R.string.problem_read_failed_title to stringResource(R.string.problem_read_failed_body)
    }
    ResultView(KinIcons.Info, Kin.colors.danger, stringResource(title), listOf(body)) {
        if (p.kind == StorageTask.Problem.Kind.NoWriteAccess) KinButton(stringResource(R.string.removable_allow_access), actions.onAllowAccess)
        KinButton(stringResource(R.string.removable_done), actions.onDone, style = if (p.kind == StorageTask.Problem.Kind.NoWriteAccess) KinButtonStyle.Secondary else KinButtonStyle.Primary)
    }
}

// ---------------------------------------------------------------------------------------------- import picker

/** Grid of the stick's photos and videos with select all and sort (frameo-behaviour-spec B.10). */
@Composable
fun ImportPicker(
    candidates: List<ImportCandidate>,
    restore: Boolean,
    onCancel: () -> Unit,
    onImport: (List<ImportCandidate>) -> Unit,
    thumb: @Composable (ImportCandidate) -> Unit = { ImportThumb(it) },
    initiallySelected: Set<File>? = null,
) {
    var sort by remember { mutableStateOf(ImportScanner.Sort.Date) }
    // A restore takes everything by default; a plain import starts empty so nothing is sent by accident.
    var selected by remember { mutableStateOf(initiallySelected ?: if (restore) candidates.mapTo(HashSet()) { it.file } else emptySet()) }
    val sorted = remember(candidates, sort) { if (restore) candidates else ImportScanner.sort(candidates, sort) }
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 900.dp
            val header = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                        KinIcon(KinIcons.ChevronLeft, stringResource(R.string.action_back))
                    }
                    Text(stringResource(if (restore) R.string.restore_title else R.string.import_title), style = Kin.type.headline, modifier = Modifier.padding(start = 8.dp))
                }
            }
            val tools = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!restore) {
                        ChoiceChips(
                            listOf(ImportScanner.Sort.Date to stringResource(R.string.import_sort_date), ImportScanner.Sort.Name to stringResource(R.string.import_sort_name)),
                            sort, { sort = it },
                        )
                    }
                    val all = selected.size == candidates.size
                    KinButton(
                        stringResource(if (all) R.string.import_select_none else R.string.import_select_all),
                        { selected = if (all) emptySet() else candidates.mapTo(HashSet()) { it.file } },
                        style = KinButtonStyle.Quiet,
                    )
                }
            }
            if (narrow) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header(); Box(Modifier.padding(start = 16.dp)) { tools() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 32.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { header() }
                    tools()
                }
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sorted, key = { it.file.path }) { cand ->
                val on = cand.file in selected
                PickerTile(cand, on, { selected = if (on) selected - cand.file else selected + cand.file }, thumb)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Kin.colors.surface).border(1.dp, Kin.colors.line).padding(horizontal = 32.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pluralStringResource(R.plurals.import_selected, selected.size, selected.size), style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f))
            KinButton(stringResource(R.string.action_cancel), onCancel, style = KinButtonStyle.Quiet)
            Spacer(Modifier.width(12.dp))
            KinButton(
                pluralStringResource(if (restore) R.plurals.restore_action_count else R.plurals.import_action, selected.size, selected.size),
                { onImport(sorted.filter { it.file in selected }) }, enabled = selected.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun PickerTile(c: ImportCandidate, selected: Boolean, onToggle: () -> Unit, thumb: @Composable (ImportCandidate) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.aspectRatio(1f).clip(shape).background(Kin.colors.paperDeep)
            .border(if (selected) 3.dp else 0.dp, if (selected) Kin.colors.accent else Color.Transparent, shape)
            .clickable(onClick = onToggle),
    ) {
        thumb(c)
        if (c.kind == "video") {
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("▶", style = Kin.type.caption, color = Color.White)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape)
                .background(if (selected) Kin.colors.accent else Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) KinIcon(KinIcons.Check, null, tint = Color.White, size = 18.dp) }
    }
}

/** A small thumbnail decoded off the main thread at tile size (never the full picture: 1 GB of RAM). */
@Composable
fun ImportThumb(c: ImportCandidate) {
    val bmp by produceState<Bitmap?>(null, c.file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeThumb(c, 200) }.getOrNull() }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { KinIcon(KinIcons.Image, null, tint = Kin.colors.inkFaint) }
}

private fun decodeThumb(c: ImportCandidate, target: Int): Bitmap? {
    if (c.kind == "video") {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(c.file.absolutePath)
            r.getFrameAtTime(1_000_000)?.let { Bitmap.createScaledBitmap(it, target, target * it.height / it.width.coerceAtLeast(1), true) }
        } finally {
            runCatching { r.release() }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(c.file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(c.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
}
