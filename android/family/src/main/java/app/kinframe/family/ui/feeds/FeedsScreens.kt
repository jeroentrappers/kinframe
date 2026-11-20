package app.kinframe.family.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedCodes
import app.kinframe.core.api.FeedDeliveryStatus
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.core.api.FeedInviteStatus
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.FeedPostReceipts
import app.kinframe.core.api.FeedReaction
import app.kinframe.core.api.FeedReceiptSummary
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Frame
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.SendFeed
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRemovedReason
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.history.Delivery
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FeedTab
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.OnlineStatus
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

// ---------------------------------------------------------------------------------------------------------- shared bits

/** "Seen on 4 of 5 frames" and the like for one post (feeds.md §7). Paused frames are not in the denominator. */
@Composable
fun receiptHeadline(s: FeedReceiptSummary): String = when {
    s.frames == 0 -> stringResource(R.string.feed_receipt_no_frames)
    s.seen >= s.frames -> pluralStringResource(R.plurals.feed_receipt_seen_all, s.frames, s.frames)
    s.seen > 0 -> pluralStringResource(R.plurals.feed_receipt_seen, s.frames, s.seen, s.frames)
    else -> pluralStringResource(R.plurals.feed_receipt_delivered, s.frames, s.delivered, s.frames)
}

/** The extra counts after the headline: awaiting approval, paused, removed on a frame. */
@Composable
fun receiptExtras(s: FeedReceiptSummary): String? = listOfNotNull(
    s.awaitingApproval.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.feed_receipt_awaiting, it, it) },
    s.paused.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.feed_receipt_paused, it, it) },
    s.removed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.feed_receipt_removed, it, it) },
).joinToString(" · ").ifEmpty { null }

/** A feed recipient of a send in the history: "Peeters kids · Seen on 4 of 5 frames". */
@Composable
fun SendFeedRow(f: SendFeed) {
    val c = Kin.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KinIcons.People, null, Modifier.size(18.dp), tint = c.inkSoft)
        Column(Modifier.weight(1f)) {
            Text(f.feedName, style = Kin.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val s = f.summary
            when {
                f.removed -> Text(stringResource(R.string.feed_post_removed_short), style = Kin.type.caption, color = c.inkFaint)
                s != null -> {
                    Text(receiptHeadline(s), style = Kin.type.caption, color = c.inkSoft)
                    receiptExtras(s)?.let { Text(it, style = Kin.type.caption, color = c.inkFaint) }
                }
            }
        }
    }
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

@Composable
fun FeedRoleBadge(role: String) {
    val c = Kin.colors
    when (role) {
        FeedRole.OWNER -> Pill(stringResource(R.string.role_owner), c.accentSoft, c.onAccentSoft)
        FeedRole.ADMIN -> Pill(stringResource(R.string.role_admin), c.sageSoft, c.onSageSoft)
        FeedRole.MEMBER -> Pill(stringResource(R.string.social_role_member), c.paperDeep, c.inkSoft)
        else -> Pill(stringResource(R.string.feed_role_poster), c.paperDeep, c.inkSoft)
    }
}

@Composable
fun SubscriptionStatusPill(sub: Subscription) {
    val c = Kin.colors
    when (sub.status) {
        SubscriptionStatus.ACTIVE -> Pill(stringResource(R.string.feed_sub_status_active), c.sageSoft, c.onSageSoft)
        SubscriptionStatus.PAUSED -> Pill(stringResource(R.string.feed_sub_status_paused), c.warnSoft, c.warn)
        SubscriptionStatus.PENDING -> Pill(stringResource(R.string.feed_sub_status_pending), c.accentSoft, c.onAccentSoft, icon = KinIcons.Clock)
        SubscriptionStatus.REJECTED -> Pill(stringResource(R.string.feed_sub_status_rejected), c.dangerSoft, c.danger)
        else -> Pill(stringResource(R.string.feed_sub_status_removed), c.paperDeep, c.inkSoft)
    }
}

@Composable
private fun removedReason(sub: Subscription): String? = when (sub.removedReason) {
    SubscriptionRemovedReason.UNSUBSCRIBED -> stringResource(R.string.feed_sub_reason_unsubscribed)
    SubscriptionRemovedReason.REMOVED_BY_FEED -> stringResource(R.string.feed_sub_reason_removed_by_feed)
    SubscriptionRemovedReason.FRAME_REMOVED -> stringResource(R.string.feed_sub_reason_frame_removed)
    SubscriptionRemovedReason.FEED_DELETED -> stringResource(R.string.feed_sub_reason_feed_deleted)
    SubscriptionRemovedReason.WITHDRAWN -> stringResource(R.string.feed_sub_reason_withdrawn)
    else -> null
}

@Composable
fun historyLabel(history: String, count: Int?): String = when (history) {
    SubscriptionHistory.LAST_N -> pluralStringResource(R.plurals.feed_history_last_n_value, count ?: FeedRules.DEFAULT_LAST_N, count ?: FeedRules.DEFAULT_LAST_N)
    SubscriptionHistory.ALL -> stringResource(R.string.feed_history_all)
    else -> stringResource(R.string.feed_history_none)
}

/** "Only new posts" / "The last N" / "Everything" (feeds.md §5.2). History only: never a rolling limit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryPicker(option: String, count: Int, onChange: (String, Int) -> Unit) {
    val c = Kin.colors
    var countText by remember(count) { mutableStateOf(count.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feed_history_title), style = Kin.type.label, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.feed_history_none), option == SubscriptionHistory.NONE, { onChange(SubscriptionHistory.NONE, count) })
            ToggleChip(stringResource(R.string.feed_history_last_n), option == SubscriptionHistory.LAST_N, { onChange(SubscriptionHistory.LAST_N, count) })
            ToggleChip(stringResource(R.string.feed_history_all), option == SubscriptionHistory.ALL, { onChange(SubscriptionHistory.ALL, count) })
        }
        if (option == SubscriptionHistory.LAST_N) {
            KinTextField(countText, { v ->
                countText = v.filter { it.isDigit() }.take(4)
                countText.toIntOrNull()?.let { onChange(SubscriptionHistory.LAST_N, it.coerceIn(1, SubscriptionHistory.MAX_COUNT)) }
            }, stringResource(R.string.feed_history_count), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Text(stringResource(R.string.feed_history_hint), style = Kin.type.caption, color = c.inkSoft)
    }
}

// ---------------------------------------------------------------------------------------------------------- one feed

/** Everything a feed screen can do; wired to [FeedDetailViewModel] by the root. */
class FeedActions(
    val vm: FeedDetailViewModel,
    val me: String?,
    /** Frames you manage (owner, admin, change_settings): for "Show on a frame". */
    val managedFrames: List<Frame>,
    val post: () -> Unit,
    val shareInvite: (FeedInvite) -> Unit,
    val copyInvite: (FeedInvite) -> Unit,
    val openFrame: (String) -> Unit,
    /** The Birthdays tab; null when the server has no birthdays (discovery `birthdays`). */
    val birthdays: BirthdayActions? = null,
    val birthdaysState: BirthdaysState? = null,
    /** The Sources tab (owner/admins, discovery `photo_sources`); null hides it. */
    val sources: (@Composable () -> Unit)? = null,
    /** "Add from Google Photos" in the Posts tab (any member, discovery `google_photos_import`). */
    val addFromGoogle: (() -> Unit)? = null,
    /** "Photos by email and Telegram" in Settings (owner/admins; discovery `email_inbox` / `telegram`); null hides it. */
    val channels: (@Composable () -> Unit)? = null,
    /** Family social (docs/spec/family-social.md); null when the server doesn't have it. */
    val social: app.kinframe.family.ui.social.FeedSocialSlots? = null,
    /** Leaving (README §5.21): the leave sheet with the photo choice; null shows the older dialog. */
    val leaveSheet: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    /** Leaving and other servers in Settings: photos taken away, frames on other servers (owner/admins). */
    val leavingSettings: (@Composable () -> Unit)? = null,
    /** "Add a frame": pick a frame you manage or pair a new one (docs/spec/simple-app.md §3.4); null hides it. */
    val addFrame: (() -> Unit)? = null,
)

/**
 * A page of a family's settings (docs/spec/simple-app.md §3.4): name, cover, rules and leaving ([FeedTab.Settings]),
 * birthdays, or photo sources. Posts, People and Frames are the family profile's segments instead.
 */
@Composable
fun FamilyPartScreen(state: FeedDetailState, part: FeedTab, onBack: () -> Unit, a: FeedActions) {
    val c = Kin.colors
    val feed = state.feedOrNull
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(when (part) {
            FeedTab.Birthdays -> R.string.settings_family_birthdays
            FeedTab.Sources -> R.string.settings_family_more_ways
            FeedTab.People -> R.string.family_seg_people
            FeedTab.Frames -> R.string.family_seg_frames
            else -> R.string.settings_family_name_cover
        }), onBack = onBack, subtitle = feed?.name)
        when (val f = state.feed) {
            Load.Loading -> SkeletonList(3, 140.dp)
            is Load.Error -> ErrorPanel(f.message, { a.vm.load() })
            is Load.Ready -> Box(Modifier.weight(1f)) {
                when (part) {
                    FeedTab.People -> PeopleTab(f.value, state, a)
                    FeedTab.Frames -> FramesTab(f.value, state, a)
                    FeedTab.Birthdays -> a.birthdays?.let { b -> a.birthdaysState?.let { bs -> BirthdaysTab(f.value, bs, b) } }
                    FeedTab.Sources -> PageColumn(spacing = 12.dp) {
                        Text(stringResource(R.string.source_feed_intro), style = Kin.type.body, color = Kin.colors.inkSoft)
                        a.sources?.invoke()
                        if (f.value.canPost) a.addFromGoogle?.let { SecondaryButton(stringResource(R.string.google_add), it, Modifier.fillMaxWidth(), icon = KinIcons.Image) }
                        if (f.value.isAdmin) a.channels?.invoke()
                    }
                    // Everyone can leave; owner and admins also see the family's name, cover and rules.
                    else -> if (f.value.isAdmin) SettingsTab(f.value, state, a) else PageColumn { LeaveSection(f.value, a) }
                }
            }
        }
    }
}

// ---- people

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeopleTab(feed: Feed, state: FeedDetailState, a: FeedActions) {
    val c = Kin.colors
    var editing by remember { mutableStateOf<FeedMember?>(null) }
    PageColumn {
        if (feed.isAdmin && state.requests.isNotEmpty()) {
            SectionTitle(stringResource(R.string.people_requests))
            state.requests.forEach { r -> JoinRequestCard(r, state.busy, a) }
        }
        SectionTitle(stringResource(R.string.people_members))
        Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
            state.members.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = c.line)
                val editable = FeedRules.canManageMember(feed, m, a.me)
                Row(
                    Modifier.fillMaxWidth().let { if (editable) it.clickable(role = Role.Button) { editing = m } else it }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(m.displayName, m.avatarUrl)
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName + if (m.userId == a.me) " (" + stringResource(R.string.people_you) + ")" else "",
                            style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.lastPostedAt?.let { stringResource(R.string.feed_last_posted, Format.relative(it)) } ?: stringResource(R.string.feed_never_posted),
                            style = Kin.type.caption, color = c.inkSoft)
                    }
                    FeedRoleBadge(m.role)
                    if (editable) Icon(KinIcons.ChevronRight, stringResource(R.string.people_edit_member, m.displayName), Modifier.size(20.dp), tint = c.inkFaint)
                }
            }
        }
        if (feed.canInvite) FeedInviteSection(feed, state, a)
    }
    editing?.let { m -> FeedMemberDialog(feed, m, a) { editing = null } }
}

/** You in this family: mute, and leave (or, the owner, delete) it. */
@Composable
internal fun LeaveSection(feed: Feed, a: FeedActions) {
    val c = Kin.colors
    var leaving by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.feed_you))
    a.social?.you?.invoke()
    if (!feed.isOwner) QuietButton(stringResource(R.string.feed_leave), { leaving = true }, danger = true)
    else {
        Text(stringResource(R.string.feed_owner_hint), style = Kin.type.caption, color = c.inkSoft)
        QuietButton(stringResource(R.string.feed_delete), { deleting = true }, danger = true)
    }
    if (leaving && a.leaveSheet != null) a.leaveSheet.invoke { leaving = false }
    else if (leaving) {
        var removeMine by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.feed_leave_title, feed.name)) },
            text = { Column { Text(stringResource(R.string.feed_leave_body)); CheckRow(stringResource(R.string.feed_remove_my_posts), removeMine) { removeMine = it } } },
            confirmButton = { TextButton({ leaving = false; a.vm.leave(removeMine) }) { Text(stringResource(R.string.feed_leave), color = c.danger) } },
            dismissButton = { TextButton({ leaving = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.feed_delete_title, feed.name)) },
            text = { Text(stringResource(R.string.feed_delete_body)) },
            confirmButton = { TextButton({ deleting = false; a.vm.delete() }) { Text(stringResource(R.string.feed_delete), color = c.danger) } },
            dismissButton = { TextButton({ deleting = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun JoinRequestCard(r: JoinRequest, busy: Boolean, a: FeedActions) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(r.user.displayName.orEmpty(), r.user.avatarUrl)
            Column(Modifier.weight(1f)) {
                Text(r.user.displayName.orEmpty(), style = Kin.type.bodyStrong, color = c.ink)
                if (r.isChannel) app.kinframe.family.ui.channels.JoinRequestViaText(r)
                else Text(stringResource(R.string.people_via_invite, r.invitedByName.orEmpty()), style = Kin.type.caption, color = c.inkSoft)
                r.note?.let { Text("“$it”", style = Kin.type.caption, color = c.inkSoft) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.people_approve), { a.vm.decide(r, true) }, enabled = !busy)
            QuietButton(stringResource(R.string.people_deny), { a.vm.decide(r, false) }, enabled = !busy)
        }
    }
}

@Composable
private fun FeedMemberDialog(feed: Feed, m: FeedMember, a: FeedActions, onDismiss: () -> Unit) {
    val c = Kin.colors
    var role by remember { mutableStateOf(m.role) }
    var removing by remember { mutableStateOf(false) }
    var transferring by remember { mutableStateOf(false) }
    var removePosts by remember { mutableStateOf(false) }
    when {
        removing -> AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.people_remove_confirm, m.displayName)) },
            text = { Column { Text(stringResource(R.string.feed_remove_member_body)); CheckRow(stringResource(R.string.feed_remove_their_posts), removePosts) { removePosts = it } } },
            confirmButton = { TextButton({ onDismiss(); a.vm.removeMember(m, removePosts) }) { Text(stringResource(R.string.people_remove), color = c.danger) } },
            dismissButton = { TextButton({ removing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
        transferring -> AlertDialog(
            onDismissRequest = { transferring = false },
            title = { Text(stringResource(R.string.transfer_confirm_title, m.displayName)) },
            text = { Text(stringResource(R.string.feed_transfer_body, m.displayName, feed.name)) },
            confirmButton = { TextButton({ onDismiss(); a.vm.transfer(m) }) { Text(stringResource(R.string.transfer_do), color = c.danger) } },
            dismissButton = { TextButton({ transferring = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.people_edit_member, m.displayName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.people_role), style = Kin.type.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(stringResource(R.string.role_admin), role == FeedRole.ADMIN, { role = FeedRole.ADMIN })
                        ToggleChip(stringResource(R.string.feed_role_poster), role == FeedRole.POSTER, { role = FeedRole.POSTER })
                        if (a.social != null) ToggleChip(stringResource(R.string.social_role_member), role == FeedRole.MEMBER, { role = FeedRole.MEMBER })
                    }
                    Text(stringResource(when (role) {
                        FeedRole.ADMIN -> R.string.feed_admin_hint
                        FeedRole.MEMBER -> R.string.social_role_member_hint
                        else -> R.string.feed_poster_hint
                    }), style = Kin.type.caption, color = c.inkSoft)
                    if (feed.isOwner) QuietButton(stringResource(R.string.people_make_owner), { transferring = true })
                    QuietButton(stringResource(R.string.people_remove), { removing = true }, danger = true)
                }
            },
            confirmButton = { TextButton({ onDismiss(); if (role != m.role) a.vm.setRole(m, role) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedInviteSection(feed: Feed, state: FeedDetailState, a: FeedActions) {
    val c = Kin.colors
    var purpose by rememberSaveable { mutableStateOf(FeedInvitePurpose.JOIN) }
    var single by rememberSaveable { mutableStateOf(false) }
    var hours by rememberSaveable { mutableStateOf(168) }
    var note by rememberSaveable { mutableStateOf("") }
    var qr by remember { mutableStateOf<FeedInvite?>(null) }
    SectionTitle(stringResource(R.string.people_invite))
    Surface(Modifier.fillMaxWidth()) {
        // Subscribe invites are pre-approved, so only owner and admins make them.
        if (feed.isAdmin) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(stringResource(R.string.feed_invite_join), purpose == FeedInvitePurpose.JOIN, { purpose = FeedInvitePurpose.JOIN })
                ToggleChip(stringResource(R.string.feed_invite_subscribe), purpose == FeedInvitePurpose.SUBSCRIBE, { purpose = FeedInvitePurpose.SUBSCRIBE })
            }
        }
        val effective = if (feed.isAdmin) purpose else FeedInvitePurpose.JOIN
        Text(stringResource(when {
            effective == FeedInvitePurpose.SUBSCRIBE -> R.string.feed_invite_subscribe_hint
            feed.inviteRequiresApproval -> R.string.feed_invite_join_hint_approval
            else -> R.string.feed_invite_join_hint
        }), style = Kin.type.caption, color = c.inkSoft)
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
        PrimaryButton(stringResource(R.string.people_create), { a.vm.createInvite(effective, single, hours, note); note = "" }, busy = state.creatingInvite, icon = KinIcons.Share)
    }
    if (state.invites.isNotEmpty()) {
        SectionTitle(stringResource(R.string.people_active_invites))
        state.invites.forEach { inv ->
            Surface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (inv.purpose == FeedInvitePurpose.SUBSCRIBE) R.string.feed_invite_subscribe else R.string.feed_invite_join),
                            style = Kin.type.caption, color = c.accent)
                        Text(inv.note ?: FeedCodes.format(inv.code), style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val uses = inv.maxUses?.let { stringResource(R.string.people_uses_of, inv.uses, it) } ?: stringResource(R.string.people_uses_n, inv.uses)
                        Text("$uses · " + stringResource(R.string.people_expires_in, Format.relative(inv.expiresAt)), style = Kin.type.caption, color = c.inkSoft)
                    }
                    IconButton({ qr = inv }) { Icon(KinIcons.Grid, stringResource(R.string.feed_invite_show_qr), tint = c.inkSoft) }
                    IconButton({ a.copyInvite(inv) }) { Icon(KinIcons.Copy, stringResource(R.string.people_copy), tint = c.inkSoft) }
                    IconButton({ a.shareInvite(inv) }) { Icon(KinIcons.Share, stringResource(R.string.people_share), tint = c.accent) }
                    IconButton({ a.vm.revokeInvite(inv) }) { Icon(KinIcons.Trash, stringResource(R.string.people_revoke), tint = c.danger) }
                }
            }
        }
    }
    qr?.let { inv -> InviteQrDialog(feed.name, inv) { qr = null } }
}

/** The invite as a QR code and as the short code for "Enter a code". */
@Composable
fun InviteQrDialog(feedName: String, inv: FeedInvite, onDismiss: () -> Unit) {
    val c = Kin.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feedName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) { QrCode(inv.url, Modifier.size(200.dp)) }
                Text(stringResource(R.string.feed_invite_code_label), style = Kin.type.caption, color = c.inkSoft)
                Text(FeedCodes.format(inv.code), style = Kin.type.code, color = c.ink)
                Text(stringResource(R.string.feed_invite_code_hint), style = Kin.type.caption, color = c.inkSoft)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

// ---- frames (subscriptions, feed side)

@Composable
internal fun FramesTab(feed: Feed, state: FeedDetailState, a: FeedActions) {
    val c = Kin.colors
    PageColumn {
        Text(stringResource(R.string.family_frames_lede), style = Kin.type.body, color = c.inkSoft)
        a.addFrame?.let { SecondaryButton(stringResource(R.string.family_add_frame), it, icon = KinIcons.Frame) }
        if (feed.isAdmin && state.pendingSubscriptions.isNotEmpty()) {
            SectionTitle(stringResource(R.string.feed_sub_requests))
            state.pendingSubscriptions.forEach { s ->
                Surface(Modifier.fillMaxWidth()) {
                    Text(app.kinframe.family.ui.servers.remoteName(s.frame.name, s.remote?.host?.ifBlank { null } ?: s.remote?.serverName), style = Kin.type.bodyStrong, color = c.ink)
                    s.requestedByName?.let { Text(stringResource(R.string.feed_sub_requested_by, it), style = Kin.type.caption, color = c.inkSoft) }
                    Text(historyLabel(s.history, s.historyCount), style = Kin.type.caption, color = c.inkSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(stringResource(R.string.people_approve), { a.vm.approve(s) }, enabled = !state.busy)
                        QuietButton(stringResource(R.string.feed_sub_reject), { a.vm.reject(s) }, enabled = !state.busy)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.feed_sub_frames))
        if (state.liveSubscriptions.isEmpty()) Note(stringResource(R.string.feed_sub_none))
        state.liveSubscriptions.forEach { s -> SubscriptionCard(s, feed.isAdmin || s.canDecide, state.busy, a) }
        if (feed.isAdmin && state.endedSubscriptions.isNotEmpty()) {
            SectionTitle(stringResource(R.string.feed_sub_ended))
            state.endedSubscriptions.forEach { s ->
                Surface(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.kinframe.family.ui.servers.remoteName(s.frame.name, s.remote?.host?.ifBlank { null } ?: s.remote?.serverName), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                        SubscriptionStatusPill(s)
                    }
                    removedReason(s)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(s: Subscription, canRemove: Boolean, busy: Boolean, a: FeedActions) {
    val c = Kin.colors
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KinIcons.Frame, null, Modifier.size(20.dp), tint = c.inkSoft)
            Column(Modifier.weight(1f)) {
                Text(s.frame.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                s.frame.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
            }
            SubscriptionStatusPill(s)
        }
        OnlineStatus(s.frame.online, s.frame.lastSeenAt)
        if (canRemove) QuietButton(stringResource(R.string.feed_sub_remove), { confirm = true }, enabled = !busy, danger = true)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.feed_sub_remove_title, s.frame.name)) },
        text = { Text(stringResource(R.string.feed_sub_remove_body)) },
        confirmButton = { TextButton({ confirm = false; a.vm.removeSubscription(s) }) { Text(stringResource(R.string.feed_sub_remove), color = c.danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ---- settings (owner/admin)

@Composable
internal fun SettingsTab(feed: Feed, state: FeedDetailState, a: FeedActions) {
    val c = Kin.colors
    var name by remember(feed.name) { mutableStateOf(feed.name) }
    var picking by remember { mutableStateOf(false) }
    PageColumn {
        SectionTitle(stringResource(R.string.feed_name))
        KinTextField(name, { name = it.take(FeedsViewModel.NAME_MAX) }, stringResource(R.string.feed_name),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        PrimaryButton(stringResource(R.string.common_save), { a.vm.rename(name) }, enabled = name.isNotBlank() && name.trim() != feed.name && !state.busy)
        SectionTitle(stringResource(R.string.feed_cover))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) {
                if (feed.coverUrl != null) Thumb(feed.coverUrl, null, Modifier.fillMaxSize(), corner = 14.dp) else Icon(KinIcons.Image, null, tint = c.inkSoft)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SecondaryButton(stringResource(R.string.feed_cover_choose), { picking = true }, icon = KinIcons.Image)
                if (feed.coverItemId != null) QuietButton(stringResource(R.string.feed_cover_remove), { a.vm.setCover(null) })
            }
        }
        SectionTitle(stringResource(R.string.feed_settings_rules))
        SwitchRow(stringResource(R.string.feed_setting_auto_approve), feed.autoApproveSubscriptions, { a.vm.setFlag("auto_approve_subscriptions", it) },
            hint = stringResource(R.string.feed_setting_auto_approve_hint), enabled = !state.busy)
        SwitchRow(stringResource(R.string.feed_setting_invite_approval), feed.inviteRequiresApproval, { a.vm.setFlag("invite_requires_approval", it) },
            hint = stringResource(R.string.feed_setting_invite_approval_hint), enabled = !state.busy)
        SwitchRow(stringResource(R.string.feed_setting_see_reactions), feed.membersSeeReactions, { a.vm.setFlag("members_see_reactions", it) },
            hint = stringResource(if (a.social != null) R.string.social_setting_see_reactions_hint else R.string.feed_setting_see_reactions_hint), enabled = !state.busy)
        a.social?.settings?.invoke()
        SwitchRow(stringResource(R.string.feed_setting_posters_invite), feed.postersCanInvite, { a.vm.setFlag("posters_can_invite", it) },
            hint = stringResource(R.string.feed_setting_posters_invite_hint), enabled = !state.busy)
        if (a.birthdays != null) {
            SwitchRow(stringResource(R.string.feed_setting_posters_birthdays), feed.postersCanAddBirthdays, { a.vm.setFlag("posters_can_add_birthdays", it) },
                hint = stringResource(R.string.feed_setting_posters_birthdays_hint), enabled = !state.busy)
        }
        a.leavingSettings?.invoke()
        LeaveSection(feed, a)
    }
    if (picking) CoverPicker(feed, (state.posts as? Load.Ready)?.value.orEmpty(), { picking = false }) { id -> picking = false; a.vm.setCover(id) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverPicker(feed: Feed, posts: List<FeedPost>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val items = posts.flatMap { it.items }.filter { it.status != "failed" }.take(60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_cover_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) Text(stringResource(R.string.feed_cover_no_items), color = Kin.colors.inkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { item ->
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onPick(item.id) }) {
                            Thumb(item.thumbUrl, stringResource(R.string.feed_use_as_cover), Modifier.fillMaxSize())
                            if (feed.coverItemId == item.id) Icon(KinIcons.Check, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp), tint = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

// ---------------------------------------------------------------------------------------------------------- invite

@Composable
fun FeedInviteScreen(
    state: FeedInviteState,
    vm: FeedInviteViewModel,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenFrame: (String) -> Unit,
) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.feed_invite_title), onBack = onBack)
        when (val p = state.preview) {
            Load.Loading -> SkeletonList(2, 120.dp)
            is Load.Error -> if (state.invalid) {
                EmptyState(stringResource(R.string.invite_invalid_title), stringResource(p.message)) { SecondaryButton(stringResource(R.string.common_back), onBack) }
            } else ErrorPanel(p.message, { vm.load() })
            is Load.Ready -> {
                val preview = p.value
                val result = state.result
                PageColumn(spacing = 16.dp) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) {
                        if (preview.coverUrl != null) Thumb(preview.coverUrl, null, Modifier.fillMaxSize(), corner = 22.dp) else Icon(KinIcons.People, null, Modifier.size(40.dp), tint = c.inkSoft)
                    }
                    Text(stringResource(R.string.invite_from, preview.invitedByName), style = Kin.type.caption, color = c.inkSoft)
                    Text(
                        stringResource(if (state.isSubscribe) R.string.feed_invite_subscribe_heading else R.string.feed_invite_join_heading, preview.feedName),
                        style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() },
                    )
                    Text(listOfNotNull(preview.ownerName?.let { stringResource(R.string.feed_owned_by, it) },
                        pluralStringResource(R.plurals.feed_members_count, preview.memberCount, preview.memberCount)).joinToString(" · "),
                        style = Kin.type.body, color = c.inkSoft)
                    when {
                        result?.status == FeedInviteStatus.PENDING -> {
                            Note(stringResource(R.string.feed_invite_pending, preview.ownerName ?: preview.invitedByName), tone = NoteTone.Good, icon = KinIcons.Clock)
                            PrimaryButton(stringResource(R.string.common_done), onBack, Modifier.fillMaxWidth())
                        }
                        result?.status == FeedInviteStatus.SUBSCRIBED -> {
                            Note(stringResource(R.string.feed_invite_subscribed, preview.feedName), tone = NoteTone.Good, icon = KinIcons.Check)
                            result.subscriptions.forEach { s ->
                                SecondaryButton(stringResource(R.string.feed_open_frame, s.frame.name), { onOpenFrame(s.frame.id) }, Modifier.fillMaxWidth(), icon = KinIcons.Frame)
                            }
                        }
                        result != null -> {
                            Note(stringResource(R.string.feed_invite_joined, preview.feedName), tone = NoteTone.Good, icon = KinIcons.Check)
                            PrimaryButton(stringResource(R.string.feed_open), { onOpenFeed(result.feed.id) }, Modifier.fillMaxWidth())
                        }
                        preview.alreadyMember && !state.isSubscribe -> {
                            Note(stringResource(R.string.feed_invite_already))
                            SecondaryButton(stringResource(R.string.common_back), onBack, Modifier.fillMaxWidth())
                        }
                        state.isSubscribe -> SubscribeInviteForm(state, vm)
                        else -> {
                            Text(stringResource(if (preview.requiresApproval) R.string.feed_invite_needs_approval else R.string.feed_invite_instant),
                                style = Kin.type.body, color = c.inkSoft)
                            state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                            PrimaryButton(stringResource(R.string.feed_invite_join_do), vm::accept, Modifier.fillMaxWidth(), busy = state.accepting)
                            QuietButton(stringResource(R.string.invite_not_now), onBack, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscribeInviteForm(state: FeedInviteState, vm: FeedInviteViewModel) {
    val c = Kin.colors
    val preview = (state.preview as? Load.Ready)?.value ?: return
    Text(stringResource(R.string.feed_invite_subscribe_body), style = Kin.type.body, color = c.inkSoft)
    if (preview.frames.isEmpty()) {
        Note(stringResource(R.string.feed_invite_no_frames), tone = NoteTone.Warn)
        return
    }
    Text(stringResource(R.string.feed_invite_pick_frames), style = Kin.type.label, color = c.ink)
    preview.frames.forEach { f ->
        val taken = SubscriptionStatus.isLive(f.subscriptionStatus)
        Row(
            Modifier.fillMaxWidth().let { if (!taken) it.clickable(role = Role.Checkbox) { vm.toggleFrame(f.frameId) } else it }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(taken || f.frameId in state.selected, null, enabled = !taken, colors = CheckboxDefaults.colors(checkedColor = c.accent))
            Column(Modifier.padding(start = 8.dp)) {
                Text(f.frameName, style = Kin.type.body, color = c.ink)
                if (taken) Text(stringResource(R.string.feed_invite_frame_already), style = Kin.type.caption, color = c.inkSoft)
            }
        }
    }
    HistoryPicker(state.history, state.historyCount) { o, n -> vm.setHistory(o, n) }
    if (state.birthdaysAvailable) {
        SwitchRow(stringResource(R.string.frame_feeds_show_birthdays), state.birthdays, vm::setBirthdays, hint = stringResource(R.string.frame_feeds_show_birthdays_hint))
    }
    Text(stringResource(R.string.feed_privacy_note, preview.feedName), style = Kin.type.caption, color = c.inkSoft)
    state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
    PrimaryButton(stringResource(R.string.feed_invite_subscribe_do), vm::accept, Modifier.fillMaxWidth(), enabled = state.canAccept, busy = state.accepting)
}

// ---------------------------------------------------------------------------------------------------------- a frame's feeds

/** A frame's Feeds tab (feeds.md §3.8): its subscriptions, subscribing, and "play one feed". */
@Composable
fun FrameFeedsTab(
    frame: Frame,
    state: FrameFeedsState,
    vm: FrameFeedsViewModel,
    onOpenFeed: (String) -> Unit,
    onEnterCode: () -> Unit,
    /** The server has birthdays: per-subscription "Show birthdays" (birthdays.md §3, frame owner). */
    birthdays: Boolean = false,
    /** Families on other servers (README §5.22); null without discovery `remote_families`. */
    remote: (@Composable () -> Unit)? = null,
) {
    val c = Kin.colors
    val manage = frame.can { it.changeSettings }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var removing by remember { mutableStateOf<Subscription?>(null) }
    PageColumn {
        Text(stringResource(R.string.frame_feeds_lede, frame.name), style = Kin.type.body, color = c.inkSoft)
        remote?.invoke()
        when (val subs = state.subscriptions) {
            Load.Loading -> SkeletonList(2, 96.dp)
            is Load.Error -> ErrorPanel(subs.message, { vm.load() })
            is Load.Ready -> {
                if (state.live.isEmpty()) Note(stringResource(R.string.frame_feeds_none))
                state.live.forEach { s ->
                    Surface(Modifier.fillMaxWidth(), onClick = { onOpenFeed(s.feed.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) {
                                if (s.feed.coverUrl != null) Thumb(s.feed.coverUrl, null, Modifier.fillMaxSize()) else Icon(KinIcons.People, null, tint = c.inkSoft)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.feed.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.feed.adminNames.isNotEmpty()) Text(stringResource(R.string.frame_feeds_admins, s.feed.adminNames.joinToString(", ")),
                                    style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(historyLabel(s.history, s.historyCount), style = Kin.type.caption, color = c.inkSoft)
                            }
                            SubscriptionStatusPill(s)
                        }
                        s.backfill?.takeIf { it.running }?.let { b ->
                            Text(stringResource(R.string.frame_feeds_backfill, b.placed, b.total), style = Kin.type.caption, color = c.inkSoft)
                        }
                        if (s.status == SubscriptionStatus.PAUSED) Text(stringResource(R.string.frame_feeds_paused_hint), style = Kin.type.caption, color = c.warn)
                        if (state.playingFeed == s.feed.id) Pill(stringResource(R.string.frame_feeds_playing_only), c.accentSoft, c.onAccentSoft, icon = KinIcons.Play)
                        if (birthdays) {
                            val count = s.birthdayCount
                            SwitchRow(
                                stringResource(R.string.frame_feeds_show_birthdays), s.birthdays, { vm.setBirthdays(s, it) },
                                hint = if (count != null) pluralStringResource(R.plurals.frame_feeds_birthday_count, count, count) else stringResource(R.string.frame_feeds_show_birthdays_hint),
                                enabled = (manage || s.canManage) && !state.busy,
                            )
                            if (s.birthdays && s.frame.birthdaysSupported == false) Note(stringResource(R.string.frame_feeds_birthdays_update_app, frame.name), tone = NoteTone.Warn)
                        }
                        if (manage || s.canManage) {
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (s.status) {
                                    SubscriptionStatus.ACTIVE -> SecondaryButton(stringResource(R.string.frame_feeds_pause), { vm.pause(s) }, enabled = !state.busy)
                                    SubscriptionStatus.PAUSED -> SecondaryButton(stringResource(R.string.frame_feeds_resume), { vm.resume(s) }, enabled = !state.busy)
                                }
                                QuietButton(stringResource(R.string.frame_feeds_history), { editing = s }, enabled = !state.busy)
                                if (s.status == SubscriptionStatus.ACTIVE) {
                                    if (state.playingFeed == s.feed.id) QuietButton(stringResource(R.string.frame_feeds_play_all), { vm.playOnly(null) }, enabled = !state.busy)
                                    else QuietButton(stringResource(R.string.frame_feeds_play_only), { vm.playOnly(s.feed.id) }, enabled = !state.busy)
                                }
                                QuietButton(stringResource(if (s.status == SubscriptionStatus.PENDING) R.string.frame_feeds_withdraw else R.string.frame_feeds_unsubscribe),
                                    { removing = s }, enabled = !state.busy, danger = true)
                            }
                        }
                    }
                }
                if (manage) {
                    SectionTitle(stringResource(R.string.frame_feeds_add))
                    if (state.subscribable.isEmpty()) Text(stringResource(R.string.frame_feeds_add_none), style = Kin.type.caption, color = c.inkSoft)
                    state.subscribable.forEach { f ->
                        // Shown at once with the last 50 posts (docs/spec/simple-app.md §5); no dialog.
                        Surface(Modifier.fillMaxWidth(), onClick = { vm.subscribe(f, SubscriptionHistory.LAST_N, app.kinframe.family.ui.family.FamilyFrames.HISTORY_COUNT, true) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(KinIcons.Plus, null, tint = c.accent)
                                Text(f.name, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                FeedRoleBadge(f.role)
                            }
                        }
                    }
                    QuietButton(stringResource(R.string.frame_feeds_have_code), onEnterCode)
                }
                if (state.ended.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.feed_sub_ended))
                    state.ended.forEach { s ->
                        Surface(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.feed.name, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                SubscriptionStatusPill(s)
                            }
                            removedReason(s)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { s ->
        var option by remember(s.id) { mutableStateOf(s.history) }
        var count by remember(s.id) { mutableStateOf(s.historyCount ?: FeedRules.DEFAULT_LAST_N) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.frame_feeds_history_title, s.feed.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryPicker(option, count) { o, n -> option = o; count = n }
                    Text(stringResource(R.string.frame_feeds_history_change_hint), style = Kin.type.caption, color = c.inkSoft)
                }
            },
            confirmButton = { TextButton({ editing = null; vm.setHistory(s, option, count) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.frame_feeds_unsubscribe_title, s.feed.name)) },
            text = { Text(stringResource(R.string.frame_feeds_unsubscribe_body)) },
            confirmButton = { TextButton({ removing = null; vm.unsubscribe(s) }) { Text(stringResource(R.string.frame_feeds_unsubscribe), color = c.danger) } },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** "Post to a feed" from a sent item: pick feeds; the items go there without a new upload. */
@Composable
fun PostToFeedDialog(feeds: List<Feed>, already: Set<String>, onDismiss: () -> Unit, onPost: (List<Feed>) -> Unit) {
    val c = Kin.colors
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_post_to_feed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (feeds.isEmpty()) Text(stringResource(R.string.feeds_empty_body), color = c.inkSoft)
                feeds.forEach { f ->
                    val done = f.id in already
                    Row(Modifier.fillMaxWidth().let { if (!done) it.clickable(role = Role.Checkbox) { chosen = if (f.id in chosen) chosen - f.id else chosen + f.id } else it }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(done || f.id in chosen, null, enabled = !done, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(f.name, style = Kin.type.body, color = c.ink)
                            Text(if (done) stringResource(R.string.feed_already_posted) else pluralStringResource(R.plurals.feed_frames_count, f.subscriptionCount, f.subscriptionCount),
                                style = Kin.type.caption, color = c.inkSoft)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onPost(feeds.filter { it.id in chosen }) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.feed_post_do)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
