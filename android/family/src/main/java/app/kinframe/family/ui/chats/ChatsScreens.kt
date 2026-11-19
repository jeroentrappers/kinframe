package app.kinframe.family.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.ConversationKind
import app.kinframe.core.social.Message
import app.kinframe.core.social.MuteFor
import app.kinframe.core.social.Person
import app.kinframe.core.social.SystemMessageType
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.ContentMaxWidth
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
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.engagement.milestoneTitle
import app.kinframe.family.ui.social.ActionMenu
import app.kinframe.family.ui.social.MenuAction
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.UnreadPill
import app.kinframe.family.ui.social.longPressMenu
import app.kinframe.family.ui.social.personName
import app.kinframe.family.util.Format

/** The conversation's picture: group photo, family cover, the other person's avatar. */
@Composable
fun ConversationAvatar(c: Conversation, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val k = Kin.colors
    when {
        c.photoUrl != null -> Thumb(c.photoUrl, null, Modifier.size(size), corner = size)
        c.isDirect -> PersonAvatar(c.other, size)
        else -> Box(Modifier.size(size).clip(CircleShape).background(k.sageSoft), contentAlignment = Alignment.Center) {
            Icon(if (c.isFamily || c.isCircle) KinIcons.Home else KinIcons.People, null, tint = k.onSageSoft)
        }
    }
}

@Composable
fun conversationName(c: Conversation): String = when {
    c.isDirect && c.other?.formerMember == true -> stringResource(R.string.social_former_member)
    else -> c.name
}

/** One line for a message in the list and in quotes: text, "📷 Photo", "🎤 Voice message", system lines. */
@Composable
fun messagePreview(m: Message, me: String?): String {
    val who = if (m.author?.userId == me) stringResource(R.string.social_you) else personName(m.author)
    val body = when {
        m.deleted -> stringResource(if (m.removedByAdmin) R.string.chat_message_removed else R.string.chat_message_deleted)
        m.isSystem -> m.system?.let { systemText(it) }.orEmpty()
        m.voice != null -> stringResource(R.string.chat_voice_message)
        m.sharedPost != null -> stringResource(R.string.chat_shared_post)
        !m.body.isNullOrBlank() -> m.body!!
        m.items.isNotEmpty() -> pluralStringResource(R.plurals.chat_photos, m.items.size, m.items.size)
        else -> ""
    }
    return if (m.isSystem || who.isEmpty()) body else "$who: $body"
}

/** A system message in the viewer's language from its fields (README §5.19). */
@Composable
fun systemText(s: app.kinframe.core.social.SystemMessage): String {
    val who = personName(s.person)
    val targets = (s.frameNames + s.feedNames).joinToString(", ")
    return when (s.type) {
        SystemMessageType.PUT_ON_FRAME -> pluralStringResource(R.plurals.chat_system_put_on_frame, s.itemCount ?: 1, who, s.itemCount ?: 1, targets)
        SystemMessageType.STREAK_MILESTONE -> pluralStringResource(R.plurals.chat_system_streak, s.weeks ?: 0, s.weeks ?: 0)
        SystemMessageType.MONTHLY_RECAP -> stringResource(R.string.chat_system_recap, monthName(s.month))
        SystemMessageType.PROMPT -> stringResource(R.string.chat_system_prompt, s.promptTitle.orEmpty())
        SystemMessageType.MEMBER_JOINED -> stringResource(R.string.chat_system_joined, who)
        SystemMessageType.MEMBER_LEFT -> stringResource(R.string.chat_system_left, who)
        else -> ""
    }
}

fun monthName(month: String?): String = runCatching {
    val ym = java.time.YearMonth.parse(month)
    ym.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.getDefault()))
}.getOrDefault(month.orEmpty())

@Composable
fun ChatsScreen(
    s: ChatsState,
    me: String?,
    onOpen: (Conversation) -> Unit,
    onNew: () -> Unit,
    onNewGroup: () -> Unit,
    onRefresh: () -> Unit,
    onToggleArchived: () -> Unit,
    onArchive: (Conversation, Boolean) -> Unit,
    topActions: @Composable () -> Unit = {},
    /** The person's user id on a chat's server (Chats merges every account's conversations). */
    meFor: (Conversation) -> String? = { me },
    /** A chat's server chip when signed in to several servers (multi-server.md §2.2), else null. */
    serverChip: (Conversation) -> String? = { null },
) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.chat_title)) {
            IconButton(onNewGroup) { Icon(KinIcons.People, stringResource(R.string.chat_new_group), tint = c.inkSoft) }
            IconButton(onNew) { Icon(KinIcons.Plus, stringResource(R.string.chat_new), tint = c.accent) }
            topActions()
        }
        when (val l = s.conversations) {
            Load.Loading -> SkeletonList(4, 64.dp)
            is Load.Error -> ErrorPanel(l.message, onRefresh)
            is Load.Ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (l.value.isEmpty()) item("empty") {
                        EmptyState(stringResource(R.string.chat_empty_title), stringResource(R.string.chat_empty_body)) {
                            PrimaryButton(stringResource(R.string.chat_new), onNew, icon = KinIcons.Plus)
                        }
                    }
                    items(l.value, key = { it.id }) { conv -> ConversationRow(conv, meFor(conv), { onOpen(conv) }, { onArchive(conv, true) }, server = serverChip(conv)) }
                    item("archived") {
                        TextButton(onToggleArchived, Modifier.padding(horizontal = 12.dp)) {
                            Text(stringResource(if (s.showArchived) R.string.chat_hide_archived else R.string.chat_show_archived), color = c.inkSoft)
                        }
                    }
                    if (s.showArchived) items(s.archived, key = { "a" + it.id }) { conv -> ConversationRow(conv, meFor(conv), { onOpen(conv) }, { onArchive(conv, false) }, archived = true, server = serverChip(conv)) }
                    item("note") { Text(stringResource(R.string.chat_operator_note), style = Kin.type.caption, color = c.inkFaint, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conv: Conversation, me: String?, onClick: () -> Unit, onArchive: () -> Unit, archived: Boolean = false, server: String? = null) {
    val c = Kin.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().longPressMenu({ menu = true }, onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConversationAvatar(conv)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(conversationName(conv), style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (conv.isFamily) Pill(stringResource(R.string.chat_kind_family), c.sageSoft, c.onSageSoft)
                    if (conv.isCircle) Pill(stringResource(R.string.chat_kind_circle), c.sageSoft, c.onSageSoft)
                    if (conv.muted) Icon(KinIcons.VolumeOff, stringResource(R.string.chat_muted), Modifier.size(14.dp), tint = c.inkFaint)
                    server?.let { Pill(it, c.paperDeep, c.inkSoft, icon = KinIcons.Server) }
                }
                Text(conv.lastMessage?.let { messagePreview(it, me) } ?: stringResource(R.string.chat_no_messages),
                    style = Kin.type.caption, color = if (conv.unreadCount > 0) c.ink else c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Format.relative(conv.lastActivityAt), style = Kin.type.caption, color = c.inkFaint)
                UnreadPill(conv.unreadCount, mentions = conv.unreadMentions > 0)
            }
        }
        ActionMenu(menu, listOf(MenuAction(if (archived) R.string.chat_unarchive else R.string.chat_archive) { onArchive() })) { menu = false }
    }
}

/** "New chat" (pick one person) or "New group" (a name and two or more people). */
@Composable
fun NewChatScreen(s: ChatsState, group: Boolean, onBack: () -> Unit, onStart: (List<String>, String?) -> Unit, onRetry: () -> Unit) {
    val c = Kin.colors
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(if (group) R.string.chat_new_group else R.string.chat_new), onBack = onBack)
        PageColumn(spacing = 10.dp) {
            Text(stringResource(R.string.chat_contacts_hint), style = Kin.type.caption, color = c.inkSoft)
            if (group) KinTextField(name, { name = it.take(ChatsViewModel.NAME_MAX) }, stringResource(R.string.chat_group_name), placeholder = stringResource(R.string.chat_group_name_placeholder))
            when (val l = s.contacts) {
                Load.Loading -> SkeletonList(3, 56.dp)
                is Load.Error -> ErrorPanel(l.message, onRetry)
                is Load.Ready -> if (l.value.isEmpty()) Note(stringResource(R.string.chat_no_contacts)) else Surface(Modifier.fillMaxWidth(), padding = 6.dp) {
                    l.value.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        ContactRow(p, selected = p.person.userId in picked, multi = group) {
                            if (group) picked = if (p.person.userId in picked) picked - p.person.userId else picked + p.person.userId
                            else onStart(listOf(p.person.userId), null)
                        }
                    }
                }
            }
            if (group) PrimaryButton(
                stringResource(R.string.chat_create_group), { onStart(picked, name) }, Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && picked.size >= 2, busy = s.creating,
            )
        }
    }
}

@Composable
fun ContactRow(p: Person, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = if (multi) Role.Checkbox else Role.Button, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(p.person, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(personName(p.person), style = Kin.type.bodyStrong, color = c.ink)
            val shared = (p.feeds.map { it.feedName } + p.frames.map { it.frameName }).joinToString(", ")
            if (shared.isNotEmpty()) Text(shared, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (multi) Checkbox(selected, null, colors = CheckboxDefaults.colors(checkedColor = c.accent))
    }
}

/** A conversation's details: members, name and photo, mute, archive, leave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationInfoScreen(
    s: ConversationInfoState,
    vm: ConversationInfoViewModel,
    me: String?,
    onBack: () -> Unit,
    onPerson: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onOpenFamily: ((String) -> Unit)?,
) {
    val c = Kin.colors
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.chat_info_title), onBack = onBack)
        when (val l = s.conversation) {
            Load.Loading -> SkeletonList(3, 64.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> {
                val conv = l.value
                var name by remember(conv.name) { mutableStateOf(conv.name) }
                PageColumn {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ConversationAvatar(conv, 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(conversationName(conv), style = Kin.type.headline, color = c.ink)
                            Text(pluralStringResource(R.plurals.chat_members_count, conv.memberCount, conv.memberCount), style = Kin.type.caption, color = c.inkSoft)
                        }
                    }
                    if (conv.isGroup && conv.isAdmin) {
                        KinTextField(name, { name = it.take(ChatsViewModel.NAME_MAX) }, stringResource(R.string.chat_group_name))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(stringResource(R.string.common_save), { vm.rename(name) }, enabled = name.isNotBlank() && name.trim() != conv.name && !s.busy)
                            SecondaryButton(stringResource(R.string.chat_group_photo), onPickPhoto, icon = KinIcons.Image)
                            if (conv.photoUrl != null) QuietButton(stringResource(R.string.chat_group_photo_remove), vm::removePhoto)
                        }
                    }
                    if ((conv.isFamily || conv.isCircle) && conv.feedId != null && onOpenFamily != null) {
                        Note(stringResource(if (conv.isFamily) R.string.chat_family_members_note else R.string.chat_circle_members_note))
                        SecondaryButton(stringResource(R.string.chat_open_family), { onOpenFamily(conv.feedId!!) }, icon = KinIcons.People)
                    }
                    SectionTitle(stringResource(R.string.chat_mute_title))
                    app.kinframe.family.ui.social.ChoiceRow(
                        listOf(MuteFor.OFF to stringResource(R.string.chat_mute_off)) + MuteFor.VALUES.map { it to stringResource(muteLabel(it)) },
                        if (!conv.muted) MuteFor.OFF else if (conv.mutedUntil == null) MuteFor.ALWAYS else "", vm::mute,
                    )
                    conv.mutedUntil?.let { Text(stringResource(R.string.chat_muted_until, Format.dateTime(it)), style = Kin.type.caption, color = c.inkSoft) }
                    SectionTitle(stringResource(R.string.chat_members), action = if (conv.isGroup) ({
                        TextButton({ adding = true; vm.loadContacts() }) { Text(stringResource(R.string.chat_add_people), color = c.accent) }
                    }) else null)
                    Surface(Modifier.fillMaxWidth(), padding = 6.dp) {
                        conv.members.forEachIndexed { i, m ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = m.person.userId != me && !m.person.formerMember) {
                                        if (conv.isGroup && conv.isAdmin) menu = true else onPerson(m.person.userId)
                                    }.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PersonAvatar(m.person, 36.dp)
                                    Text(personName(m.person) + if (m.person.userId == me) " (" + stringResource(R.string.social_you) + ")" else "",
                                        style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (m.role == app.kinframe.core.social.ConversationRole.ADMIN && conv.isGroup) Pill(stringResource(R.string.role_admin), c.sageSoft, c.onSageSoft)
                                }
                                ActionMenu(menu, listOf(
                                    MenuAction(R.string.chat_view_profile) { onPerson(m.person.userId) },
                                    MenuAction(if (m.role == app.kinframe.core.social.ConversationRole.ADMIN) R.string.chat_remove_admin else R.string.chat_make_admin) {
                                        vm.makeAdmin(m.person.userId, m.role != app.kinframe.core.social.ConversationRole.ADMIN)
                                    },
                                    MenuAction(R.string.chat_remove_member, danger = true) { vm.remove(m.person.userId) },
                                )) { menu = false }
                            }
                        }
                    }
                    if (conv.isGroup) QuietButton(stringResource(R.string.chat_leave_group), { leaving = true }, danger = true)
                    Text(stringResource(R.string.chat_operator_note), style = Kin.type.caption, color = c.inkFaint)
                }
                if (adding) AddPeopleDialog(s.contacts.filter { p -> conv.members.none { it.person.userId == p.person.userId } }, { adding = false }) { ids -> adding = false; vm.add(ids) }
                if (leaving) AlertDialog(
                    onDismissRequest = { leaving = false },
                    title = { Text(stringResource(R.string.chat_leave_title, conv.name)) },
                    text = { Text(stringResource(R.string.chat_leave_body)) },
                    confirmButton = { TextButton({ leaving = false; vm.leave() }) { Text(stringResource(R.string.chat_leave_group), color = c.danger) } },
                    dismissButton = { TextButton({ leaving = false }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }
    }
}

fun muteLabel(v: String): Int = when (v) {
    MuteFor.HOUR -> R.string.chat_mute_1h
    MuteFor.EIGHT_HOURS -> R.string.chat_mute_8h
    MuteFor.WEEK -> R.string.chat_mute_1w
    MuteFor.ALWAYS -> R.string.chat_mute_always
    else -> R.string.chat_mute_off
}

@Composable
fun AddPeopleDialog(people: List<Person>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_add_people)) },
        text = {
            if (people.isEmpty()) Text(stringResource(R.string.chat_no_contacts))
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(people, key = { it.person.userId }) { p ->
                    ContactRow(p, p.person.userId in picked, multi = true) { picked = if (p.person.userId in picked) picked - p.person.userId else picked + p.person.userId }
                }
            }
        },
        confirmButton = { TextButton({ onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.chat_add)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A contact's profile: shared families and frames, start a chat, block, the milestones they show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonScreen(s: PersonState, vm: PersonViewModel, me: String?, chats: Boolean, onBack: () -> Unit) {
    val c = Kin.colors
    var blocking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar((s.person as? Load.Ready)?.value?.let { personName(it.person) }.orEmpty(), onBack = onBack)
        when (val l = s.person) {
            Load.Loading -> SkeletonList(2, 80.dp)
            is Load.Error -> ErrorPanel(l.message, { vm.load() })
            is Load.Ready -> PageColumn {
                val p = l.value
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PersonAvatar(p.person, 72.dp)
                    Text(personName(p.person), style = Kin.type.headline, color = c.ink)
                }
                if (p.feeds.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.chat_person_families))
                    Text(p.feeds.joinToString(", ") { it.feedName }, style = Kin.type.body, color = c.inkSoft)
                }
                if (p.frames.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.chat_person_frames))
                    Text(p.frames.joinToString(", ") { it.frameName }, style = Kin.type.body, color = c.inkSoft)
                }
                if (p.milestones.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.engagement_milestones))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.milestones.forEach { Pill("✨ " + milestoneTitle(it), c.accentSoft, c.onAccentSoft) }
                    }
                }
                if (p.person.userId != me && chats) {
                    if (!p.blocked) PrimaryButton(stringResource(R.string.chat_send_message), vm::chat, Modifier.fillMaxWidth(), busy = s.busy, icon = KinIcons.Message)
                    if (p.blocked) {
                        Note(stringResource(R.string.chat_blocked_note))
                        SecondaryButton(stringResource(R.string.chat_unblock), { vm.setBlocked(false) })
                    } else QuietButton(stringResource(R.string.chat_block), { blocking = true }, danger = true)
                }
            }
        }
    }
    if (blocking) AlertDialog(
        onDismissRequest = { blocking = false },
        title = { Text(stringResource(R.string.chat_block_title, (s.person as? Load.Ready)?.value?.person?.displayName.orEmpty())) },
        text = { Text(stringResource(R.string.chat_block_body)) },
        confirmButton = { TextButton({ blocking = false; vm.setBlocked(true) }) { Text(stringResource(R.string.chat_block), color = c.danger) } },
        dismissButton = { TextButton({ blocking = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** "Share in chat": pick a conversation and add a line. */
@Composable
fun ShareInChatDialog(conversations: Load<List<Conversation>>, onDismiss: () -> Unit, onShare: (Conversation, String) -> Unit) {
    var picked by remember { mutableStateOf<Conversation?>(null) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_share_in_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (conversations) {
                    Load.Loading -> Text(stringResource(R.string.social_loading))
                    is Load.Error -> Text(stringResource(conversations.message))
                    is Load.Ready -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(conversations.value.filter { !it.readOnly }, key = { it.id }) { conv ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (picked?.id == conv.id) Kin.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable(role = Role.RadioButton) { picked = conv }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ConversationAvatar(conv, 36.dp)
                                Text(conversationName(conv), style = Kin.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                KinTextField(text, { text = it.take(500) }, stringResource(R.string.chat_share_line), placeholder = stringResource(R.string.chat_share_line_placeholder))
            }
        },
        confirmButton = { TextButton({ picked?.let { onShare(it, text.trim()) } }, enabled = picked != null) { Text(stringResource(R.string.chat_share)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
