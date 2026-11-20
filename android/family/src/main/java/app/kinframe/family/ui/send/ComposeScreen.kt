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
import app.kinframe.family.ui.KinTopBar
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
import androidx.compose.ui.platform.LocalContext

/**
 * The composer (`+`, docs/spec/simple-app.md §3.2): photos first, captions and framing per item, then one destination
 * row — the current family ("everyone and its 3 frames"), a circle of it, or (from a frame's screen) that frame only —
 * and "Share".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    state: ComposerState,
    onPick: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    /** "Change": another family or a circle; null hides it (a frame-only send, or nothing to change to). */
    onChange: (() -> Unit)? = null,
    /** "Answers this week's prompt" for the destination family; null hides it. */
    onAnswer: ((String?) -> Unit)? = null,
) {
    val c = Kin.colors
    val frames = state.frames
    Column(Modifier.fillMaxSize().background(c.surface)) {
        KinTopBar(
            state.destinationFrame?.let { stringResource(R.string.frames_send_to, it.name) } ?: stringResource(R.string.compose_title),
            onBack = onCancel,
        )
        if (frames is Load.Error && state.items.isEmpty()) {
            ErrorPanel(frames.message, onRetry, Modifier.padding(top = 80.dp)); return@Column
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.items.isEmpty()) {
                    PickerHero(state, onPick)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.send_count, state.items.size, state.sendLimits.limits.maxItemsPerSend),
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
                if (state.overLimit > 0) Note(stringResource(R.string.send_over_limit, state.sendLimits.limits.maxItemsPerSend, state.overLimit), tone = NoteTone.Warn)
                if (state.tooMany) Note(state.sendLimits.itemsServer?.let { stringResource(R.string.send_limit_items_server, it, state.sendLimits.limits.maxItemsPerSend) }
                    ?: stringResource(R.string.send_limit_items, state.sendLimits.limits.maxItemsPerSend), tone = NoteTone.Danger)
                if (state.issues > 0) state.sendLimits.let { l ->
                    val tooLarge = state.items.any { state.issue(it) == ItemIssue.TooLarge }
                    val tooLong = state.items.any { state.issue(it) == ItemIssue.TooLong }
                    if (tooLarge && l.uploadServer != null) Note(stringResource(R.string.send_limit_upload_server, l.uploadServer, Format.bytes(LocalContext.current, l.limits.maxUploadBytes)), tone = NoteTone.Warn)
                    if (tooLong && l.videoServer != null) Note(stringResource(R.string.send_limit_video_server, l.videoServer, Format.clock(l.limits.maxVideoSeconds * 1000L)), tone = NoteTone.Warn)
                }
                if (state.issues > 0) Note(pluralStringResource(R.plurals.send_issues, state.issues, state.issues), tone = NoteTone.Danger)

                HorizontalDivider(color = c.line)
                when (frames) {
                    Load.Loading -> SkeletonList(1, 48.dp)
                    else -> Destination(state, onChange)
                }
                state.destinationFamily?.let { f ->
                    val prompt = state.prompts[f.id]
                    if (prompt != null && onAnswer != null) ToggleChip(stringResource(R.string.engagement_answers_prompt, prompt.title), state.answering == prompt.id,
                        { onAnswer(if (state.answering == prompt.id) null else prompt.id) }) { Icon(KinIcons.Star, null, Modifier.size(16.dp), tint = c.inkSoft) }
                }
                if (state.destinationFrame?.online == false) Note(stringResource(R.string.send_offline_note))
                state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.items.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), contentAlignment = Alignment.Center) {
                PrimaryButton(
                    if (state.preparing) stringResource(R.string.send_preparing) else stringResource(R.string.compose_share),
                    onSend, Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), enabled = state.canSend, busy = state.preparing,
                )
            }
        }
    }
}

/** "Peeters family · Everyone in the family and its 3 frames · Change", or "Oma's frame · Only this frame". */
@Composable
private fun Destination(state: ComposerState, onChange: (() -> Unit)?) {
    val c = Kin.colors
    val frame = state.destinationFrame
    val family = state.destinationFamily
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (frame != null) Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) { Icon(KinIcons.Frame, null, tint = c.inkSoft) }
        else app.kinframe.family.ui.family.FamilyCover(family, 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val families = state.destinationFamilies
            Text(frame?.name ?: families.joinToString(", ") { it.name }, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(destinationLine(state), style = Kin.type.caption, color = c.inkSoft)
        }
        if (onChange != null && frame == null) QuietButton(stringResource(R.string.compose_change), onChange)
    }
}

/** The line under the destination's name. */
@Composable
fun destinationLine(state: ComposerState): String {
    if (state.destinationFrame != null) return stringResource(R.string.compose_frame_only)
    val families = state.destinationFamilies
    if (families.size > 1) {
        val frames = families.sumOf { it.subscriptionCount }
        return pluralStringResource(R.plurals.compose_families, families.size, families.size) + " · " + pluralStringResource(R.plurals.compose_frames, frames, frames)
    }
    state.destinationCircle?.let { return stringResource(R.string.compose_circle, it.name) }
    val n = state.destinationFamily?.subscriptionCount ?: 0
    return if (n == 0) stringResource(R.string.compose_everyone_no_frames) else pluralStringResource(R.plurals.compose_everyone, n, n)
}

/**
 * "Share with": the person's families as checkboxes (one post to several families), and per ticked family everyone
 * or one of its circles. Not single frames.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(state: ComposerState, onFamily: (String) -> Unit, onCircle: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val c = Kin.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.compose_sheet_title), style = Kin.type.title, color = c.ink)
            state.feeds.forEach { f ->
                val on = f.id in state.selectedFeeds
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { onFamily(f.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.Checkbox(on, null, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent))
                    app.kinframe.family.ui.family.FamilyCover(f, 36)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val n = f.subscriptionCount
                        Text(if (n == 0) stringResource(R.string.compose_everyone_no_frames) else pluralStringResource(R.plurals.compose_everyone, n, n),
                            style = Kin.type.caption, color = c.inkSoft)
                    }
                }
                val circles = state.circles[f.id].orEmpty()
                if (on && circles.isNotEmpty()) {
                    Text(stringResource(R.string.compose_sheet_who, f.name), style = Kin.type.label, color = c.inkSoft, modifier = Modifier.padding(start = 48.dp))
                    FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(stringResource(R.string.compose_sheet_everyone), state.audience[f.id] == null, { onCircle(f.id, null) })
                        circles.forEach { ci -> ToggleChip(ci.name, state.audience[f.id] == ci.id, { onCircle(f.id, ci.id) }) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Whether "Change" has anything to offer. */
fun canChangeDestination(state: ComposerState): Boolean =
    !state.frameOnly && (state.feeds.size > 1 || state.destinationFamilies.any { state.circles[it.id].orEmpty().isNotEmpty() })

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
        Text(stringResource(R.string.compose_choose_photos), style = Kin.type.body, color = c.inkSoft)
        PrimaryButton(stringResource(R.string.send_choose), onPick, icon = KinIcons.Image, busy = state.adding)
        Text(
            stringResource(R.string.send_choose_hint, state.sendLimits.limits.maxItemsPerSend), style = Kin.type.caption, color = c.inkSoft,
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

