package app.kinframe.family.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FeedMember
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Circle
import app.kinframe.core.social.CircleRole
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface

/** A family's circles: smaller groups with their own chat, and an audience for posts. */
@Composable
fun CirclesScreen(s: CirclesState, vm: CirclesViewModel, onBack: () -> Unit, onOpen: (Circle) -> Unit) {
    val c = Kin.colors
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.social_circles_title, s.feed?.name.orEmpty()), onBack = onBack) {
            if (s.canCreate) IconButton({ creating = true }) { Icon(KinIcons.Plus, stringResource(R.string.social_circle_new), tint = c.accent) }
        }
        when (val l = s.circles) {
            Load.Loading -> SkeletonList(3, 72.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn(spacing = 10.dp) {
                Text(stringResource(R.string.social_circles_lede), style = Kin.type.body, color = c.inkSoft)
                if (l.value.isEmpty()) EmptyState(stringResource(R.string.social_circles_empty_title), stringResource(R.string.social_circles_empty_body)) {
                    if (s.canCreate) PrimaryButton(stringResource(R.string.social_circle_new), { creating = true }, icon = KinIcons.Plus)
                }
                l.value.forEach { circle ->
                    Surface(Modifier.fillMaxWidth(), onClick = { onOpen(circle) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(KinIcons.People, null, tint = c.inkSoft)
                            Column(Modifier.weight(1f)) {
                                Text(circle.name, style = Kin.type.bodyStrong, color = c.ink)
                                Text(pluralStringResource(R.plurals.feed_members_count, circle.memberCount, circle.memberCount), style = Kin.type.caption, color = c.inkSoft)
                            }
                            if (!circle.isMember) Pill(stringResource(R.string.social_circle_not_in), c.paperDeep, c.inkSoft)
                            else if (circle.isAdmin) Pill(stringResource(R.string.role_admin), c.sageSoft, c.onSageSoft)
                            Icon(KinIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.inkFaint)
                        }
                    }
                }
            }
        }
    }
    if (creating) CreateCircleDialog(s, { creating = false }) { name, members, frames -> creating = false; vm.create(name, members, frames) }
}

@Composable
private fun CreateCircleDialog(s: CirclesState, onDismiss: () -> Unit, onCreate: (String, List<String>, List<String>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.social_circle_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KinTextField(name, { name = it.take(CirclesViewModel.NAME_MAX) }, stringResource(R.string.social_circle_name), placeholder = stringResource(R.string.social_circle_name_placeholder))
                Text(stringResource(R.string.social_circle_pick_members), style = Kin.type.label)
                MemberChecklist(s.members, picked) { id -> picked = if (id in picked) picked - id else picked + id }
            }
        },
        confirmButton = { TextButton({ onCreate(name, picked.toList(), emptyList()) }, enabled = name.isNotBlank() && !s.busy) { Text(stringResource(R.string.social_circle_create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun MemberChecklist(members: List<FeedMember>, picked: Set<String>, onToggle: (String) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(members, key = { it.userId }) { m ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onToggle(m.userId) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(m.userId in picked, null, colors = CheckboxDefaults.colors(checkedColor = Kin.colors.accent))
                Avatar(m.displayName, m.avatarUrl, 28.dp)
                Text(m.displayName, style = Kin.type.body, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One circle: name, members and roles, extra frames, its chat, leave or delete. */
@Composable
fun CircleScreen(s: CirclesState, vm: CirclesViewModel, circleId: String, me: String?, onBack: () -> Unit, onChat: ((String) -> Unit)?) {
    val c = Kin.colors
    val circle = (s.circles as? Load.Ready)?.value?.firstOrNull { it.id == circleId }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(circle?.name.orEmpty(), onBack = onBack)
        when {
            s.circles is Load.Loading -> SkeletonList(3, 64.dp)
            circle == null -> ErrorPanel(R.string.problem_not_found, { vm.load() })
            else -> {
                val manage = s.canManage(circle)
                var name by remember(circle.name) { mutableStateOf(circle.name) }
                PageColumn {
                    if (onChat != null && circle.conversationId != null && circle.isMember) PrimaryButton(stringResource(R.string.social_circle_chat), { onChat(circle.conversationId!!) }, Modifier.fillMaxWidth(), icon = KinIcons.Message)
                    if (manage) {
                        KinTextField(name, { name = it.take(CirclesViewModel.NAME_MAX) }, stringResource(R.string.social_circle_name))
                        SecondaryButton(stringResource(R.string.common_save), { vm.rename(circle, name) }, enabled = name.isNotBlank() && name.trim() != circle.name && !s.busy)
                    }
                    SectionTitle(stringResource(R.string.people_members), action = if (manage) ({ TextButton({ adding = true }) { Text(stringResource(R.string.chat_add_people), color = c.accent) } }) else null)
                    Surface(Modifier.fillMaxWidth(), padding = 6.dp) {
                        circle.members.forEachIndexed { i, m ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            var menu by remember { mutableStateOf(false) }
                            androidx.compose.foundation.layout.Box {
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = manage && m.userId != me) { menu = true }.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Avatar(m.displayName, m.avatarUrl, 32.dp)
                                    Text(m.displayName + if (m.userId == me) " (" + stringResource(R.string.social_you) + ")" else "", style = Kin.type.body, color = c.ink, modifier = Modifier.weight(1f))
                                    if (m.role == CircleRole.ADMIN) Pill(stringResource(R.string.role_admin), c.sageSoft, c.onSageSoft)
                                }
                                ActionMenu(menu, listOf(
                                    MenuAction(if (m.role == CircleRole.ADMIN) R.string.chat_remove_admin else R.string.chat_make_admin) { vm.setAdmin(circle, m.userId, m.role != CircleRole.ADMIN) },
                                    MenuAction(R.string.chat_remove_member, danger = true) { vm.removeMember(circle, m.userId) },
                                )) { menu = false }
                            }
                        }
                    }
                    SectionTitle(stringResource(R.string.social_circle_frames))
                    Text(stringResource(R.string.social_circle_frames_hint), style = Kin.type.caption, color = c.inkSoft)
                    if (circle.framesReached.isEmpty()) Note(stringResource(R.string.social_circle_no_frames))
                    else Text(circle.framesReached.joinToString(", ") { it.frameName }, style = Kin.type.body, color = c.ink)
                    if (manage && s.frames.isNotEmpty()) {
                        Text(stringResource(R.string.social_circle_extra_frames), style = Kin.type.label, color = c.ink)
                        s.frames.forEach { sub ->
                            val on = sub.frame.id in circle.frameIds
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                                vm.setFrames(circle, if (on) circle.frameIds - sub.frame.id else circle.frameIds + sub.frame.id)
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                                Text(sub.frame.name, style = Kin.type.body)
                            }
                        }
                    }
                    if (circle.isMember) QuietButton(stringResource(R.string.social_circle_leave), { vm.leave(circle) }, danger = true)
                    if (manage) QuietButton(stringResource(R.string.social_circle_delete), { deleting = true }, danger = true)
                }
                if (adding) {
                    var picked by remember { mutableStateOf(setOf<String>()) }
                    AlertDialog(
                        onDismissRequest = { adding = false },
                        title = { Text(stringResource(R.string.chat_add_people)) },
                        text = { MemberChecklist(s.members.filter { m -> circle.members.none { it.userId == m.userId } }, picked) { id -> picked = if (id in picked) picked - id else picked + id } },
                        confirmButton = { TextButton({ adding = false; vm.addMembers(circle, picked.toList()) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.chat_add)) } },
                        dismissButton = { TextButton({ adding = false }) { Text(stringResource(R.string.common_cancel)) } },
                    )
                }
                if (deleting) AlertDialog(
                    onDismissRequest = { deleting = false },
                    title = { Text(stringResource(R.string.social_circle_delete_title, circle.name)) },
                    text = { Text(stringResource(R.string.social_circle_delete_body)) },
                    confirmButton = { TextButton({ deleting = false; vm.delete(circle) }) { Text(stringResource(R.string.social_circle_delete), color = c.danger) } },
                    dismissButton = { TextButton({ deleting = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }
    }
}
