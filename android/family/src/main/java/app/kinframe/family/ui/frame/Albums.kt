package app.kinframe.family.ui.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kinframe.core.api.Album
import app.kinframe.core.api.BulkAction
import app.kinframe.core.api.Frame
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.Placement
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Album schedules as `MM-DD`, shown in words ("1 December"). */
object AlbumDates {
    fun parse(md: String?): Pair<Int, Int>? = md?.split('-')?.takeIf { it.size == 2 }?.let { (m, d) -> m.toIntOrNull()?.let { mm -> d.toIntOrNull()?.let { mm to it } } }
    fun format(m: Int, d: Int) = "%02d-%02d".format(m, d)
    fun label(md: String?, locale: Locale = Locale.getDefault()): String =
        parse(md)?.let { (m, d) -> "$d " + Month.of(m.coerceIn(1, 12)).getDisplayName(TextStyle.FULL_STANDALONE, locale) } ?: ""

    /** The schedule runs over New Year (December to January). */
    fun wraps(from: String, to: String) = to < from

    /** Days in a month, February counted with 29 (the schedule repeats every year). */
    fun days(month: Int) = if (month == 2) 29 else Month.of(month.coerceIn(1, 12)).length(false)

    /** Whole years between a photo's capture date and [today], for "On this day". */
    fun yearsAgo(capturedAt: String?, today: LocalDate): Int =
        Format.instant(capturedAt)?.atZone(java.time.ZoneId.systemDefault())?.year?.let { today.year - it }?.coerceAtLeast(1) ?: 1
}

/** The album chips above the library: all photos, each album, and "New album". */
@Composable
fun AlbumChips(state: LibraryState, canManage: Boolean, onSelect: (String?) -> Unit, onNew: () -> Unit) {
    if (state.albums.isEmpty() && !canManage) return
    val c = Kin.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.albums_all), state.albumId == null, { onSelect(null) })
        state.albums.forEach { a ->
            ToggleChip("${a.name} · ${a.placementCount}", state.albumId == a.id, { onSelect(a.id) }, leading = when {
                state.playingAlbumId == a.id -> ({ Icon(KinIcons.Play, stringResource(R.string.albums_playing_badge), Modifier.size(14.dp)) })
                a.schedule != null -> ({ Icon(KinIcons.Calendar, stringResource(R.string.albums_scheduled), Modifier.size(14.dp)) })
                else -> null
            })
        }
        if (canManage) ToggleChip(stringResource(R.string.albums_new), false, onNew, leading = { Icon(KinIcons.Plus, null, Modifier.size(14.dp), tint = c.accent) })
    }
}

/** What the header of an open album offers. */
class AlbumHeaderActions(
    val onPlay: (Boolean) -> Unit,
    val onTv: (() -> Unit)?,
    val onArrange: () -> Unit,
    val onSchedule: () -> Unit,
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumHeader(album: Album, cover: Placement?, playing: Boolean, canSettings: Boolean, canManage: Boolean, a: AlbumHeaderActions) {
    val c = Kin.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) {
            if (cover != null) Thumb(cover.item.thumbUrl, null, Modifier.fillMaxSize()) else Icon(KinIcons.Album, null, tint = c.inkFaint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(album.name, style = Kin.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.albums_count, album.placementCount, album.placementCount), style = Kin.type.caption, color = c.inkSoft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (playing) Pill(stringResource(R.string.albums_playing_badge), c.sageSoft, c.onSageSoft, icon = KinIcons.Play)
                album.schedule?.let { s ->
                    Pill(stringResource(R.string.albums_schedule_short, AlbumDates.label(s.fromMonthDay), AlbumDates.label(s.toMonthDay)), c.accentSoft, c.onAccentSoft, icon = KinIcons.Calendar)
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canSettings) {
            if (playing) SecondaryButton(stringResource(R.string.albums_stop_playing), { a.onPlay(false) }, icon = KinIcons.Image)
            else PrimaryButton(stringResource(R.string.albums_play), { a.onPlay(true) }, icon = KinIcons.Play)
        }
        a.onTv?.let { SecondaryButton(stringResource(R.string.tv_show), it, icon = KinIcons.Tv) }
        if (canManage) {
            if (album.placementCount > 1) QuietButton(stringResource(R.string.albums_arrange), a.onArrange)
            QuietButton(stringResource(R.string.albums_schedule), a.onSchedule)
            QuietButton(stringResource(R.string.albums_rename), a.onRename)
            QuietButton(stringResource(R.string.albums_delete), a.onDelete, danger = true)
        }
    }
}

/** "On this day": a strip of earlier years' photos taken on today's date. */
@Composable
fun MemoriesStrip(memories: List<Placement>, today: LocalDate, onOpen: (Placement) -> Unit) {
    if (memories.isEmpty()) return
    val c = Kin.colors
    val dayLabel = "${today.dayOfMonth} " + today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.memories_title), style = Kin.type.bodyStrong, color = c.ink)
        Text(pluralStringResource(R.plurals.memories_subtitle, memories.size, memories.size, dayLabel), style = Kin.type.caption, color = c.inkSoft)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            memories.forEach { p ->
                val years = AlbumDates.yearsAgo(p.item.capturedAt, today)
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onOpen(p) }) {
                    Thumb(p.item.thumbUrl, null, Modifier.fillMaxSize(), corner = 10.dp)
                    Text(pluralStringResource(R.plurals.memories_years_ago, years, years), style = Kin.type.caption, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

/** New album or rename (web `AlbumNameDialog`). */
@Composable
fun AlbumNameDialog(initial: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.albums_new_title else R.string.albums_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) Text(stringResource(R.string.albums_new_hint), style = Kin.type.caption, color = Kin.colors.inkSoft)
                KinTextField(name, { name = it.take(60) }, stringResource(R.string.albums_name), placeholder = stringResource(R.string.albums_name_placeholder))
            }
        },
        confirmButton = {
            TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(if (initial == null) R.string.albums_create else R.string.common_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Add the selection to an album, or to a new one (web `AlbumPickerDialog`). */
@Composable
fun AlbumPickerDialog(count: Int, albums: List<Album>, onDismiss: () -> Unit, onAdd: (albumId: String?, newName: String?) -> Unit) {
    val c = Kin.colors
    var choice by remember { mutableStateOf(albums.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.albums_add_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.albums_pick), style = Kin.type.label, color = c.ink)
                albums.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { choice = a.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == a.id, null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                        Text("${a.name} · ${a.placementCount}", style = Kin.type.body, color = c.ink, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { choice = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == null, null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                    Text(stringResource(R.string.albums_new), style = Kin.type.body, color = c.ink, modifier = Modifier.padding(start = 8.dp))
                }
                if (choice == null) KinTextField(name, { name = it.take(60) }, stringResource(R.string.albums_name), placeholder = stringResource(R.string.albums_name_placeholder))
            }
        },
        confirmButton = {
            TextButton({ onAdd(choice, name.trim().takeIf { choice == null }) }, enabled = choice != null || name.isNotBlank()) { Text(stringResource(R.string.albums_add)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Play an album automatically between two dates every year (web `AlbumScheduleDialog`). */
@Composable
fun AlbumScheduleDialog(album: Album, onDismiss: () -> Unit, onSave: (from: String?, to: String?) -> Unit) {
    val c = Kin.colors
    val from0 = AlbumDates.parse(album.schedule?.fromMonthDay) ?: (12 to 1)
    val to0 = AlbumDates.parse(album.schedule?.toMonthDay) ?: (12 to 31)
    var on by remember { mutableStateOf(album.schedule != null) }
    var from by remember { mutableStateOf(from0) }
    var to by remember { mutableStateOf(to0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.albums_schedule_title, album.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.albums_schedule_body), style = Kin.type.caption, color = c.inkSoft)
                SwitchRow(stringResource(R.string.albums_schedule_on), on, { on = it })
                if (on) {
                    Text(stringResource(R.string.albums_from), style = Kin.type.label, color = c.ink)
                    MonthDayPicker(from, R.string.albums_from_day, R.string.albums_from_month) { from = it }
                    Text(stringResource(R.string.albums_to), style = Kin.type.label, color = c.ink)
                    MonthDayPicker(to, R.string.albums_to_day, R.string.albums_to_month) { to = it }
                    val f = AlbumDates.format(from.first, from.second)
                    val t = AlbumDates.format(to.first, to.second)
                    Text(stringResource(R.string.albums_schedule_summary, AlbumDates.label(f), AlbumDates.label(t)) +
                        if (AlbumDates.wraps(f, t)) " " + stringResource(R.string.albums_schedule_wraps) else "", style = Kin.type.body, color = c.ink)
                }
            }
        },
        confirmButton = {
            TextButton({
                if (on) onSave(AlbumDates.format(from.first, from.second), AlbumDates.format(to.first, to.second)) else onSave(null, null)
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun MonthDayPicker(value: Pair<Int, Int>, dayLabel: Int, monthLabel: Int, onChange: (Pair<Int, Int>) -> Unit) {
    val (m, d) = value
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(stringResource(dayLabel), d.toString(), (1..AlbumDates.days(m)).map { it.toString() }, Modifier.width(96.dp)) { onChange(m to it.toInt()) }
        Picker(stringResource(monthLabel), Month.of(m).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()),
            (1..12).map { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) }, Modifier.weight(1f)) { name ->
            val nm = (1..12).first { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) == name }
            onChange(nm to d.coerceAtMost(AlbumDates.days(nm)))
        }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    val c = Kin.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperDeep).clickable(role = Role.DropdownList) { open = true }.padding(10.dp)) {
            Text(label, style = Kin.type.caption, color = c.inkSoft)
            Text(value, style = Kin.type.bodyStrong, color = c.ink)
        }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp)) {
            options.forEach { o -> DropdownMenuItem({ Text(o) }, { open = false; onPick(o) }) }
        }
    }
}

// ------------------------------------------------------------------ viewer

/** What the photo viewer offers for one placement (web `FrameLibrary` viewer dialog). */
class ViewerActions(
    val onTv: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onCover: (() -> Unit)?,
    val onRemoveFromAlbum: (() -> Unit)?,
    val onAddToAlbum: (() -> Unit)?,
    /** hide, unhide, trash or restore, as the library's tab allows. */
    val bulk: List<String>,
    val onBulk: (String) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlacementViewer(p: Placement, a: ViewerActions, onClose: () -> Unit) {
    val c = Kin.colors
    val video = p.item.kind == MediaKind.VIDEO
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.item.senderName?.let { stringResource(R.string.library_from, it) } ?: stringResource(R.string.library_photo), style = Kin.type.title, color = c.ink, modifier = Modifier.weight(1f))
                TextButton(onClose) { Text(stringResource(R.string.common_close)) }
            }
            val model = p.media?.posterUrl.takeIf { video } ?: p.media?.url.takeIf { !video } ?: p.item.thumbUrl
            Box(Modifier.fillMaxWidth().aspectRatio(((p.item.width ?: 4).toFloat() / (p.item.height ?: 3)).coerceIn(0.5f, 2f)).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                Thumb(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, corner = 12.dp)
                if (video) Icon(KinIcons.Play, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
            }
            p.item.caption?.let { Text(it, style = Kin.type.body, color = c.ink) }
            p.sharedByName?.let { Text(stringResource(R.string.shared_by, it), style = Kin.type.caption, color = c.inkSoft) }
            p.item.capturedAt?.let { Text(stringResource(R.string.library_taken, Format.date(it)), style = Kin.type.caption, color = c.inkSoft) }
            p.receivedAt?.let { Text(stringResource(R.string.library_received, Format.relative(it)), style = Kin.type.caption, color = c.inkSoft) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.onTv?.let { SecondaryButton(stringResource(R.string.tv_show), it, icon = KinIcons.Tv) }
                a.onShare?.let { SecondaryButton(stringResource(R.string.share_action), it, icon = KinIcons.Share) }
                a.onCover?.let { QuietButton(stringResource(R.string.albums_make_cover), it) }
                a.onRemoveFromAlbum?.let { QuietButton(stringResource(R.string.albums_remove), it) }
                a.onAddToAlbum?.let { QuietButton(stringResource(R.string.albums_add_to_album), it) }
                a.bulk.forEach { action ->
                    QuietButton(stringResource(when (action) {
                        BulkAction.HIDE -> R.string.library_hide
                        BulkAction.UNHIDE -> R.string.library_unhide
                        BulkAction.TRASH -> R.string.library_trash
                        else -> R.string.library_restore
                    }), { a.onBulk(action) }, danger = action == BulkAction.TRASH)
                }
            }
        }
    }
}

/** Library and album permissions, worked out once. */
fun Frame.libraryPerms() = Triple(can { it.manageLibrary }, can { it.changeSettings }, can { it.seeLibrary })
