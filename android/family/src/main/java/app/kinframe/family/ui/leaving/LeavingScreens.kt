package app.kinframe.family.ui.leaving

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.leaving.CannotAskReason
import app.kinframe.core.leaving.CommentsChoice
import app.kinframe.core.leaving.KeepAnswer
import app.kinframe.core.leaving.KeepRequest
import app.kinframe.core.leaving.KeepRequestStatus
import app.kinframe.core.leaving.PhotosChoice
import app.kinframe.core.leaving.TakeAwayResult
import app.kinframe.core.leaving.TakenAwayGroup
import app.kinframe.core.social.PersonRef
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.util.Format

// ------------------------------------------------------------------------------------------------- small pieces

@Composable
private fun Choice(text: String, hint: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(text, style = Kin.type.bodyStrong, color = Kin.colors.ink)
            if (hint != null) Text(hint, style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
    }
}

@Composable
private fun Check(text: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(text, style = Kin.type.body, color = if (enabled) Kin.colors.ink else Kin.colors.inkFaint)
            if (hint != null) Text(hint, style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
    }
}

/** Account deletion (leaving.md story 7): take (default) or leave the photos to the families. */
@Composable
fun AccountDeletionChoice(take: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Choice(stringResource(R.string.leaving_delete_take), stringResource(R.string.leaving_delete_take_hint), take, enabled) { onChange(true) }
        Choice(stringResource(R.string.leaving_delete_leave), stringResource(R.string.leaving_delete_leave_hint), !take, enabled) { onChange(false) }
    }
}

/** The line after leaving or taking away: "214 photos were taken away. They stay in the frames' trash until 19 December." */
fun resultText(res: android.content.res.Resources, r: TakeAwayResult?): String {
    if (r == null || r.photosTakenAway == 0) return res.getString(R.string.leaving_done_kept)
    val n = res.getQuantityString(R.plurals.leaving_done_taken, r.photosTakenAway, r.photosTakenAway)
    val until = r.until?.let { res.getString(R.string.leaving_done_until, Format.date(it)) }.orEmpty()
    val remote = if (r.remoteFrames > 0) " " + res.getQuantityString(R.plurals.leaving_done_remote, r.remoteFrames, r.remoteFrames) else ""
    return listOf(n, until).filter { it.isNotBlank() }.joinToString(" ") + remote
}

// ------------------------------------------------------------------------------------------------- leave sheets

/**
 * "Leave Janssens" (leaving.md story 1) or "Leave Oma's frame" (story 6): the photo choice, for families also the
 * comments and the frames to leave too, from `getLeavePreview`.
 */
@Composable
fun LeaveSheet(state: LeaveSheetState, family: Boolean, onPhotos: (String) -> Unit, onComments: (String) -> Unit, onFrame: (String) -> Unit,
               onRetry: () -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val c = Kin.colors
    val p = (state.preview as? Load.Ready)?.value
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(if (family) R.string.leaving_leave_family_title else R.string.leaving_leave_frame_title, p?.name.orEmpty())) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val pv = state.preview) {
                    Load.Loading -> SkeletonList(2, 48.dp)
                    is Load.Error -> ErrorPanel(pv.message, onRetry)
                    is Load.Ready -> {
                        val pr = pv.value
                        if (pr.owner) Note(stringResource(R.string.leaving_owner_first), tone = NoteTone.Warn)
                        Text(pluralStringResource(R.plurals.leaving_your_photos, pr.photos, pr.photos), style = Kin.type.bodyStrong, color = c.ink)
                        Choice(stringResource(if (family) R.string.leaving_photos_keep_family else R.string.leaving_photos_keep_frame),
                            stringResource(R.string.leaving_photos_keep_hint), state.photos == PhotosChoice.KEEP, !state.busy) { onPhotos(PhotosChoice.KEEP) }
                        Choice(stringResource(R.string.leaving_photos_take), stringResource(R.string.leaving_photos_take_hint),
                            state.photos == PhotosChoice.TAKE, !state.busy && pr.photos > 0) { onPhotos(PhotosChoice.TAKE) }
                        if (family) {
                            Text(pluralStringResource(R.plurals.leaving_your_comments, pr.comments, pr.comments), style = Kin.type.bodyStrong, color = c.ink)
                            Choice(stringResource(R.string.leaving_comments_keep), null, state.comments == CommentsChoice.KEEP, !state.busy) { onComments(CommentsChoice.KEEP) }
                            Choice(stringResource(R.string.leaving_comments_delete), null, state.comments == CommentsChoice.DELETE, !state.busy && pr.comments > 0) {
                                onComments(CommentsChoice.DELETE)
                            }
                            if (pr.frames.isNotEmpty()) {
                                Text(stringResource(R.string.leaving_frames_title), style = Kin.type.bodyStrong, color = c.ink)
                                pr.frames.forEach { f ->
                                    Check(stringResource(R.string.leaving_frame_too, f.frameName),
                                        if (!f.canLeave) stringResource(R.string.leaving_frame_owner) else null,
                                        f.frameId in state.frames, f.canLeave && !state.busy) { onFrame(f.frameId) }
                                }
                            }
                        }
                        Text(stringResource(R.string.leaving_later_hint), style = Kin.type.caption, color = c.inkSoft)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onLeave, enabled = p != null && !p.owner && !state.busy) {
                Text(stringResource(if (state.busy) R.string.common_loading else R.string.leaving_leave), color = c.danger)
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------------- former places

/** Settings → "Places you left": take your photos away from families and frames you no longer belong to. */
@Composable
fun FormerPlacesScreen(state: FormerPlacesState, vm: FormerPlacesViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    var confirm by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    Column(Modifier.fillMaxSize()) {
        KinTopBar(stringResource(R.string.leaving_former_title), onBack = onBack)
        when (val pl = state.places) {
            Load.Loading -> SkeletonList()
            is Load.Error -> ErrorPanel(pl.message, { vm.load() })
            is Load.Ready -> PageColumn {
                Text(stringResource(R.string.leaving_former_lede), style = Kin.type.body, color = c.inkSoft)
                if (pl.value.feeds.isEmpty() && pl.value.frames.isEmpty()) {
                    EmptyState(stringResource(R.string.leaving_former_empty), "", art = {})
                }
                pl.value.feeds.forEach { f ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(f.name, style = Kin.type.bodyStrong, color = c.ink)
                        Text(pluralStringResource(R.plurals.leaving_former_photos, f.photos, f.photos), style = Kin.type.caption, color = c.inkSoft)
                        SecondaryButton(stringResource(R.string.leaving_take_away_from, f.name), { confirm = Triple(f.feedId, f.name, true) }, enabled = !state.busy && f.photos > 0)
                    }
                }
                pl.value.frames.forEach { f ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(f.name, style = Kin.type.bodyStrong, color = c.ink)
                        Text(pluralStringResource(R.plurals.leaving_former_photos, f.photos, f.photos), style = Kin.type.caption, color = c.inkSoft)
                        SecondaryButton(stringResource(R.string.leaving_take_away_from, f.name), { confirm = Triple(f.frameId, f.name, false) }, enabled = !state.busy && f.photos > 0)
                    }
                }
            }
        }
    }
    confirm?.let { (id, name, family) ->
        var comments by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.leaving_take_away_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.leaving_photos_take_hint))
                    if (family) Check(stringResource(R.string.leaving_comments_delete), null, comments) { comments = it }
                }
            },
            confirmButton = {
                TextButton({ confirm = null; if (family) vm.takeAwayFromFeed(id, comments) else vm.takeAwayFromFrame(id) }) {
                    Text(stringResource(R.string.leaving_take_away), color = c.danger)
                }
            },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------- keep requests

private fun statusText(res: android.content.res.Resources, r: KeepRequest): String = res.getString(when (r.status) {
    KeepRequestStatus.OPEN -> R.string.leaving_keep_status_open
    KeepRequestStatus.ALLOWED -> R.string.leaving_keep_status_allowed
    KeepRequestStatus.PARTLY_ALLOWED -> R.string.leaving_keep_status_partly
    KeepRequestStatus.DECLINED -> R.string.leaving_keep_status_declined
    else -> R.string.leaving_keep_status_expired
})

/** The person's open keep requests: "Lies asks to keep 12 photos on Oma Janssens's frame." */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeepRequestsScreen(state: KeepRequestsState, vm: KeepRequestsViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize()) {
        KinTopBar(stringResource(R.string.leaving_keep_title), onBack = onBack)
        when (val rq = state.requests) {
            Load.Loading -> SkeletonList()
            is Load.Error -> ErrorPanel(rq.message, { vm.load() })
            is Load.Ready -> PageColumn {
                if (rq.value.isEmpty()) EmptyState(stringResource(R.string.leaving_keep_empty), "", art = {})
                rq.value.forEach { r ->
                    Surface(Modifier.fillMaxWidth(), onClick = if (r.isOpen) ({ vm.open(r.id) }) else null) {
                        Text(pluralStringResource(R.plurals.leaving_keep_line, r.itemCount, r.askedByName, r.itemCount, r.placeName),
                            style = Kin.type.bodyStrong, color = c.ink)
                        r.note?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = Kin.type.body, color = c.inkSoft) }
                        Text(if (r.isOpen) stringResource(R.string.leaving_keep_until, Format.date(r.expiresAt)) else statusText(androidx.compose.ui.platform.LocalResources.current, r),
                            style = Kin.type.caption, color = c.inkSoft)
                    }
                }
            }
        }
    }
    state.open?.let { o -> KeepAnswerDialog(state, o, vm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepAnswerDialog(state: KeepRequestsState, open: Load<KeepRequest>, vm: KeepRequestsViewModel) {
    val c = Kin.colors
    val r = (open as? Load.Ready)?.value
    AlertDialog(
        onDismissRequest = { if (!state.busy) vm.close() },
        title = { Text(r?.let { pluralStringResource(R.plurals.leaving_keep_line, it.itemCount, it.askedByName, it.itemCount, it.placeName) }.orEmpty()) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (open) {
                    Load.Loading -> SkeletonList(1, 120.dp)
                    is Load.Error -> ErrorPanel(open.message, null)
                    is Load.Ready -> {
                        Text(stringResource(R.string.leaving_keep_answer_hint), style = Kin.type.caption, color = c.inkSoft)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            open.value.items.forEach { i ->
                                val on = i.item.id in state.allowed
                                Thumb(i.item.thumbUrl, null, Modifier.size(72.dp)
                                    .border(if (on) 3.dp else 0.dp, if (on) c.accent else c.paperDeep, RoundedCornerShape(12.dp))
                                    .clickable(onClickLabel = stringResource(R.string.leaving_keep_toggle)) { vm.toggle(i.item.id) })
                            }
                        }
                        Text(stringResource(R.string.leaving_keep_picked, state.allowed.size, open.value.items.size), style = Kin.type.caption, color = c.inkSoft)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({ vm.answer(KeepAnswer.NONE) }, enabled = r != null && !state.busy) { Text(stringResource(R.string.leaving_keep_decline), color = c.danger) }
                TextButton({ vm.answer(KeepAnswer.SOME) }, enabled = r != null && !state.busy) {
                    Text(stringResource(if (r != null && state.allowed.size == r.items.size) R.string.leaving_keep_allow_all else R.string.leaving_keep_allow_some))
                }
            }
        },
        dismissButton = { TextButton({ vm.close() }, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------------- taken away

/** "Taken away by Tom" groups in a frame's trash or a family's settings, with "Ask Tom to keep them". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TakenAwaySection(state: TakenAwayState, vm: TakenAwayViewModel) {
    val c = Kin.colors
    val groups = (state.groups as? Load.Ready)?.value.orEmpty()
    if (groups.isEmpty()) return
    SectionTitle(stringResource(R.string.leaving_taken_title))
    groups.forEach { g ->
        Surface(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.leaving_taken_by, g.byName), style = Kin.type.bodyStrong, color = c.ink)
            Text(pluralStringResource(R.plurals.leaving_taken_line, g.count, g.count, Format.date(g.takenAt), Format.date(g.until)), style = Kin.type.caption, color = c.inkSoft)
            if (g.previewThumbUrls.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.previewThumbUrls.take(6).forEach { Thumb(it, null, Modifier.size(48.dp), corner = 8.dp) }
            }
            when {
                g.keepRequest != null -> Text(stringResource(R.string.leaving_taken_asked, g.keepRequest!!.let { statusText(androidx.compose.ui.platform.LocalResources.current, it) }),
                    style = Kin.type.caption, color = c.inkSoft)
                g.canAsk -> SecondaryButton(stringResource(R.string.leaving_ask_button, g.byName), { vm.ask(g) }, enabled = !state.busy)
                g.cannotAskReason == CannotAskReason.BLOCKED -> Text(stringResource(R.string.leaving_ask_blocked, g.byName), style = Kin.type.caption, color = c.inkSoft)
                g.cannotAskReason == CannotAskReason.ACCOUNT_DELETED -> Text(stringResource(R.string.leaving_ask_deleted), style = Kin.type.caption, color = c.inkSoft)
                g.cannotAskReason == CannotAskReason.EXPIRED -> Text(stringResource(R.string.leaving_problem_expired), style = Kin.type.caption, color = c.inkSoft)
            }
        }
    }
    state.asking?.let { g -> AskToKeepDialog(state, g, vm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskToKeepDialog(state: TakenAwayState, g: TakenAwayGroup, vm: TakenAwayViewModel) {
    val c = Kin.colors
    AlertDialog(
        onDismissRequest = { if (!state.busy) vm.cancelAsk() },
        title = { Text(stringResource(R.string.leaving_ask_button, g.byName)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.leaving_ask_hint, g.byName), style = Kin.type.caption, color = c.inkSoft)
                when (val ph = state.photos) {
                    Load.Loading -> SkeletonList(1, 120.dp)
                    is Load.Error -> ErrorPanel(ph.message, null)
                    is Load.Ready -> if (ph.value.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ph.value.forEach { p ->
                                val on = p.item.id in state.picked
                                Thumb(p.item.thumbUrl, null, Modifier.size(72.dp)
                                    .border(if (on) 3.dp else 0.dp, if (on) c.accent else c.paperDeep, RoundedCornerShape(12.dp))
                                    .clickable(onClickLabel = stringResource(R.string.leaving_keep_toggle)) { vm.togglePhoto(p.item.id) })
                            }
                        }
                        Text(stringResource(R.string.leaving_keep_picked, state.picked.size, ph.value.size), style = Kin.type.caption, color = c.inkSoft)
                    }
                }
                app.kinframe.family.ui.KinTextField(state.note, vm::onNote, label = stringResource(R.string.leaving_ask_note))
            }
        },
        confirmButton = { TextButton(vm::send, enabled = !state.busy) { Text(stringResource(R.string.leaving_ask_send)) } },
        dismissButton = { TextButton(vm::cancelAsk, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------------- memories

/** Settings → Memories (leaving.md story 8): leave people out of On this day, recaps, suggestions and summaries. */
@Composable
fun MemoriesScreen(state: MemoriesState, vm: MemoriesViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        KinTopBar(stringResource(R.string.leaving_memories_title), onBack = onBack)
        when (val p = state.prefs) {
            Load.Loading -> SkeletonList()
            is Load.Error -> ErrorPanel(p.message, { vm.load() })
            is Load.Ready -> PageColumn {
                Text(stringResource(R.string.leaving_memories_lede), style = Kin.type.body, color = c.inkSoft)
                SectionTitle(stringResource(R.string.leaving_memories_people))
                if (p.value.hiddenPeople.isEmpty()) Text(stringResource(R.string.leaving_memories_people_none), style = Kin.type.caption, color = c.inkSoft)
                p.value.hiddenPeople.forEach { person ->
                    Surface(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(person.displayName, person.avatarUrl)
                            Text(person.displayName, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                            QuietButton(stringResource(R.string.leaving_memories_show), { vm.showPerson(person) }, enabled = !state.busy)
                        }
                    }
                }
                SecondaryButton(stringResource(R.string.leaving_memories_add), { picking = true }, enabled = !state.busy, icon = KinIcons.People)
                SectionTitle(stringResource(R.string.leaving_memories_hidden))
                Text(pluralStringResource(R.plurals.leaving_memories_hidden_count, p.value.hiddenMemories.size, p.value.hiddenMemories.size),
                    style = Kin.type.caption, color = c.inkSoft)
                if (p.value.hiddenMemories.isNotEmpty()) QuietButton(stringResource(R.string.leaving_memories_show_all), vm::showAllMemories, enabled = !state.busy)
                Note(stringResource(R.string.leaving_memories_private))
            }
        }
    }
    if (picking) {
        val hidden = (state.prefs as? Load.Ready)?.value?.hiddenPeople.orEmpty().map { it.userId }.toSet()
        PersonPicker(state.people.filter { it.userId !in hidden }, { picking = false }) { picking = false; vm.hidePerson(it) }
    }
}

@Composable
private fun PersonPicker(people: List<PersonRef>, onDismiss: () -> Unit, onPick: (PersonRef) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leaving_memories_add)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (people.isEmpty()) Text(stringResource(R.string.leaving_memories_no_people))
                people.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(p.displayName, p.avatarUrl, 32.dp)
                        Text(p.displayName, style = Kin.type.body, color = Kin.colors.ink)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------------- settings entries

/** Settings → Memories, keep requests and places you left (only with discovery `leaving`). */
@Composable
fun LeavingSettingsSection(openKeepRequests: Int, onMemories: () -> Unit, onKeepRequests: () -> Unit, onFormerPlaces: () -> Unit) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.leaving_settings_title))
    Surface(Modifier.fillMaxWidth(), onClick = onMemories) {
        Text(stringResource(R.string.leaving_memories_title), style = Kin.type.bodyStrong, color = c.ink)
        Text(stringResource(R.string.leaving_memories_entry_hint), style = Kin.type.caption, color = c.inkSoft)
    }
    Surface(Modifier.fillMaxWidth(), onClick = onKeepRequests) {
        Text(stringResource(R.string.leaving_keep_title), style = Kin.type.bodyStrong, color = c.ink)
        Text(if (openKeepRequests > 0) pluralStringResource(R.plurals.leaving_keep_open, openKeepRequests, openKeepRequests)
            else stringResource(R.string.leaving_keep_entry_hint), style = Kin.type.caption, color = if (openKeepRequests > 0) c.accent else c.inkSoft)
    }
    Surface(Modifier.fillMaxWidth(), onClick = onFormerPlaces) {
        Text(stringResource(R.string.leaving_former_title), style = Kin.type.bodyStrong, color = c.ink)
        Text(stringResource(R.string.leaving_former_entry_hint), style = Kin.type.caption, color = c.inkSoft)
    }
}
