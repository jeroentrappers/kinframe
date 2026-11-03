package app.kinframe.family.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.LiveBadge
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    state: ComposerState,
    onPick: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onToggleFrame: (String) -> Unit,
    onSend: () -> Unit,
    onAddFrame: () -> Unit,
    onRetryFrames: () -> Unit,
) {
    val c = Kin.colors
    val frames = state.frames
    if (frames is Load.Ready && state.sendableFrames.isEmpty()) {
        Box(Modifier.fillMaxSize().background(c.paper).padding(top = 40.dp)) {
            EmptyState(stringResource(R.string.send_no_frames_title), stringResource(R.string.send_no_frames_body)) {
                PrimaryButton(stringResource(R.string.frames_add), onAddFrame, icon = KinIcons.Plus)
            }
        }
        return
    }
    if (frames is Load.Error && state.items.isEmpty()) {
        ErrorPanel(frames.message, onRetryFrames, Modifier.padding(top = 80.dp)); return
    }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.send_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
                if (state.items.isEmpty()) {
                    Text(stringResource(R.string.send_lede), style = Kin.type.body, color = c.inkSoft)
                    PickerHero(state, onPick)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.send_count, state.items.size, state.limits.maxItemsPerSend),
                            style = Kin.type.label, color = c.inkSoft, modifier = Modifier.weight(1f),
                        )
                        QuietButton(stringResource(R.string.send_clear), onClear)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.items.forEachIndexed { i, item -> DraftThumb(i, item, state.issue(item), { onEdit(i) }, { onRemove(item.id) }) }
                        AddTile(onPick)
                    }
                    Text(stringResource(R.string.send_focal_hint), style = Kin.type.caption, color = c.inkFaint)
                }
                if (state.skipped > 0) Note(pluralStringResource(R.plurals.send_skipped, state.skipped, state.skipped), tone = NoteTone.Warn)
                if (state.overLimit > 0) Note(stringResource(R.string.send_over_limit, state.limits.maxItemsPerSend, state.overLimit), tone = NoteTone.Warn)
                if (state.issues > 0) Note(pluralStringResource(R.plurals.send_issues, state.issues, state.issues), tone = NoteTone.Danger)

                SectionTitle(stringResource(R.string.send_to))
                when (frames) {
                    Load.Loading -> SkeletonList(1, 48.dp)
                    is Load.Error -> ErrorPanel(frames.message, onRetryFrames)
                    is Load.Ready -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.sendableFrames.forEach { f ->
                                ToggleChip(f.name, f.id in state.selected, { onToggleFrame(f.id) }) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (f.online) c.sage else c.inkFaint))
                                }
                            }
                        }
                        val anyOffline = state.sendableFrames.any { it.id in state.selected && !it.online }
                        if (anyOffline) Note(stringResource(R.string.send_offline_note))
                        if (state.items.isNotEmpty() && state.selected.isEmpty()) Text(stringResource(R.string.send_pick_frame), style = Kin.type.caption, color = c.warn)
                    }
                }
                state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.items.isNotEmpty()) {
            HorizontalDivider(color = c.line)
            Box(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                PrimaryButton(
                    if (state.preparing) stringResource(R.string.send_preparing)
                    else pluralStringResource(R.plurals.send_button, state.items.size, state.items.size),
                    onSend, Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), enabled = state.canSend, busy = state.preparing, icon = KinIcons.Send,
                )
            }
        }
    }
}

@Composable
private fun PickerHero(state: ComposerState, onPick: () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface)
            .border(1.5.dp, c.line, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onPick).padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FrameArt(width = 150.dp)
        PrimaryButton(stringResource(R.string.send_choose), onPick, icon = KinIcons.Image, busy = state.adding)
        Text(
            stringResource(R.string.send_choose_hint, state.limits.maxItemsPerSend), style = Kin.type.caption, color = c.inkSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DraftThumb(index: Int, item: DraftItem, issue: ItemIssue?, onEdit: () -> Unit, onRemove: () -> Unit) {
    val c = Kin.colors
    val label = stringResource(R.string.send_adjust_item, index + 1) + (item.caption.takeIf { it.isNotBlank() }?.let { ". $it" } ?: "")
    Box(Modifier.size(104.dp)) {
        Box(
            Modifier.size(104.dp).clip(RoundedCornerShape(14.dp))
                .border(if (issue != null) 3.dp else 0.dp, if (issue != null) c.danger else Color.Transparent, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onEdit).semantics { contentDescription = label },
        ) {
            Thumb(item.info.uri, null, Modifier.fillMaxSize(), corner = 14.dp)
            if (item.isVideo) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(if (item.trim != null) KinIcons.Scissors else KinIcons.Play, null, Modifier.size(11.dp), tint = Color.White)
                    Text(Format.clock(item.sendDurationMs ?: 0), style = Kin.type.caption, color = Color.White)
                }
            }
            if (item.isLive) LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            if (item.caption.isNotBlank()) {
                Icon(KinIcons.Message, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(3.dp), tint = Color.White)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(48.dp).clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            val removeLabel = stringResource(R.string.send_remove_item, index + 1)
            Icon(KinIcons.Close, removeLabel, Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(5.dp), tint = Color.White)
        }
    }
}

@Composable
private fun AddTile(onPick: () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(c.paperDeep).clickable(role = Role.Button, onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(KinIcons.Plus, null, tint = c.inkSoft)
        Text(stringResource(R.string.send_add_more), style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

