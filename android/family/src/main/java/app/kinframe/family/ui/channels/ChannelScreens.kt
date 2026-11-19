package app.kinframe.family.ui.channels

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.EmailAddress
import app.kinframe.core.api.EmailAddressUpdate
import app.kinframe.core.api.EmailHeldMessage
import app.kinframe.core.api.EmailMessage
import app.kinframe.core.api.EmailPendingSender
import app.kinframe.core.api.EmailSender
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.TelegramChat
import app.kinframe.core.api.TelegramChatUpdate
import app.kinframe.core.api.TelegramContributor
import app.kinframe.core.api.TelegramContributorsMode
import app.kinframe.core.api.TelegramLinks
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.LocalToaster
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QrCode
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------------------- helpers

/** Opens a `t.me` link in the Telegram app when it is installed, else in the browser. */
object TelegramOpener {
    fun open(context: Context, url: String) {
        val app = TelegramLinks.appUri(url)
        if (app != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // No Telegram app: the https link works in a browser.
            }
        }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun share(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Copy to the clipboard with a toast. */
@Composable
fun rememberCopy(): (label: String, text: String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val copied = stringResource(R.string.channel_copied)
    return remember(clipboard, scope, toaster, copied) {
        { label, text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))); toaster.show(copied) } }
    }
}

/** Share an inbox address with the ready-made message for a relative (README §5.16). */
@Composable
fun rememberShareAddress(me: String): (EmailAddress) -> Unit {
    val context = LocalContext.current
    val res = LocalResources.current
    return remember(context, res, me) {
        { a ->
            val text = EmailLogic.shareText(a, me,
                { name, address, owner -> res.getString(R.string.email_share_text, name, address, owner) },
                { name, address -> res.getString(R.string.email_share_text_open, name, address) })
            TelegramOpener.share(context, res.getString(R.string.email_share_subject, a.name.ifBlank { a.target.name }), text)
        }
    }
}

@Composable
private fun Toned(text: String, tone: ChatTone) {
    val c = Kin.colors
    val (bg, fg) = when (tone) {
        ChatTone.Good -> c.sageSoft to c.onSageSoft
        ChatTone.Danger -> c.dangerSoft to c.danger
        ChatTone.Warn -> c.warnSoft to c.warn
        ChatTone.Paused, ChatTone.Ended -> c.paperDeep to c.inkSoft
    }
    Pill(text, bg, fg)
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = Kin.type.body, color = Kin.colors.inkSoft) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(confirm, color = if (danger) Kin.colors.danger else Kin.colors.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ---------------------------------------------------------------------------------------------------------- settings entry

/**
 * "Photos by email and Telegram" in frame settings (frame managers) and feed settings (owner/admins): the two ways in.
 * Each is null when the server has it off (discovery `email_inbox`, `telegram`).
 */
@Composable
fun ChannelsSection(feed: Boolean, onEmail: (() -> Unit)?, onTelegram: (() -> Unit)?) {
    if (onEmail == null && onTelegram == null) return
    val c = Kin.colors
    SectionTitle(stringResource(R.string.channels_title))
    if (onEmail != null) Surface(Modifier.fillMaxWidth(), onClick = onEmail) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(KinIcons.Send, null, tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (feed) R.string.email_entry_feed else R.string.email_entry_frame), style = Kin.type.bodyStrong, color = c.ink)
                Text(stringResource(if (feed) R.string.email_entry_feed_hint else R.string.email_entry_frame_hint), style = Kin.type.caption, color = c.inkSoft)
            }
            Icon(KinIcons.ChevronRight, null, tint = c.inkSoft)
        }
    }
    if (onTelegram != null) Surface(Modifier.fillMaxWidth(), onClick = onTelegram) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(KinIcons.Message, null, tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tg_entry), style = Kin.type.bodyStrong, color = c.ink)
                Text(stringResource(if (feed) R.string.tg_entry_feed_hint else R.string.tg_entry_frame_hint), style = Kin.type.caption, color = c.inkSoft)
            }
            Icon(KinIcons.ChevronRight, null, tint = c.inkSoft)
        }
    }
}

/** How a join request came, with the channel's name and a check mark when it matches (README §5.16). */
@Composable
fun JoinRequestViaText(r: JoinRequest) {
    val c = Kin.colors
    Text(stringResource(JoinRequestVia.line(r), r.invitedByName.orEmpty()), style = Kin.type.caption, color = c.inkSoft)
    JoinRequestVia.detail(r)?.let { (name, matches) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (matches == true) Icon(KinIcons.Check, stringResource(R.string.people_via_matches), Modifier.size(14.dp), tint = c.onSageSoft)
            Text(name, style = Kin.type.caption, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (matches == false) Text(stringResource(R.string.people_via_mismatch), style = Kin.type.caption, color = c.warn)
    }
}

// ---------------------------------------------------------------------------------------------------------- email: list

@Composable
fun EmailInboxScreen(
    state: EmailInboxState,
    vm: EmailInboxViewModel,
    maxAddresses: Int,
    me: String,
    onBack: () -> Unit,
    onOpen: (EmailAddress) -> Unit,
) {
    val c = Kin.colors
    val copy = rememberCopy()
    val share = rememberShareAddress(me)
    var creating by remember { mutableStateOf(false) }
    val title = stringResource(if (vm.scope.isFeed) R.string.email_entry_feed else R.string.email_entry_frame)
    Column(Modifier.fillMaxSize()) {
        KinTopBar(title, onBack = onBack)
        when (val l = state.addresses) {
            Load.Loading -> SkeletonList(2, 140.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn {
                Text(stringResource(if (vm.scope.isFeed) R.string.email_intro_feed else R.string.email_intro_frame, vm.scope.name), style = Kin.type.body, color = c.inkSoft)
                if (l.value.isEmpty()) {
                    EmptyState(stringResource(R.string.email_empty_title), stringResource(R.string.email_empty_body)) {
                        PrimaryButton(stringResource(R.string.email_create), { creating = true }, icon = KinIcons.Plus)
                    }
                } else {
                    l.value.forEach { a -> AddressCard(a, a.id in state.busy, onOpen = { onOpen(a) }, onCopy = { copy(a.name, a.address) }, onShare = { share(a) }) }
                    if (l.value.size < maxAddresses) SecondaryButton(stringResource(R.string.email_create_another), { creating = true }, icon = KinIcons.Plus)
                    else Text(stringResource(R.string.email_limit_note, maxAddresses), style = Kin.type.caption, color = c.inkSoft)
                }
            }
        }
    }
    if (creating) CreateAddressDialog(vm.scope.name, state, onDismiss = { creating = false; vm.clearCreateError() }) { slug ->
        vm.create(slug) { creating = false }
    }
}

@Composable
private fun AddressCard(a: EmailAddress, busy: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.name, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.isPaused) Toned(stringResource(R.string.email_status_paused), ChatTone.Paused)
            if (a.counts.pendingSenders > 0) Toned(pluralStringResource(R.plurals.email_pending_count, a.counts.pendingSenders, a.counts.pendingSenders), ChatTone.Warn)
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
        }
        Text(a.address, style = Kin.type.body, color = c.ink)
        Text(
            pluralStringResource(R.plurals.email_delivered_30d, a.counts.delivered30d, a.counts.delivered30d) +
                (a.lastReceivedAt?.let { " · " + stringResource(R.string.email_last_received, Format.relative(it)) } ?: ""),
            style = Kin.type.caption, color = c.inkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.channel_copy), onCopy, icon = KinIcons.Copy)
            SecondaryButton(stringResource(R.string.channel_share), onShare, icon = KinIcons.Share)
        }
    }
}

@Composable
private fun CreateAddressDialog(targetName: String, state: EmailInboxState, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var slug by remember { mutableStateOf(EmailLogic.slugOf(targetName)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.email_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.email_create_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                KinTextField(slug, { slug = EmailLogic.cleanSlug(it) }, stringResource(R.string.email_slug),
                    supporting = stringResource(R.string.email_slug_hint), error = state.createError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
        },
        confirmButton = { TextButton({ onCreate(slug) }, enabled = !state.creating) { Text(stringResource(R.string.email_create_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ---------------------------------------------------------------------------------------------------------- email: one address

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailAddressScreen(state: EmailAddressState, vm: EmailAddressViewModel, me: String, onBack: () -> Unit) {
    val c = Kin.colors
    val copy = rememberCopy()
    val share = rememberShareAddress(me)
    var confirm by remember { mutableStateOf<String?>(null) }
    var editingSlug by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editingSender by remember { mutableStateOf<EmailSender?>(null) }
    var rejecting by remember { mutableStateOf<EmailPendingSender?>(null) }
    val a = state.addressOrNull
    Column(Modifier.fillMaxSize()) {
        KinTopBar(a?.name ?: stringResource(R.string.email_address_title), onBack = onBack)
        when (val l = state.address) {
            Load.Loading -> SkeletonList(3, 120.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn {
                val busy = a!!.id in state.busy
                Surface(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(a.address, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                        Toned(stringResource(if (a.isPaused) R.string.email_status_paused else R.string.email_status_active), if (a.isPaused) ChatTone.Paused else ChatTone.Good)
                    }
                    a.owner?.let { Text(stringResource(R.string.email_owner, it.displayName), style = Kin.type.caption, color = c.inkSoft) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.channel_copy), { copy(a.name, a.address) }, icon = KinIcons.Copy)
                        SecondaryButton(stringResource(R.string.channel_share), { share(a) }, icon = KinIcons.Share)
                        SecondaryButton(stringResource(if (a.isPaused) R.string.email_resume else R.string.email_pause), { vm.setPaused(!a.isPaused) },
                            enabled = !busy, icon = if (a.isPaused) KinIcons.Play else KinIcons.Pause)
                    }
                }
                if (a.isPaused) Note(stringResource(R.string.email_paused_note), tone = NoteTone.Warn)
                if (!a.repliesAvailable) Note(stringResource(R.string.email_no_replies_note))
                if (!a.isOwner && a.canManage) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.email_take_over_hint), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
                    QuietButton(stringResource(R.string.email_take_over), { vm.takeOver() }, enabled = !busy)
                }

                // ---- pending senders
                val pending = (state.pending as? Load.Ready)?.value.orEmpty()
                if (pending.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.email_pending_title))
                    pending.forEach { p ->
                        PendingCard(p, p.id in state.busy, onOpen = { vm.openHeld(it) }, onApprove = { vm.approve(p) }, onReject = { rejecting = p })
                    }
                }

                // ---- allowed senders
                SectionTitle(stringResource(R.string.email_senders_title)) {
                    QuietButton(stringResource(R.string.email_sender_add), { adding = true })
                }
                Text(stringResource(if (a.target.isFeed) R.string.email_senders_members_feed else R.string.email_senders_members_frame), style = Kin.type.caption, color = c.inkSoft)
                when (val s = state.senders) {
                    Load.Loading -> SkeletonList(1, 56.dp)
                    is Load.Error -> ErrorPanel(s.message, { vm.load() })
                    is Load.Ready -> if (s.value.isNotEmpty()) Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
                        s.value.forEachIndexed { i, sender ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            SenderRow(sender) { editingSender = sender }
                        }
                    }
                }

                // ---- settings
                SectionTitle(stringResource(R.string.email_settings_title))
                Surface(Modifier.fillMaxWidth()) {
                    SwitchRow(stringResource(R.string.email_setting_anyone), a.anyoneMaySend, { vm.update(EmailAddressUpdate(anyoneMaySend = it)) },
                        hint = stringResource(R.string.email_setting_anyone_hint), enabled = !busy)
                    SwitchRow(stringResource(R.string.email_setting_confirmations), a.sendConfirmations, { vm.update(EmailAddressUpdate(sendConfirmations = it)) },
                        hint = stringResource(R.string.email_setting_confirmations_hint), enabled = !busy && a.repliesAvailable)
                    SwitchRow(stringResource(R.string.email_setting_app_invite), a.offerAppInvite, { vm.update(EmailAddressUpdate(offerAppInvite = it)) },
                        hint = stringResource(R.string.email_setting_app_invite_hint), enabled = !busy && a.repliesAvailable)
                    if (a.isOwner) SwitchRow(stringResource(R.string.email_setting_reactions), a.notifyReactions, { vm.update(EmailAddressUpdate(notifyReactions = it)) },
                        hint = stringResource(R.string.email_setting_reactions_hint), enabled = !busy)
                }
                Surface(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.email_name_label), style = Kin.type.label, color = c.ink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, style = Kin.type.body, color = c.inkSoft, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.common_edit), { editingName = true }, enabled = !busy)
                    }
                    Text(stringResource(R.string.email_slug), style = Kin.type.label, color = c.ink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.slug, style = Kin.type.body, color = c.inkSoft, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.common_edit), { editingSlug = true }, enabled = !busy)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.email_regenerate), { confirm = "regenerate" }, enabled = !busy, icon = KinIcons.Refresh)
                    QuietButton(stringResource(R.string.email_delete), { confirm = "delete" }, enabled = !busy, danger = true)
                }

                // ---- recent mail
                SectionTitle(stringResource(R.string.email_recent_title))
                when (val m = state.messages) {
                    Load.Loading -> SkeletonList(2, 48.dp)
                    is Load.Error -> ErrorPanel(m.message, { vm.load() })
                    is Load.Ready -> if (m.value.isEmpty()) Text(stringResource(R.string.email_recent_empty), style = Kin.type.body, color = c.inkSoft)
                    else Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
                        m.value.forEachIndexed { i, msg ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            MessageRow(msg)
                        }
                        if (state.nextCursor != null) QuietButton(stringResource(R.string.common_load_more), { vm.loadMoreMessages() }, enabled = !state.loadingMore)
                    }
                }
            }
        }
    }
    if (a != null) when (confirm) {
        "regenerate" -> ConfirmDialog(stringResource(R.string.email_regenerate_title), stringResource(R.string.email_regenerate_body, a.address),
            stringResource(R.string.email_regenerate), danger = false, onDismiss = { confirm = null }) { vm.regenerate() }
        "delete" -> ConfirmDialog(stringResource(R.string.email_delete_title), stringResource(R.string.email_delete_body, a.address),
            stringResource(R.string.email_delete), onDismiss = { confirm = null }) { vm.delete() }
    }
    if (a != null && editingName) TextDialog(stringResource(R.string.email_name_label), a.name, null, onDismiss = { editingName = false }) { vm.rename(it); editingName = false }
    if (a != null && editingSlug) TextDialog(stringResource(R.string.email_slug), a.slug, stringResource(R.string.email_slug_change_hint), clean = EmailLogic::cleanSlug,
        onDismiss = { editingSlug = false }) { vm.changeSlug(it); editingSlug = false }
    if (adding) AddSenderDialog(state, onDismiss = { adding = false; vm.clearSenderError() }) { email, name, block -> vm.addSender(email, name, block) { adding = false } }
    editingSender?.let { s ->
        SenderDialog(s, onDismiss = { editingSender = null },
            onRename = { vm.renameSender(s, it); editingSender = null },
            onBlock = { vm.setBlocked(s, !s.isBlocked); editingSender = null },
            onRemove = { vm.removeSender(s); editingSender = null })
    }
    rejecting?.let { p ->
        RejectDialog(p, onDismiss = { rejecting = null }) { block -> vm.reject(p, block); rejecting = null }
    }
    state.held?.let { m ->
        val p = (state.pending as? Load.Ready)?.value?.firstOrNull { it.id == m.senderId }
        HeldMailDialog(m, p, m.id in state.busy || (p != null && p.id in state.busy), onDismiss = vm::closeHeld,
            onApprove = p?.let { { vm.approve(it) } }, onReject = p?.let { { rejecting = it } },
            onDeliver = { vm.deliverHeld(m) }, onDelete = { vm.deleteHeld(m) })
    }
}

@Composable
private fun PendingCard(p: EmailPendingSender, busy: Boolean, onOpen: (String) -> Unit, onApprove: () -> Unit, onReject: () -> Unit) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth()) {
        Text(p.displayName?.let { "$it (${p.email})" } ?: p.email, style = Kin.type.bodyStrong, color = c.ink)
        if (!p.authenticated) Note(stringResource(R.string.email_unverified, p.email), tone = NoteTone.Warn, icon = KinIcons.Warning)
        p.messages.forEach { m ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(m.id) }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (m.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.email_no_subject)) + " · " + Format.dateTime(m.receivedAt),
                    style = Kin.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(pluralStringResource(R.plurals.email_media_count, m.mediaCount, m.mediaCount) +
                    (m.expiresAt?.let { " · " + stringResource(R.string.email_expires, Format.date(it)) } ?: ""),
                    style = Kin.type.caption, color = c.inkSoft)
                if (m.thumbUrls.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.thumbUrls.take(4).forEach { Thumb(it, null, Modifier.size(56.dp), corner = 10.dp) }
                }
            }
        }
        // Unverified mail is never matched to the allow-list: deliver it one by one from its preview instead.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.authenticated) PrimaryButton(stringResource(R.string.email_approve), onApprove, enabled = !busy, busy = busy)
            else SecondaryButton(stringResource(R.string.email_review), { p.messages.firstOrNull()?.let { onOpen(it.id) } }, enabled = !busy)
            QuietButton(stringResource(R.string.email_reject), onReject, enabled = !busy, danger = true)
        }
    }
}

@Composable
private fun SenderRow(s: EmailSender, onClick: () -> Unit) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.displayName?.takeIf { it.isNotBlank() } ?: s.email, style = Kin.type.bodyStrong, color = c.ink)
            if (!s.displayName.isNullOrBlank()) Text(s.email, style = Kin.type.caption, color = c.inkSoft)
        }
        if (s.isBlocked) Toned(stringResource(R.string.email_sender_blocked), ChatTone.Danger)
    }
}

@Composable
private fun MessageRow(m: EmailMessage) {
    val c = Kin.colors
    val who = m.senderName?.takeIf { it.isNotBlank() } ?: m.senderEmail.orEmpty()
    val what = if (m.outcome == app.kinframe.core.api.EmailOutcome.DELIVERED) pluralStringResource(R.plurals.email_outcome_delivered_count, m.items, m.items)
    else stringResource(EmailLogic.outcome(m.outcome))
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text(if (EmailLogic.outcomeHasSender(m.outcome) && who.isNotEmpty()) "$who · $what" else what, style = Kin.type.body, color = c.ink)
        val skipped = m.skipped?.total ?: 0
        Text(Format.dateTime(m.receivedAt) + if (skipped > 0) " · " + pluralStringResource(R.plurals.email_skipped, skipped, skipped) else "",
            style = Kin.type.caption, color = c.inkSoft)
    }
}

@Composable
private fun TextDialog(label: String, initial: String, hint: String?, clean: (String) -> String = { it }, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = { KinTextField(v, { v = clean(it) }, label, supporting = hint) },
        confirmButton = { TextButton({ onSave(v) }, enabled = v.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun AddSenderDialog(state: EmailAddressState, onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.email_sender_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.email_sender_add_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                KinTextField(email, { email = it }, stringResource(R.string.email_sender_email), error = state.senderError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                KinTextField(name, { name = it.take(40) }, stringResource(R.string.email_sender_name), supporting = stringResource(R.string.email_sender_name_hint))
            }
        },
        confirmButton = { TextButton({ onAdd(email, name, false) }, enabled = email.isNotBlank() && "add" !in state.busy) { Text(stringResource(R.string.email_sender_allow)) } },
        dismissButton = {
            Row {
                TextButton({ onAdd(email, name, true) }, enabled = email.isNotBlank() && "add" !in state.busy) { Text(stringResource(R.string.email_sender_block), color = Kin.colors.danger) }
                TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun SenderDialog(s: EmailSender, onDismiss: () -> Unit, onRename: (String) -> Unit, onBlock: () -> Unit, onRemove: () -> Unit) {
    var name by remember { mutableStateOf(s.displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.email) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KinTextField(name, { name = it.take(40) }, stringResource(R.string.email_sender_name), supporting = stringResource(R.string.email_sender_name_hint))
                s.addedByName?.let { Text(stringResource(R.string.email_sender_added_by, it), style = Kin.type.caption, color = Kin.colors.inkSoft) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(if (s.isBlocked) R.string.email_sender_unblock else R.string.email_sender_block), onBlock)
                    QuietButton(stringResource(R.string.email_sender_remove), onRemove, danger = true)
                }
            }
        },
        confirmButton = { TextButton({ onRename(name) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RejectDialog(p: EmailPendingSender, onDismiss: () -> Unit, onReject: (Boolean) -> Unit) {
    var block by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.email_reject_title, p.displayName ?: p.email)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.email_reject_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { block = !block }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block, null, colors = CheckboxDefaults.colors(checkedColor = Kin.colors.accent))
                    Text(stringResource(R.string.email_reject_block), style = Kin.type.body, color = Kin.colors.ink)
                }
            }
        },
        confirmButton = { TextButton({ onReject(block) }) { Text(stringResource(R.string.email_reject), color = Kin.colors.danger) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A held mail: sender as claimed, subject, how it was checked, every photo (README §5.16 "Held mail"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeldMailDialog(
    m: EmailHeldMessage, p: EmailPendingSender?, busy: Boolean, onDismiss: () -> Unit,
    onApprove: (() -> Unit)?, onReject: (() -> Unit)?, onDeliver: () -> Unit, onDelete: () -> Unit,
) {
    val c = Kin.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.email_no_subject)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.email_held_from, m.senderName?.let { "$it <${m.senderEmail}>" } ?: m.senderEmail), style = Kin.type.body, color = c.ink)
                Text(Format.dateTime(m.receivedAt), style = Kin.type.caption, color = c.inkSoft)
                if (m.isUnverified) Note(stringResource(R.string.email_unverified, m.senderEmail), tone = NoteTone.Warn, icon = KinIcons.Warning)
                else Note(stringResource(R.string.email_verified, m.auth.fromDomain ?: m.senderEmail.substringAfter('@')), tone = NoteTone.Good, icon = KinIcons.Shield)
                Text(stringResource(R.string.email_auth_detail, m.auth.spf ?: "-", m.auth.dkim ?: "-", m.auth.dmarc ?: "-"), style = Kin.type.caption, color = c.inkFaint)
                m.caption?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.email_held_caption, it), style = Kin.type.caption, color = c.inkSoft) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.media.forEach { media ->
                        if (media.thumbUrl != null) Thumb(media.thumbUrl, stringResource(if (media.kind == MediaKind.VIDEO) R.string.email_held_video else R.string.email_held_photo), Modifier.size(72.dp), corner = 10.dp)
                        else Surface(Modifier.size(72.dp), padding = 4.dp) { Icon(if (media.kind == MediaKind.VIDEO) KinIcons.Video else KinIcons.Image, null, tint = c.inkSoft) }
                    }
                }
                m.expiresAt?.let { Text(stringResource(R.string.email_expires, Format.date(it)), style = Kin.type.caption, color = c.inkSoft) }
                if (m.isUnverified) {
                    // Unverified: only this one mail, never the sender.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(stringResource(R.string.email_deliver_one), onDeliver, enabled = !busy, busy = busy)
                        QuietButton(stringResource(R.string.channel_delete), onDelete, enabled = !busy, danger = true)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onApprove != null) PrimaryButton(stringResource(R.string.email_approve), onApprove, enabled = !busy, busy = busy)
                        SecondaryButton(stringResource(R.string.email_deliver_one), onDeliver, enabled = !busy)
                        if (onReject != null) QuietButton(stringResource(R.string.email_reject), onReject, enabled = !busy, danger = true)
                        else QuietButton(stringResource(R.string.channel_delete), onDelete, enabled = !busy, danger = true)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

// ---------------------------------------------------------------------------------------------------------- telegram: list

@Composable
fun TelegramChatsScreen(state: TelegramChatsState, vm: TelegramChatsViewModel, privacyMode: Boolean, maxChats: Int, onBack: () -> Unit, onOpenChat: (TelegramChat) -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize()) {
        KinTopBar(stringResource(R.string.tg_entry), onBack = onBack)
        when (val l = state.chats) {
            Load.Loading -> SkeletonList(2, 100.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn {
                Text(stringResource(if (vm.scope.isFeed) R.string.tg_intro_feed else R.string.tg_intro_frame, vm.scope.name), style = Kin.type.body, color = c.inkSoft)
                val live = l.value.count { it.isLive }
                if (live < maxChats) {
                    PrimaryButton(stringResource(R.string.tg_connect_group), { vm.connect(ConnectKind.Group) }, Modifier.fillMaxWidth(), icon = KinIcons.People)
                    SecondaryButton(stringResource(R.string.tg_connect_mine), { vm.connect(ConnectKind.Mine) }, Modifier.fillMaxWidth(), icon = KinIcons.Message)
                    SecondaryButton(stringResource(R.string.tg_connect_invite), { vm.connect(ConnectKind.Invite) }, Modifier.fillMaxWidth(), icon = KinIcons.UserPlus)
                } else Text(stringResource(R.string.tg_limit_note, maxChats), style = Kin.type.caption, color = c.inkSoft)
                if (l.value.isEmpty()) Text(stringResource(R.string.tg_empty), style = Kin.type.body, color = c.inkSoft)
                l.value.forEach { chat -> ChatCard(chat, chat.id in state.busy, { onOpenChat(chat) }) { vm.setPaused(chat, !chat.isPaused) } }
            }
        }
    }
    state.connect?.let { ConnectDialog(it, vm.scope, privacyMode, vm, onOpenChat) }
}

@Composable
private fun ChatCard(chat: TelegramChat, busy: Boolean, onOpen: () -> Unit, onTogglePause: () -> Unit) {
    val c = Kin.colors
    val ui = TelegramLogic.status(chat)
    Surface(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (chat.isGroup) KinIcons.People else KinIcons.User, null, tint = c.inkSoft)
            Text(chat.title, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Toned(stringResource(ui.status), ui.tone)
        }
        ui.problem?.let { Note(stringResource(it, *ui.problemArgs.toTypedArray()), tone = if (ui.tone == ChatTone.Ended) NoteTone.Info else NoteTone.Warn) }
        ui.warnings.forEach { Text(stringResource(it), style = Kin.type.caption, color = c.warn) }
        Text(pluralStringResource(R.plurals.tg_sent_count, chat.counts.sent, chat.counts.sent) +
            (chat.lastDeliveryAt?.let { " · " + stringResource(R.string.tg_last_delivery, Format.relative(it)) } ?: ""), style = Kin.type.caption, color = c.inkSoft)
        if (chat.isLive && chat.canManage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(stringResource(if (chat.isPaused) R.string.tg_resume else R.string.tg_pause), onTogglePause, enabled = !busy)
        }
    }
}

/** The connect sheet: what will happen, open Telegram, the link as text and QR code, and live status. */
@Composable
private fun ConnectDialog(k: ConnectState, scope: ChannelScope, privacyMode: Boolean, vm: TelegramChatsViewModel, onOpenChat: (TelegramChat) -> Unit) {
    val c = Kin.colors
    val copy = rememberCopy()
    val context = LocalContext.current
    val title = stringResource(when (k.kind) {
        ConnectKind.Group -> R.string.tg_connect_group
        ConnectKind.Mine -> R.string.tg_connect_mine
        ConnectKind.Invite -> R.string.tg_connect_invite
    })
    AlertDialog(
        onDismissRequest = vm::closeConnect,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    k.connected -> {
                        Note(k.chat?.let { stringResource(R.string.tg_connected_to, it.title) } ?: stringResource(R.string.tg_connected), tone = NoteTone.Good, icon = KinIcons.Check)
                        k.chat?.let { chat ->
                            val ui = TelegramLogic.status(chat)
                            ui.problem?.let { Note(stringResource(it, *ui.problemArgs.toTypedArray()), tone = NoteTone.Warn) }
                        }
                    }
                    k.creating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.common_loading), style = Kin.type.body, color = c.inkSoft)
                    }
                    else -> {
                        Text(stringResource(when (k.kind) {
                            ConnectKind.Group -> if (scope.isFeed) R.string.tg_connect_group_body_feed else R.string.tg_connect_group_body
                            ConnectKind.Mine -> R.string.tg_connect_mine_body
                            ConnectKind.Invite -> R.string.tg_connect_invite_body
                        }, scope.name), style = Kin.type.body, color = c.inkSoft)
                        if (k.kind == ConnectKind.Group && privacyMode) Note(stringResource(R.string.tg_connect_privacy_note), tone = NoteTone.Info)
                        k.error?.let { Note(stringResource(it, scope.name), tone = NoteTone.Warn, icon = KinIcons.Warning) }
                        k.url?.let { url ->
                            if (k.kind == ConnectKind.Invite) PrimaryButton(stringResource(R.string.channel_share), { vm.reopen() }, Modifier.fillMaxWidth(), icon = KinIcons.Share)
                            else PrimaryButton(stringResource(R.string.tg_open_telegram), { TelegramOpener.open(context, url) }, Modifier.fillMaxWidth(), icon = KinIcons.Message)
                            Text(stringResource(R.string.tg_other_device), style = Kin.type.caption, color = c.inkSoft)
                            QrCode(url, Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(url, style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                QuietButton(stringResource(R.string.channel_copy), { copy(title, url) })
                            }
                        }
                        if (k.waiting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                            Text(stringResource(R.string.tg_waiting), style = Kin.type.caption, color = c.inkSoft)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val chat = k.chat
            if (chat != null) TextButton({ vm.closeConnect(); onOpenChat(chat) }) { Text(stringResource(R.string.tg_open_chat)) }
            else if (k.error != null && !k.creating) TextButton({ vm.connect(k.kind) }) { Text(stringResource(R.string.tg_new_link)) }
            else TextButton(vm::closeConnect) { Text(stringResource(if (k.connected) R.string.common_done else R.string.common_cancel)) }
        },
        dismissButton = if (k.chat != null || (k.error != null && !k.creating)) ({ TextButton(vm::closeConnect) { Text(stringResource(R.string.common_close)) } }) else null,
    )
}

// ---------------------------------------------------------------------------------------------------------- telegram: one chat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelegramChatScreen(state: TelegramChatState, vm: TelegramChatViewModel, onBack: () -> Unit, onReconnect: () -> Unit) {
    val c = Kin.colors
    var disconnecting by remember { mutableStateOf(false) }
    val chat = state.chatOrNull
    Column(Modifier.fillMaxSize()) {
        KinTopBar(chat?.title ?: stringResource(R.string.tg_entry), onBack = onBack)
        when (val l = state.chat) {
            Load.Loading -> SkeletonList(3, 100.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn {
                val ch = l.value
                val busy = ch.id in state.busy
                val ui = TelegramLogic.status(ch)
                Surface(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (ch.isGroup) R.string.tg_kind_group else R.string.tg_kind_private), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
                        Toned(stringResource(ui.status), ui.tone)
                    }
                    ui.problem?.let { Note(stringResource(it, *ui.problemArgs.toTypedArray()), tone = if (ui.tone == ChatTone.Ended) NoteTone.Info else NoteTone.Warn) }
                    if (ui.reconnect) SecondaryButton(stringResource(R.string.tg_reconnect), onReconnect, icon = KinIcons.Refresh)
                    ui.warnings.forEach { Text(stringResource(it), style = Kin.type.caption, color = c.warn) }
                    ch.owner?.let { Text(stringResource(R.string.tg_owner, it.displayName), style = Kin.type.caption, color = c.inkSoft) }
                    Text(pluralStringResource(R.plurals.tg_sent_count, ch.counts.sent, ch.counts.sent) + " · " +
                        pluralStringResource(R.plurals.tg_contributor_count, ch.counts.contributors, ch.counts.contributors), style = Kin.type.caption, color = c.inkSoft)
                    if (ch.isLive && !ch.isOwner && ch.canManage) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tg_take_over_hint), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.tg_take_over), { vm.takeOver() }, enabled = !busy)
                    }
                }
                if (ch.isLive && ch.canManage) {
                    SectionTitle(stringResource(R.string.tg_settings_title))
                    Surface(Modifier.fillMaxWidth()) {
                        SwitchRow(stringResource(R.string.tg_setting_paused), ch.isPaused, { vm.setPaused(it) }, hint = stringResource(R.string.tg_setting_paused_hint), enabled = !busy)
                        if (ch.isGroup) {
                            Text(stringResource(R.string.tg_setting_who), style = Kin.type.label, color = c.ink)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToggleChip(stringResource(R.string.tg_who_everyone), ch.contributors == TelegramContributorsMode.EVERYONE,
                                    { vm.update(TelegramChatUpdate(contributors = TelegramContributorsMode.EVERYONE)) })
                                ToggleChip(stringResource(R.string.tg_who_linked, ch.target.name), ch.contributors == TelegramContributorsMode.LINKED_ONLY,
                                    { vm.update(TelegramChatUpdate(contributors = TelegramContributorsMode.LINKED_ONLY)) })
                            }
                        }
                        if (ch.isForum && ch.topics.isNotEmpty()) {
                            Text(stringResource(R.string.tg_setting_topic), style = Kin.type.label, color = c.ink)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToggleChip(stringResource(R.string.tg_topic_all), ch.topicId == null, { vm.setTopic(null) })
                                ch.topics.forEach { t -> ToggleChip(t.name, ch.topicId == t.id, { vm.setTopic(t.id) }) }
                            }
                        }
                        if (!ch.target.isFeed) SwitchRow(stringResource(R.string.tg_setting_replies), ch.frameReplies, { vm.update(TelegramChatUpdate(frameReplies = it)) },
                            hint = stringResource(R.string.tg_setting_replies_hint), enabled = !busy)
                        SwitchRow(stringResource(R.string.tg_setting_app_invite), ch.offerAppInvite, { vm.update(TelegramChatUpdate(offerAppInvite = it)) },
                            hint = stringResource(R.string.tg_setting_app_invite_hint), enabled = !busy)
                        if (ch.isOwner) SwitchRow(stringResource(R.string.tg_setting_reactions), ch.notifyReactions, { vm.update(TelegramChatUpdate(notifyReactions = it)) },
                            hint = stringResource(R.string.tg_setting_reactions_hint), enabled = !busy)
                    }
                }
                SectionTitle(stringResource(R.string.tg_people_title))
                when (val p = state.contributors) {
                    Load.Loading -> SkeletonList(1, 56.dp)
                    is Load.Error -> ErrorPanel(p.message, { vm.load() })
                    is Load.Ready -> if (p.value.isEmpty()) Text(stringResource(R.string.tg_people_empty), style = Kin.type.body, color = c.inkSoft)
                    else Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
                        p.value.forEachIndexed { i, person ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            ContributorRow(person, person.id in state.busy, ch.canManage && ch.isLive) { vm.setBlocked(person, !person.blocked) }
                        }
                    }
                }
                if (ch.isLive && ch.canManage) QuietButton(stringResource(R.string.tg_disconnect), { disconnecting = true }, enabled = !busy, danger = true)
            }
        }
    }
    if (disconnecting && chat != null) ConfirmDialog(stringResource(R.string.tg_disconnect_title, chat.title), stringResource(R.string.tg_disconnect_body),
        stringResource(R.string.tg_disconnect), onDismiss = { disconnecting = false }) { vm.disconnect() }
}

@Composable
private fun ContributorRow(p: TelegramContributor, busy: Boolean, canManage: Boolean, onToggle: () -> Unit) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = Kin.type.bodyStrong, color = c.ink)
            Text(pluralStringResource(R.plurals.tg_sent_count, p.sentCount, p.sentCount) + " · " +
                stringResource(if (p.linked) R.string.tg_person_linked else R.string.tg_person_outside), style = Kin.type.caption, color = c.inkSoft)
        }
        if (p.blocked) Toned(stringResource(R.string.tg_person_blocked), ChatTone.Danger)
        if (canManage) QuietButton(stringResource(if (p.blocked) R.string.tg_unblock else R.string.tg_block), onToggle, enabled = !busy, danger = !p.blocked)
    }
}

// ---------------------------------------------------------------------------------------------------------- telegram: my account

/** Settings → Telegram: "Link my Telegram" or the linked account with Unlink. */
@Composable
fun TelegramLinkSection(state: TelegramLinkState, vm: TelegramLinkViewModel) {
    val c = Kin.colors
    val context = LocalContext.current
    var unlinking by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.tg_link_title))
    Surface(Modifier.fillMaxWidth()) {
        when (val l = state.link) {
            Load.Loading -> Text(stringResource(R.string.common_loading), style = Kin.type.body, color = c.inkSoft)
            is Load.Error -> {
                Text(stringResource(l.message), style = Kin.type.body, color = c.inkSoft)
                QuietButton(stringResource(R.string.channel_retry), { vm.load() })
            }
            is Load.Ready -> {
                val link = l.value
                if (link == null) {
                    Text(stringResource(R.string.tg_link_body), style = Kin.type.body, color = c.inkSoft)
                    PrimaryButton(stringResource(R.string.tg_link), { vm.link() }, busy = state.busy, icon = KinIcons.Link)
                    state.url?.let { url ->
                        Text(stringResource(R.string.tg_link_waiting), style = Kin.type.caption, color = c.inkSoft)
                        QuietButton(stringResource(R.string.tg_open_telegram), { TelegramOpener.open(context, url) })
                    }
                } else {
                    Text(stringResource(R.string.tg_linked_as, link.displayName + (link.username?.let { " (@$it)" } ?: "")), style = Kin.type.bodyStrong, color = c.ink)
                    if (!link.canMessage) Note(stringResource(R.string.tg_link_blocked), tone = NoteTone.Warn)
                    QuietButton(stringResource(R.string.tg_unlink), { unlinking = true }, enabled = !state.busy, danger = true)
                }
            }
        }
    }
    if (unlinking) ConfirmDialog(stringResource(R.string.tg_unlink_title), stringResource(R.string.tg_unlink_body), stringResource(R.string.tg_unlink),
        onDismiss = { unlinking = false }) { vm.unlink() }
}
