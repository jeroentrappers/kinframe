package app.kinframe.family.ui.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.CommandType
import app.kinframe.core.api.Frame
import app.kinframe.core.api.FrameHealth
import app.kinframe.core.api.Invite
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.Member
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.Role as FrameRole
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.ActionTile
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.FrameTab
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.OnlineStatus
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.RoleBadge
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format

@Composable
fun FrameDetailScreen(
    state: FrameDetailState,
    tab: FrameTab,
    onTab: (FrameTab) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    overview: FrameActions,
    library: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    val c = Kin.colors
    val frame = (state.frame as? Load.Ready)?.value
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(frame?.name ?: "", onBack = onBack) {
            if (frame?.can { it.send } == true) IconButton(onSend) { Icon(KinIcons.Send, stringResource(R.string.frames_send_to, frame.name), tint = c.accent) }
        }
        when (val f = state.frame) {
            Load.Loading -> SkeletonList(3, 140.dp)
            is Load.Error -> ErrorPanel(f.message, onRetry)
            is Load.Ready -> {
                val tabs = buildList {
                    add(FrameTab.Overview); add(FrameTab.Library); add(FrameTab.People)
                    if (f.value.can { it.changeSettings }) add(FrameTab.Settings)
                }
                val current = if (tab in tabs) tab else FrameTab.Overview
                PrimaryScrollableTabRow(
                    selectedTabIndex = tabs.indexOf(current), containerColor = c.paper, contentColor = c.accent, edgePadding = 12.dp,
                    divider = { HorizontalDivider(color = c.line) },
                    indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tabs.indexOf(current), matchContentSize = true), color = c.accent) },
                ) {
                    tabs.forEach { t ->
                        Tab(
                            selected = t == current, onClick = { onTab(t) },
                            text = {
                                Text(stringResource(when (t) {
                                    FrameTab.Overview -> R.string.frame_tab_overview
                                    FrameTab.Library -> R.string.frame_tab_library
                                    FrameTab.People -> R.string.frame_tab_people
                                    FrameTab.Settings -> R.string.frame_tab_settings
                                }), style = Kin.type.label)
                            },
                            selectedContentColor = c.accent, unselectedContentColor = c.inkSoft,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (current) {
                        FrameTab.Overview -> OverviewTab(f.value, state, overview)
                        FrameTab.Library -> library()
                        FrameTab.People -> PeopleTab(f.value, state, overview)
                        FrameTab.Settings -> settings()
                    }
                }
            }
        }
    }
}

/** Everything the overview and people tabs can do. */
class FrameActions(
    val me: String?,
    val command: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val setFlag: (String, Boolean) -> Unit,
    val leave: (Boolean) -> Unit,
    val remove: () -> Unit,
    val decide: (JoinRequest, Boolean) -> Unit,
    val updateMember: (Member, String?, Permissions?) -> Unit,
    val removeMember: (Member, Boolean) -> Unit,
    val createInvite: (Boolean, Int, String) -> Unit,
    val shareInvite: (Invite) -> Unit,
    val copyInvite: (Invite) -> Unit,
    val revokeInvite: (Invite) -> Unit,
)

@Composable
private fun OverviewTab(frame: Frame, state: FrameDetailState, a: FrameActions) {
    val c = Kin.colors
    val context = LocalContext.current
    var leaving by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    PageColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FrameArt(width = 112.dp, portrait = frame.screen?.orientation == "portrait")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleBadge(frame.role)
                frame.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
                OnlineStatus(frame.online, frame.lastSeenAt)
            }
            if (frame.isManager) IconButton({ renaming = true }) { Icon(KinIcons.Edit, stringResource(R.string.overview_rename), tint = c.inkSoft) }
        }
        if (frame.can { it.changeSettings }) {
            SectionTitle(stringResource(R.string.overview_remote))
            if (!frame.online) Note(stringResource(R.string.overview_offline_control))
            val cmds = listOf(
                CommandType.PREVIOUS to (KinIcons.SkipBack to R.string.cmd_previous),
                CommandType.NEXT to (KinIcons.SkipForward to R.string.cmd_next),
                CommandType.SLEEP to (KinIcons.Moon to R.string.cmd_sleep),
                CommandType.WAKE to (KinIcons.Sun to R.string.cmd_wake),
                CommandType.IDENTIFY to (KinIcons.Target to R.string.cmd_identify),
                CommandType.SYNC_NOW to (KinIcons.Refresh to R.string.cmd_sync),
            )
            cmds.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (type, v) ->
                        ActionTile(v.first, stringResource(v.second), { a.command(type) }, Modifier.weight(1f), enabled = frame.online && state.busyCommand == null)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.overview_details))
        Surface(Modifier.fillMaxWidth()) {
            frame.ownerName?.let { Detail(stringResource(R.string.overview_owner), it) }
            frame.memberCount?.let { Detail(stringResource(R.string.overview_members), it.toString()) }
            frame.placementCount?.let { Detail(stringResource(R.string.overview_photos), it.toString()) }
            frame.screen?.let { Detail(stringResource(R.string.overview_screen), "${it.width} × ${it.height}") }
            frame.createdAt?.let { Detail(stringResource(R.string.overview_since), Format.date(it)) }
        }
        state.health?.let { HealthCard(it) }
        if (frame.needsUpdate) Note(stringResource(R.string.frames_needs_update), tone = NoteTone.Warn)
        if (frame.role == FrameRole.OWNER) {
            SectionTitle(stringResource(R.string.overview_remove_title))
            Text(stringResource(R.string.overview_remove_body), style = Kin.type.caption, color = c.inkSoft)
            QuietButton(stringResource(R.string.overview_remove_button), { removing = true }, danger = true)
        } else {
            SectionTitle(stringResource(R.string.overview_leave_title))
            Text(stringResource(R.string.overview_leave_body), style = Kin.type.caption, color = c.inkSoft)
            SecondaryButton(stringResource(R.string.overview_leave_button), { leaving = true }, icon = KinIcons.SignOut)
        }
    }
    if (renaming) RenameDialog(frame, { renaming = false }) { n, l -> renaming = false; a.rename(n, l) }
    if (leaving) {
        var removeMine by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.overview_leave_confirm, frame.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.overview_leave_dialog_body))
                    CheckRow(stringResource(R.string.overview_remove_mine), removeMine) { removeMine = it }
                }
            },
            confirmButton = { TextButton({ leaving = false; a.leave(removeMine) }) { Text(stringResource(R.string.overview_leave_button), color = c.danger) } },
            dismissButton = { TextButton({ leaving = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (removing) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.overview_remove_confirm, frame.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.overview_remove_dialog_body))
                    KinTextField(typed, { typed = it }, stringResource(R.string.overview_type_name, frame.name))
                }
            },
            confirmButton = {
                TextButton({ removing = false; a.remove() }, enabled = typed.trim() == frame.name.trim()) {
                    Text(stringResource(R.string.overview_remove_button), color = if (typed.trim() == frame.name.trim()) c.danger else c.inkFaint)
                }
            },
            dismissButton = { TextButton({ removing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun RenameDialog(frame: Frame, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(frame.name) }
    var loc by remember { mutableStateOf(frame.location.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overview_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KinTextField(name, { name = it.take(40) }, stringResource(R.string.frame_name))
                KinTextField(loc, { loc = it.take(40) }, stringResource(R.string.frame_location))
            }
        },
        confirmButton = { TextButton({ onSave(name, loc) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f))
        Text(value, style = Kin.type.bodyStrong, color = Kin.colors.ink)
    }
}

@Composable
private fun HealthCard(h: FrameHealth) {
    val context = LocalContext.current
    val hb = h.lastHeartbeat ?: return
    SectionTitle(stringResource(R.string.overview_health))
    Surface(Modifier.fillMaxWidth()) {
        hb.wifiRssiDbm?.let { Detail(stringResource(R.string.health_wifi), listOfNotNull(hb.wifiSsid, "$it dBm").joinToString(" · ")) }
        if (hb.storageFreeBytes != null && hb.storageTotalBytes != null) {
            Detail(stringResource(R.string.health_storage), stringResource(R.string.health_free, Format.bytes(context, hb.storageFreeBytes!!), Format.bytes(context, hb.storageTotalBytes!!)))
        }
        hb.placementsCached?.let { Detail(stringResource(R.string.health_cached), it.toString()) }
        hb.placementsMissing?.takeIf { it > 0 }?.let { Text(pluralStringResource(R.plurals.health_missing, it, it), style = Kin.type.caption, color = Kin.colors.inkSoft) }
        Detail(stringResource(R.string.health_uptime), Format.duration(context, roundUptime(hb.uptimeS)))
        Detail(stringResource(R.string.health_version), hb.appVersion)
        h.lastSeenAt?.let { Detail(stringResource(R.string.health_last_seen), Format.relative(it)) }
        h.lastUncleanShutdownAt?.let { Text(stringResource(R.string.health_unclean, Format.relative(it)), style = Kin.type.caption, color = Kin.colors.warn) }
    }
}

/** Whole days, hours or minutes, so "Running for" reads naturally. */
private fun roundUptime(s: Long): Long = when {
    s >= 86_400 -> s / 86_400 * 86_400
    s >= 3600 -> s / 3600 * 3600
    else -> (s / 60 * 60).coerceAtLeast(60)
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = Kin.colors.accent))
        Text(text, style = Kin.type.body, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleTab(frame: Frame, state: FrameDetailState, a: FrameActions) {
    val c = Kin.colors
    var editing by remember { mutableStateOf<Member?>(null) }
    PageColumn {
        if (state.requests.isNotEmpty()) {
            SectionTitle(stringResource(R.string.people_requests))
            state.requests.forEach { r ->
                Surface(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(r.user.displayName.orEmpty(), r.user.avatarUrl)
                        Column(Modifier.weight(1f)) {
                            Text(r.user.displayName.orEmpty(), style = Kin.type.bodyStrong, color = c.ink)
                            Text(
                                if (r.via == "invite") stringResource(R.string.people_via_invite, r.invitedByName.orEmpty()) else stringResource(R.string.people_via_code),
                                style = Kin.type.caption, color = c.inkSoft,
                            )
                            r.note?.let { Text("“$it”", style = Kin.type.caption, color = c.inkSoft) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(stringResource(R.string.people_approve), { a.decide(r, true) }, enabled = !state.busy)
                        QuietButton(stringResource(R.string.people_deny), { a.decide(r, false) }, enabled = !state.busy)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.people_members))
        Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
            state.members.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = c.line)
                val editable = frame.isManager && m.role != FrameRole.OWNER && m.userId != a.me && (frame.role == FrameRole.OWNER || m.role == FrameRole.MEMBER)
                Row(
                    Modifier.fillMaxWidth().let { if (editable) it.clickable(role = Role.Button) { editing = m } else it }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(m.displayName, m.avatarUrl)
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName + if (m.userId == a.me) " (" + stringResource(R.string.people_you) + ")" else "",
                            style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.lastSentAt?.let { stringResource(R.string.people_last_sent, Format.relative(it)) } ?: stringResource(R.string.people_never_sent),
                            style = Kin.type.caption, color = c.inkSoft)
                    }
                    RoleBadge(m.role)
                    if (editable) Icon(KinIcons.ChevronRight, stringResource(R.string.people_edit_member, m.displayName), Modifier.size(20.dp), tint = c.inkFaint)
                }
            }
        }
        if (frame.can { it.invite }) InviteSection(frame, state, a)
        if (frame.isManager) {
            SwitchRow(stringResource(R.string.people_invite_hint_approval), frame.inviteRequiresApproval ?: true, { a.setFlag("invite_requires_approval", it) })
        }
    }
    editing?.let { m -> MemberDialog(m, frame, { editing = null }, a) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteSection(frame: Frame, state: FrameDetailState, a: FrameActions) {
    val c = Kin.colors
    var single by rememberSaveable { mutableStateOf(true) }
    var hours by rememberSaveable { mutableStateOf(168) }
    var note by rememberSaveable { mutableStateOf("") }
    SectionTitle(stringResource(R.string.people_invite))
    Surface(Modifier.fillMaxWidth()) {
        Text(stringResource(if (frame.inviteRequiresApproval != false) R.string.people_invite_hint_approval else R.string.people_invite_hint), style = Kin.type.caption, color = c.inkSoft)
        Text(stringResource(R.string.people_expires), style = Kin.type.label, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24 to R.string.people_exp_1d, 168 to R.string.people_exp_7d, 720 to R.string.people_exp_30d).forEach { (h, l) ->
                ToggleChip(stringResource(l), hours == h, { hours = h })
            }
        }
        Text(stringResource(R.string.people_uses), style = Kin.type.label, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.people_uses_single), single, { single = true })
            ToggleChip(stringResource(R.string.people_uses_unlimited), !single, { single = false })
        }
        KinTextField(note, { note = it.take(80) }, stringResource(R.string.people_note), placeholder = stringResource(R.string.people_note_placeholder))
        PrimaryButton(stringResource(R.string.people_create), { a.createInvite(single, hours, note); note = "" }, busy = state.creatingInvite, icon = KinIcons.Share)
    }
    if (state.invites.isNotEmpty()) {
        SectionTitle(stringResource(R.string.people_active_invites))
        state.invites.forEach { inv ->
            Surface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(inv.note ?: inv.url.substringAfter("://"), style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val uses = inv.maxUses?.let { stringResource(R.string.people_uses_of, inv.uses, it) } ?: stringResource(R.string.people_uses_n, inv.uses)
                        Text("$uses · " + stringResource(R.string.people_expires_in, Format.relative(inv.expiresAt)), style = Kin.type.caption, color = c.inkSoft)
                        inv.createdByName?.let { Text(stringResource(R.string.people_by_name, it), style = Kin.type.caption, color = c.inkFaint) }
                    }
                    IconButton({ a.copyInvite(inv) }) { Icon(KinIcons.Copy, stringResource(R.string.people_copy), tint = c.inkSoft) }
                    IconButton({ a.shareInvite(inv) }) { Icon(KinIcons.Share, stringResource(R.string.people_share), tint = c.accent) }
                    IconButton({ a.revokeInvite(inv) }) { Icon(KinIcons.Trash, stringResource(R.string.people_revoke), tint = c.danger) }
                }
            }
        }
    }
}

@Composable
private fun MemberDialog(m: Member, frame: Frame, onDismiss: () -> Unit, a: FrameActions) {
    val c = Kin.colors
    var role by remember { mutableStateOf(m.role) }
    var perms by remember { mutableStateOf(m.permissions) }
    var removing by remember { mutableStateOf(false) }
    var removeItems by remember { mutableStateOf(false) }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.people_remove_confirm, m.displayName)) },
            text = {
                Column {
                    Text(stringResource(R.string.people_remove_body))
                    CheckRow(stringResource(R.string.people_remove_items), removeItems) { removeItems = it }
                }
            },
            confirmButton = { TextButton({ removing = false; onDismiss(); a.removeMember(m, removeItems) }) { Text(stringResource(R.string.people_remove), color = c.danger) } },
            dismissButton = { TextButton({ removing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_edit_member, m.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (frame.role == FrameRole.OWNER) {
                    Text(stringResource(R.string.people_role), style = Kin.type.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(stringResource(R.string.role_admin), role == FrameRole.ADMIN, { role = FrameRole.ADMIN })
                        ToggleChip(stringResource(R.string.role_member), role == FrameRole.MEMBER, { role = FrameRole.MEMBER })
                    }
                }
                Text(stringResource(if (role == FrameRole.ADMIN) R.string.people_admin_hint else R.string.people_member_hint), style = Kin.type.caption, color = c.inkSoft)
                if (role == FrameRole.MEMBER) {
                    PermissionSwitch(R.string.perm_send, R.string.perm_send_hint, perms.send) { perms = perms.copy(send = it) }
                    PermissionSwitch(R.string.perm_invite, R.string.perm_invite_hint, perms.invite) { perms = perms.copy(invite = it) }
                    PermissionSwitch(R.string.perm_see_library, R.string.perm_see_library_hint, perms.seeLibrary) { perms = perms.copy(seeLibrary = it) }
                    PermissionSwitch(R.string.perm_manage_library, R.string.perm_manage_library_hint, perms.manageLibrary) { perms = perms.copy(manageLibrary = it) }
                    PermissionSwitch(R.string.perm_change_settings, R.string.perm_change_settings_hint, perms.changeSettings) { perms = perms.copy(changeSettings = it) }
                }
                QuietButton(stringResource(R.string.people_remove), { removing = true }, danger = true)
            }
        },
        confirmButton = {
            TextButton({ onDismiss(); a.updateMember(m, role.takeIf { it != m.role }, perms.takeIf { it != m.permissions }) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PermissionSwitch(title: Int, hint: Int, value: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(stringResource(title), value, onChange, hint = stringResource(hint))
