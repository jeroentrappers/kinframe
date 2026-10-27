package app.kinframe.family.ui.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.BulkAction
import app.kinframe.core.api.Frame
import app.kinframe.core.api.LivePhotos
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.SleepScheduleDto
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.Skeleton
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.LiveBadge
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryTab(
    frame: Frame,
    state: LibraryState,
    trashDays: Int,
    onTab: (LibraryTab) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAction: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** "Share to another frame" for the selection; null when there is no other frame you can send to. */
    onShare: (() -> Unit)? = null,
) {
    val c = Kin.colors
    val grid = rememberLazyGridState()
    val list = (state.placements as? Load.Ready)?.value
    LaunchedEffect(grid, list?.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= grid.layoutInfo.totalItemsCount - 6) onLoadMore() }
    }
    val selecting = state.selected.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp), state = grid, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryTab.entries.forEach { t ->
                            val n = when (t) { LibraryTab.Visible -> state.counts.photos + state.counts.videos; LibraryTab.Hidden -> state.counts.hidden; LibraryTab.Trash -> state.counts.trashed }
                            val label = stringResource(when (t) { LibraryTab.Visible -> R.string.library_visible; LibraryTab.Hidden -> R.string.library_hidden; LibraryTab.Trash -> R.string.library_trash_tab })
                            ToggleChip(if (n > 0) "$label · $n" else label, state.tab == t, { onTab(t) })
                        }
                    }
                    if (!frame.can { it.seeLibrary }) Note(stringResource(R.string.library_own_only))
                    if (state.tab == LibraryTab.Trash) Note(stringResource(R.string.library_trash_note, trashDays))
                }
            }
            when (val p = state.placements) {
                Load.Loading -> items(12) { Skeleton(Modifier.aspectRatio(1f), corner = 10.dp) }
                is Load.Error -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(p.message, onRetry) }
                is Load.Ready -> if (p.value.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when (state.tab) {
                            LibraryTab.Visible -> EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), art = { FrameArt(width = 140.dp) })
                            LibraryTab.Hidden -> EmptyState(stringResource(R.string.library_empty_hidden), "", art = {})
                            LibraryTab.Trash -> EmptyState(stringResource(R.string.library_empty_trash), "", art = {})
                        }
                    }
                } else items(p.value, key = { it.id }) { pl ->
                    val sel = pl.id in state.selected
                    val desc = stringResource(if (pl.item.kind == MediaKind.VIDEO) R.string.library_video_by else R.string.library_item_by, pl.item.senderName.orEmpty()) +
                        (pl.item.caption?.let { ". $it" } ?: "") + ". " + stringResource(R.string.library_received, Format.relative(pl.receivedAt)) +
                        (pl.sharedByName?.let { ". " + stringResource(R.string.shared_by, it) } ?: "")
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .border(if (sel) 3.dp else 0.dp, if (sel) c.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .combinedClickable(role = Role.Checkbox, onLongClick = { onToggle(pl.id) }, onClick = { onToggle(pl.id) })
                            .semantics { contentDescription = desc; selected = sel },
                    ) {
                        Thumb(pl.item.thumbUrl, null, Modifier.fillMaxSize(), corner = 10.dp)
                        if (pl.item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, null, Modifier.align(Alignment.Center).size(22.dp), tint = Color.White)
                        if (pl.item.isLive) LiveBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
                        if (pl.sharedByName != null) Icon(KinIcons.Share, null, Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp)
                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(3.dp), tint = Color.White)
                        if (pl.pendingModeration) Text(stringResource(R.string.library_pending), style = Kin.type.caption, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp))
                        if (selecting) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                    .background(if (sel) c.accent else Color.Black.copy(alpha = 0.35f)).border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (sel) Icon(KinIcons.Check, null, Modifier.size(14.dp), tint = Color.White) }
                        }
                    }
                }
            }
        }
        if (selecting) {
            HorizontalDivider(color = c.line)
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClear) { Icon(KinIcons.Close, stringResource(R.string.library_clear_selection), tint = c.ink) }
                Text(pluralStringResource(R.plurals.library_selected, state.selected.size, state.selected.size), style = Kin.type.label, color = c.ink, modifier = Modifier.weight(1f))
                TextButton(onSelectAll) { Text(stringResource(R.string.library_select_all), color = c.accent) }
                if (onShare != null && state.tab != LibraryTab.Trash) {
                    IconButton(onShare, enabled = !state.busy) { Icon(KinIcons.Share, stringResource(R.string.share_action), tint = c.ink) }
                }
                when (state.tab) {
                    LibraryTab.Visible -> {
                        IconButton({ onAction(BulkAction.HIDE) }, enabled = !state.busy) { Icon(KinIcons.EyeOff, stringResource(R.string.library_hide), tint = c.ink) }
                        IconButton({ onAction(BulkAction.TRASH) }, enabled = !state.busy) { Icon(KinIcons.Trash, stringResource(R.string.library_trash), tint = c.danger) }
                    }
                    LibraryTab.Hidden -> {
                        IconButton({ onAction(BulkAction.UNHIDE) }, enabled = !state.busy) { Icon(KinIcons.Eye, stringResource(R.string.library_unhide), tint = c.ink) }
                        IconButton({ onAction(BulkAction.TRASH) }, enabled = !state.busy) { Icon(KinIcons.Trash, stringResource(R.string.library_trash), tint = c.danger) }
                    }
                    LibraryTab.Trash -> IconButton({ onAction(BulkAction.RESTORE) }, enabled = !state.busy) { Icon(KinIcons.Undo, stringResource(R.string.library_restore), tint = c.ink) }
                }
            }
        }
    }
}

/** Remote settings form (README §5.6), the same fields as the web app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(
    frame: Frame,
    state: SettingsFormState,
    weatherEnabled: Boolean,
    vm: FrameSettingsViewModel,
    /** The Plugins section (saved on its own, see [PluginsViewModel]). */
    plugins: (@Composable () -> Unit)? = null,
) {
    val c = Kin.colors
    val context = LocalContext.current
    when (val l = state.load) {
        Load.Loading -> { SkeletonList(4, 100.dp); return }
        is Load.Error -> { ErrorPanel(l.message, { vm.load() }); return }
        else -> Unit
    }
    val s = state.settings
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            PageColumn {
                if (!frame.online) Note(stringResource(R.string.fs_offline_note))
                if (state.conflict) Note(stringResource(R.string.fs_conflict), tone = NoteTone.Warn)

                SectionTitle(stringResource(R.string.fs_slideshow))
                Surface(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fs_interval), style = Kin.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presets = listOf(5, 10, 15, 30, 60, 120, 300, 900, 3600, 86_400)
                        (if (s.slideshow.intervalS in presets) presets else (presets + s.slideshow.intervalS).sorted()).forEach { sec ->
                            ToggleChip(Format.duration(context, sec.toLong()), s.slideshow.intervalS == sec, { vm.set("slideshow.interval_s", sec) })
                        }
                    }
                    Text(stringResource(R.string.fs_order), style = Kin.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("received" to R.string.fs_order_received, "taken" to R.string.fs_order_taken, "shuffle" to R.string.fs_order_shuffle).forEach { (v, l) ->
                            ToggleChip(stringResource(l), s.slideshow.order == v, { vm.set("slideshow.order", v) })
                        }
                    }
                    if (s.slideshow.order != "shuffle") SwitchRow(stringResource(R.string.fs_reverse), s.slideshow.reverse, { vm.set("slideshow.reverse", it) })
                    Text(stringResource(R.string.fs_transition), style = Kin.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("crossfade" to R.string.fs_crossfade, "slide" to R.string.fs_slide, "kenburns" to R.string.fs_kenburns).forEach { (v, l) ->
                            ToggleChip(stringResource(l), s.slideshow.transition == v, { vm.set("slideshow.transition", v) })
                        }
                    }
                    SwitchRow(stringResource(R.string.fs_fill), s.slideshow.fill, { vm.set("slideshow.fill", it) }, hint = stringResource(R.string.fs_fill_hint))
                    Text(stringResource(R.string.fs_live_photos), style = Kin.type.label, color = c.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(LivePhotos.ON to R.string.fs_live_on, LivePhotos.TAP to R.string.fs_live_tap, LivePhotos.OFF to R.string.fs_live_off).forEach { (v, l) ->
                            ToggleChip(stringResource(l), s.slideshow.livePhotos == v, { vm.set("slideshow.live_photos", v) })
                        }
                    }
                    SwitchRow(stringResource(R.string.fs_show_caption), s.widgets.caption, { vm.set("widgets.caption", it) })
                    SwitchRow(stringResource(R.string.fs_show_clock), s.widgets.clock, { vm.set("widgets.clock", it) })
                }

                SectionTitle(stringResource(R.string.fs_display))
                Surface(Modifier.fillMaxWidth()) {
                    val pct = ((s.display.brightness - 15) * 100f / 240).roundToInt()
                    Row { Text(stringResource(R.string.fs_brightness), style = Kin.type.label, color = c.ink, modifier = Modifier.weight(1f)); Text("$pct%", style = Kin.type.label, color = c.inkSoft) }
                    val label = stringResource(R.string.fs_brightness)
                    Slider(
                        value = s.display.brightness.toFloat(), onValueChange = { vm.set("display.brightness", it.roundToInt()) }, valueRange = 15f..255f,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }

                SectionTitle(stringResource(R.string.fs_sleep)) {
                    TextButton({ vm.addSchedule() }) { Icon(KinIcons.Plus, null, tint = c.accent); Text(stringResource(R.string.fs_add_schedule), color = c.accent) }
                }
                Text(stringResource(R.string.fs_sleep_hint), style = Kin.type.caption, color = c.inkSoft)
                val schedules = s.display.sleepSchedules
                if (schedules.isEmpty()) Note(stringResource(R.string.fs_no_schedules))
                schedules.forEachIndexed { i, sch ->
                    ScheduleCard(i, sch, { n -> vm.setSchedules(schedules.mapIndexed { j, o -> if (j == i) n else o }) }, { vm.setSchedules(schedules.filterIndexed { j, _ -> j != i }) })
                }

                if (weatherEnabled) {
                    SectionTitle(stringResource(R.string.fs_weather_title))
                    Surface(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.fs_weather_hint), style = Kin.type.caption, color = c.inkSoft)
                        val loc = s.location
                        if (loc?.lat != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(KinIcons.MapPin, null, tint = c.accent)
                                Text(loc.name ?: stringResource(R.string.location_unnamed), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                TextButton({ vm.removePlace() }) { Text(stringResource(R.string.location_remove), color = c.danger) }
                            }
                        }
                        KinTextField(state.placeQuery, vm::onPlaceQuery, stringResource(R.string.location_search), placeholder = stringResource(R.string.location_placeholder),
                            error = state.placeError?.let { stringResource(it) },
                            trailing = { Icon(KinIcons.Search, null, tint = c.inkSoft) })
                        state.places.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { vm.choosePlace(p) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = Kin.type.bodyStrong, color = c.ink)
                                    Text(listOfNotNull(p.admin1, p.country).joinToString(", "), style = Kin.type.caption, color = c.inkSoft)
                                }
                            }
                        }
                        if (state.placeQuery.trim().length >= 2 && !state.searching && state.places.isEmpty() && state.placeError == null) {
                            Text(stringResource(R.string.location_no_results, state.placeQuery.trim()), style = Kin.type.caption, color = c.inkSoft)
                        }
                        SwitchRow(stringResource(R.string.fs_show_weather), s.widgets.weather, { vm.set("widgets.weather", it) },
                            hint = stringResource(if (loc?.lat == null) R.string.fs_needs_location else R.string.fs_show_weather_hint), enabled = loc?.lat != null)
                        Text(stringResource(R.string.fs_temperature_unit), style = Kin.type.label, color = c.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToggleChip(stringResource(R.string.fs_celsius), s.locale.temperatureUnit == "c", { vm.set("locale.temperature_unit", "c") })
                            ToggleChip(stringResource(R.string.fs_fahrenheit), s.locale.temperatureUnit == "f", { vm.set("locale.temperature_unit", "f") })
                        }
                        Text(stringResource(R.string.fs_weather_credit), style = Kin.type.caption, color = c.inkFaint)
                    }
                }

                plugins?.invoke()
            }
        }
        if (state.dirty) {
            HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fs_unsaved), style = Kin.type.label, color = c.inkSoft, modifier = Modifier.weight(1f))
                QuietButton(stringResource(R.string.fs_discard), { vm.discard() })
                PrimaryButton(stringResource(R.string.common_save), { vm.save() }, busy = state.saving)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(i: Int, sch: SleepScheduleDto, onChange: (SleepScheduleDto) -> Unit, onRemove: () -> Unit) {
    val c = Kin.colors
    var picking by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fs_schedule_n, i + 1), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(sch.enabled, { onChange(sch.copy(enabled = it)) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent))
            IconButton(onRemove) { Icon(KinIcons.Trash, stringResource(R.string.fs_remove_schedule), tint = c.inkSoft) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeChip(stringResource(R.string.fs_sleep_at), sch.sleepAt, Modifier.weight(1f)) { picking = "sleep" }
            TimeChip(stringResource(R.string.fs_wake_at), sch.wakeAt, Modifier.weight(1f)) { picking = "wake" }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("mon" to R.string.day_mon, "tue" to R.string.day_tue, "wed" to R.string.day_wed, "thu" to R.string.day_thu,
                "fri" to R.string.day_fri, "sat" to R.string.day_sat, "sun" to R.string.day_sun).forEach { (d, l) ->
                ToggleChip(stringResource(l), d in sch.days, { onChange(sch.copy(days = if (d in sch.days) sch.days - d else sch.days + d)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.fs_mode_off), sch.mode == "off", { onChange(sch.copy(mode = "off")) })
            ToggleChip(stringResource(R.string.fs_mode_dim), sch.mode == "dim", { onChange(sch.copy(mode = "dim")) })
        }
    }
    picking?.let { which ->
        val cur = if (which == "sleep") sch.sleepAt else sch.wakeAt
        val (h, m) = cur.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
        val tp = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(if (which == "sleep") R.string.fs_sleep_at else R.string.fs_wake_at)) },
            text = { TimeInput(tp) },
            confirmButton = {
                TextButton({
                    val v = "%02d:%02d".format(tp.hour, tp.minute)
                    onChange(if (which == "sleep") sch.copy(sleepAt = v) else sch.copy(wakeAt = v)); picking = null
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = { TextButton({ picking = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun TimeChip(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Kin.colors
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.paperDeep).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
    ) {
        Text(label, style = Kin.type.caption, color = c.inkSoft)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(KinIcons.Clock, null, Modifier.size(18.dp), tint = c.inkSoft)
            Text(value, style = Kin.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
