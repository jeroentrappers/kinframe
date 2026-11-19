package app.kinframe.family.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
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
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.servers.RemoteOnEnd
import app.kinframe.core.servers.RemoteStatus
import app.kinframe.core.servers.RemoteSubscription
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.util.Format

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(text, style = Kin.type.body, color = Kin.colors.ink, modifier = Modifier.padding(start = 8.dp))
    }
}

// ------------------------------------------------------------------------------------------------- accounts

/** One row of Settings → Accounts. */
data class AccountRow(val id: String, val label: String, val host: String, val name: String?, val primary: Boolean, val signedIn: Boolean)

/** Settings → Accounts (multi-server.md §2.1): every server the app is signed in to, add one, sign out of one, primary. */
@Composable
fun AccountsSection(rows: List<AccountRow>, onAdd: () -> Unit, onPrimary: (String) -> Unit, onSignOut: (String) -> Unit, onSettings: ((String) -> Unit)? = null) {
    val c = Kin.colors
    var signingOut by remember { mutableStateOf<AccountRow?>(null) }
    SectionTitle(stringResource(R.string.servers_accounts_title))
    rows.forEach { r ->
        Surface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = Kin.type.bodyStrong, color = c.ink)
                    Text(listOfNotNull(r.host, r.name?.let { stringResource(R.string.servers_accounts_as, it) }).joinToString(" · "),
                        style = Kin.type.caption, color = c.inkSoft)
                    if (!r.signedIn) Text(stringResource(R.string.servers_accounts_signed_out), style = Kin.type.caption, color = c.danger)
                }
                if (r.primary) Pill(stringResource(R.string.servers_accounts_primary), c.accentSoft, c.onAccentSoft)
            }
            // Several accounts: each server's own settings (reminders and chats, Telegram, memories, deleting the account).
            if (rows.size > 1 && r.signedIn && onSettings != null) {
                SecondaryButton(stringResource(R.string.servers_account_settings), { onSettings(r.id) }, Modifier.fillMaxWidth(), icon = KinIcons.Settings)
            }
            if (rows.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!r.primary) QuietButton(stringResource(R.string.servers_accounts_make_primary), { onPrimary(r.id) })
                QuietButton(stringResource(R.string.servers_accounts_sign_out), { signingOut = r }, danger = true)
            }
        }
    }
    Text(stringResource(R.string.servers_accounts_hint), style = Kin.type.caption, color = c.inkSoft)
    SecondaryButton(stringResource(R.string.servers_add_title), onAdd, Modifier.fillMaxWidth(), icon = KinIcons.Plus)
    signingOut?.let { r ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text(stringResource(R.string.servers_accounts_sign_out_title, r.label)) },
            text = { Text(stringResource(R.string.servers_accounts_sign_out_body)) },
            confirmButton = { TextButton({ signingOut = null; onSignOut(r.id) }) { Text(stringResource(R.string.servers_accounts_sign_out), color = c.danger) } },
            dismissButton = { TextButton({ signingOut = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------- remote follows (B)

private fun statusLabel(res: android.content.res.Resources, f: RemoteSubscription): String = res.getString(when (f.status) {
    RemoteStatus.PENDING -> R.string.servers_status_pending
    RemoteStatus.ACTIVE -> R.string.servers_status_active
    RemoteStatus.REJECTED -> R.string.servers_status_rejected
    RemoteStatus.ERROR -> R.string.servers_status_error
    else -> R.string.servers_status_removed
})

/** Frame → Families → "Families on other servers" (multi-server.md §3.1): add, list, on-end choice, unsubscribe. */
@Composable
fun RemoteFollowsSection(state: RemoteFollowsState, vm: RemoteFollowsViewModel, frameName: String, canManage: Boolean) {
    val c = Kin.colors
    val res = androidx.compose.ui.platform.LocalResources.current
    var removing by remember { mutableStateOf<RemoteSubscription?>(null) }
    SectionTitle(stringResource(R.string.servers_follows_title))
    when (val fl = state.follows) {
        Load.Loading -> SkeletonList(1, 72.dp)
        is Load.Error -> ErrorPanel(fl.message, { vm.load() })
        is Load.Ready -> fl.value.filter { it.status != RemoteStatus.REMOVED || it.endedAt != null }.forEach { f ->
            Surface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.servers_family_on, f.feedName, f.server.host.ifBlank { f.server.serverName }), style = Kin.type.bodyStrong, color = c.ink)
                        Text(pluralStringResource(R.plurals.servers_follow_items, f.itemCount, f.itemCount), style = Kin.type.caption, color = c.inkSoft)
                        f.lastError?.takeIf { f.status == RemoteStatus.ERROR }?.let { Text(it, style = Kin.type.caption, color = c.danger) }
                        f.backfill?.takeIf { it.state == "running" }?.let { b ->
                            Text(stringResource(R.string.frame_feeds_backfill, b.placed, b.total), style = Kin.type.caption, color = c.inkSoft)
                        }
                    }
                    Pill(statusLabel(res, f), c.paperDeep, c.inkSoft)
                }
                if (canManage && f.isLive) {
                    Text(stringResource(R.string.servers_on_end_title), style = Kin.type.caption, color = c.inkSoft)
                    Choice(stringResource(R.string.servers_on_end_remove), f.onEnd == RemoteOnEnd.REMOVE, !state.busy) { vm.setFollowOnEnd(f, RemoteOnEnd.REMOVE) }
                    Choice(stringResource(R.string.servers_on_end_keep), f.onEnd == RemoteOnEnd.KEEP, !state.busy) { vm.setFollowOnEnd(f, RemoteOnEnd.KEEP) }
                    QuietButton(stringResource(R.string.servers_follow_unsubscribe), { removing = f }, danger = true)
                }
            }
        }
    }
    if (canManage) {
        Surface(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.servers_follow_add), style = Kin.type.bodyStrong, color = c.ink)
            Text(stringResource(R.string.servers_follow_add_hint), style = Kin.type.caption, color = c.inkSoft)
            KinTextField(state.inviteUrl, vm::onInviteUrl, label = stringResource(R.string.servers_follow_invite_url), placeholder = "https://…/fi/…")
            PrimaryButton(stringResource(R.string.servers_follow_check), vm::preview, enabled = state.inviteUrl.isNotBlank(), busy = state.previewing)
        }
    }
    state.preview?.let { p ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) vm.cancelPreview() },
            title = { Text(stringResource(R.string.servers_family_on, p.feedName, p.server.host.ifBlank { p.server.serverName })) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pluralStringResource(R.plurals.servers_preview_frames, p.frameCount, p.frameCount), style = Kin.type.caption, color = c.inkSoft)
                    Text(stringResource(R.string.servers_preview_copy, p.thisServerHost.ifBlank { p.thisServerName }, frameName), style = Kin.type.body, color = c.ink)
                    Note(stringResource(R.string.servers_preview_operator, p.thisServerName), tone = NoteTone.Info)
                    if (p.requiresApproval) Note(stringResource(R.string.servers_preview_approval), tone = NoteTone.Warn)
                    Text(stringResource(R.string.servers_history_title), style = Kin.type.bodyStrong, color = c.ink)
                    Choice(stringResource(R.string.servers_history_none), state.history == SubscriptionHistory.NONE) { vm.setHistory(SubscriptionHistory.NONE) }
                    Choice(stringResource(R.string.servers_history_last, 50), state.history == SubscriptionHistory.LAST_N) { vm.setHistoryCount(50); vm.setHistory(SubscriptionHistory.LAST_N) }
                    Choice(stringResource(R.string.servers_history_all), state.history == SubscriptionHistory.ALL) { vm.setHistory(SubscriptionHistory.ALL) }
                    Text(stringResource(R.string.servers_on_end_title), style = Kin.type.bodyStrong, color = c.ink)
                    Choice(stringResource(R.string.servers_on_end_remove), state.onEnd == RemoteOnEnd.REMOVE) { vm.setOnEnd(RemoteOnEnd.REMOVE) }
                    Choice(stringResource(R.string.servers_on_end_keep), state.onEnd == RemoteOnEnd.KEEP) { vm.setOnEnd(RemoteOnEnd.KEEP) }
                }
            },
            confirmButton = { TextButton(vm::confirm, enabled = !state.busy) { Text(stringResource(R.string.servers_follow_confirm)) } },
            dismissButton = { TextButton(vm::cancelPreview, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.servers_follow_unsubscribe_title, f.feedName)) },
            text = { Text(stringResource(R.string.servers_follow_unsubscribe_body)) },
            confirmButton = {
                Row {
                    TextButton({ removing = null; vm.unsubscribe(f, keep = true) }) { Text(stringResource(R.string.servers_on_end_keep)) }
                    TextButton({ removing = null; vm.unsubscribe(f, keep = false) }) { Text(stringResource(R.string.servers_on_end_remove), color = c.danger) }
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------- family side (A)

/** Family settings → frames on other servers (multi-server.md §3.4): allow, invite link, blocked servers. */
@Composable
fun FeedRemoteSection(state: FeedRemoteState, vm: FeedRemoteViewModel) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.servers_feed_title))
    SwitchRow(stringResource(R.string.servers_feed_allow), state.allow, vm::setAllow, hint = stringResource(R.string.servers_feed_allow_hint), enabled = !state.busy)
    if (state.allow) {
        SecondaryButton(stringResource(R.string.servers_feed_invite), vm::createInvite, Modifier.fillMaxWidth(), enabled = !state.busy, icon = KinIcons.Share)
        Text(stringResource(R.string.servers_feed_invite_hint), style = Kin.type.caption, color = c.inkSoft)
    }
    Text(stringResource(R.string.servers_blocked_title), style = Kin.type.bodyStrong, color = c.ink)
    when (val b = state.blocked) {
        Load.Loading -> Unit
        is Load.Error -> ErrorPanel(b.message, { vm.loadBlocked() })
        is Load.Ready -> {
            if (b.value.isEmpty()) Text(stringResource(R.string.servers_blocked_none), style = Kin.type.caption, color = c.inkSoft)
            b.value.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.serverName ?: s.host, style = Kin.type.body, color = c.ink)
                        Text(listOfNotNull(s.host, Format.date(s.createdAt)).joinToString(" · "), style = Kin.type.caption, color = c.inkSoft)
                    }
                    if (s.feedId != null) QuietButton(stringResource(R.string.servers_blocked_unblock), { vm.unblock(s) }, enabled = !state.busy)
                }
            }
        }
    }
    KinTextField(state.blockHost, vm::onBlockHost, label = stringResource(R.string.servers_blocked_host), placeholder = "kinframe.example.org")
    QuietButton(stringResource(R.string.servers_blocked_block), vm::block, enabled = state.blockHost.isNotBlank() && !state.busy, danger = true)
}

/** "Oma's frame (kinframe.appmire.be)": a frame entry or subscription of a frame on another server. */
fun remoteName(name: String, serverName: String?): String = if (serverName.isNullOrBlank()) name else "$name ($serverName)"

/** A full-page wrapper for the remote follows of a frame (used from a link of another server's family invite). */
@Composable
fun RemoteFollowsPage(state: RemoteFollowsState, vm: RemoteFollowsViewModel, frameName: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        KinTopBar(stringResource(R.string.servers_follows_title), onBack = onBack)
        PageColumn { RemoteFollowsSection(state, vm, frameName, canManage = true) }
    }
}
