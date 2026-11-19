package app.kinframe.family.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kinframe.core.api.Item
import app.kinframe.core.api.MediaKind
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.LinkPreviewStatus
import app.kinframe.core.social.Message
import app.kinframe.core.social.PostCard
import app.kinframe.core.social.ReactionList
import app.kinframe.core.social.SystemMessageType
import app.kinframe.core.social.TypingActivity
import app.kinframe.family.R
import app.kinframe.family.history.Delivery
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.ui.social.ActionMenu
import app.kinframe.family.ui.social.MentionBody
import app.kinframe.family.ui.social.MentionField
import app.kinframe.family.ui.social.MenuAction
import app.kinframe.family.ui.social.MessageRules
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.ReactionPicker
import app.kinframe.family.ui.social.ReactionRow
import app.kinframe.family.ui.social.ReportDialog
import app.kinframe.family.ui.social.WhoReactedSheet
import app.kinframe.family.ui.social.longPressMenu
import app.kinframe.family.ui.social.personName
import app.kinframe.family.util.Format
import kotlinx.coroutines.delay

/** What the conversation screen can do besides its view model; wired by the root. */
class ConversationActions(
    val vm: ConversationViewModel,
    val me: String?,
    val onBack: () -> Unit,
    val openInfo: () -> Unit,
    val openPost: (String) -> Unit,
    val openPerson: (String) -> Unit,
    val openRecap: (feedId: String, month: String) -> Unit,
    /** The family's invite link for relatives without an account (its People tab). */
    val invite: ((feedId: String) -> Unit)?,
    val openUrl: (String) -> Unit,
    val openMedia: (Item) -> Unit,
    val voiceUrl: suspend (itemId: String) -> String,
    val whoReacted: (messageId: String) -> suspend () -> ReactionList,
    val copy: (String) -> Unit,
    val voice: VoiceRecorder?,
    val editHours: Int,
    val safety: Boolean,
    val showError: (Int) -> Unit,
)

@Composable
fun ConversationScreen(s: ConversationState, a: ConversationActions) {
    val c = Kin.colors
    val player = rememberVoicePlayer()
    var reporting by remember { mutableStateOf<Message?>(null) }
    var putting by remember { mutableStateOf<Message?>(null) }
    var who by remember { mutableStateOf<String?>(null) }
    val conv = s.conversationOrNull
    CompositionLocalProvider(LocalVoicePlayer provides player) {
        Column(Modifier.fillMaxSize().background(c.paper).imePadding()) {
            KinTopBar(conv?.let { conversationName(it) }.orEmpty(), onBack = a.onBack) {
                if (conv != null) IconButton(a.openInfo) { Icon(KinIcons.Info, stringResource(R.string.chat_info_title), tint = c.inkSoft) }
            }
            when (val l = s.conversation) {
                Load.Loading -> SkeletonList(5, 56.dp)
                is Load.Error -> ErrorPanel(l.message, { a.vm.load() })
                is Load.Ready -> {
                    Subtitle(s, l.value)
                    Messages(s, l.value, a, Modifier.weight(1f), onReport = { reporting = it }, onPut = { putting = it; a.vm.loadTargets() }, onWho = { who = it })
                    Composer(s, l.value, a)
                }
            }
        }
    }
    reporting?.let { m -> ReportDialog({ reporting = null }) { reason, note -> a.vm.report(m, reason, note) } }
    putting?.let { m -> PutOnFrameDialog(s.targets, mine = m.author?.userId == a.me, { putting = null }) { frames, feeds, circles, names -> putting = null; a.vm.putOnFrame(m, frames, feeds, circles, names) } }
    who?.let { id -> WhoReactedSheet(a.whoReacted(id)) { who = null } }
}

@Composable
private fun Subtitle(s: ConversationState, conv: Conversation) {
    val c = Kin.colors
    val typing = s.typing.firstOrNull()
    val text = when {
        typing != null && typing.activity == TypingActivity.RECORDING -> stringResource(R.string.chat_recording_now, typing.name)
        typing != null && s.typing.size > 1 -> stringResource(R.string.chat_several_typing)
        typing != null -> stringResource(R.string.chat_typing_now, typing.name)
        conv.isDirect -> null
        else -> pluralStringResource(R.plurals.chat_members_count, conv.memberCount, conv.memberCount)
    }
    text?.let { Text(it, style = Kin.type.caption, color = if (typing != null) c.accent else c.inkSoft, modifier = Modifier.padding(horizontal = 20.dp)) }
}

@Composable
private fun Messages(s: ConversationState, conv: Conversation, a: ConversationActions, modifier: Modifier, onReport: (Message) -> Unit, onPut: (Message) -> Unit, onWho: (String) -> Unit) {
    val c = Kin.colors
    val list = rememberLazyListState()
    val reversed = remember(s.messages) { s.messages.asReversed() }
    val atTop by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } == true } }
    LaunchedEffect(atTop) { if (atTop) a.vm.loadOlder() }
    // A new message at the bottom: keep it in view when we were already there.
    LaunchedEffect(s.messages.lastOrNull()?.id, s.pending.size, s.uploads.size) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    val lastMine = s.messages.lastOrNull { it.author?.userId == a.me && !it.isSystem }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(), state = list, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.typing.isNotEmpty()) item("typing") { TypingDots() }
            items(s.uploads.asReversed(), key = { "up" + it.id }) { u -> UploadBubble(u.items.size, u.chat?.voice == true) }
            items(s.pending.asReversed(), key = { "p" + it.clientId }) { p -> PendingBubble(p, a) }
            items(reversed, key = { it.id }) { m ->
                if (m.isSystem) SystemLine(m, conv, a)
                else Bubble(m, conv, a, seen = if (m.id == lastMine?.id) ReadReceipts.seenBy(conv, m, a.me) else emptyList(), onReport = onReport, onPut = onPut, onWho = onWho)
            }
            if (s.loadingOlder) item("older") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = c.accent) } }
            if (!s.hasMoreBefore && conv.isFamily && conv.feedId != null && a.invite != null) item("invite") {
                Surface(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_invite_card_title), style = Kin.type.bodyStrong, color = c.ink)
                    Text(stringResource(R.string.chat_invite_card_body), style = Kin.type.caption, color = c.inkSoft)
                    SecondaryButton(stringResource(R.string.chat_invite_card_button), { a.invite.invoke(conv.feedId!!) }, icon = KinIcons.UserPlus)
                }
            }
            if (!s.hasMoreBefore) item("start") {
                Text(stringResource(R.string.chat_operator_note), style = Kin.type.caption, color = c.inkFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val c = Kin.colors
    var n by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) { while (true) { delay(400); n = n % 3 + 1 } }
    Text("•".repeat(n), style = Kin.type.title, color = c.inkSoft, modifier = Modifier.padding(start = 12.dp))
}

@Composable
private fun UploadBubble(count: Int, voice: Boolean) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(c.accentSoft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (voice) stringResource(R.string.chat_voice_sending) else pluralStringResource(R.plurals.chat_photos_sending, count, count), style = Kin.type.caption, color = c.onAccentSoft)
            LinearProgressIndicator(Modifier.width(160.dp), color = c.accent, trackColor = c.surface)
        }
    }
}

@Composable
private fun PendingBubble(p: PendingText, a: ConversationActions) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(c.accentSoft.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            MentionBody(p.body, p.mentions, color = c.onAccentSoft)
            if (p.failed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ a.vm.retry(p.clientId) }) { Text(stringResource(R.string.common_try_again), color = c.danger) }
                TextButton({ a.vm.discard(p.clientId) }) { Text(stringResource(R.string.common_remove), color = c.inkSoft) }
            } else Text(stringResource(R.string.chat_sending), style = Kin.type.caption, color = c.inkFaint)
        }
    }
}

@Composable
private fun SystemLine(m: Message, conv: Conversation, a: ConversationActions) {
    val c = Kin.colors
    val sys = m.system ?: return
    val recap = sys.type == SystemMessageType.MONTHLY_RECAP && conv.feedId != null && sys.month != null
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val special = sys.type == SystemMessageType.STREAK_MILESTONE || sys.type == SystemMessageType.PROMPT || recap
        Text(
            (if (sys.type == SystemMessageType.STREAK_MILESTONE) "🔥 " else "") + systemText(sys),
            style = if (special) Kin.type.bodyStrong else Kin.type.caption, color = if (special) c.ink else c.inkSoft, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (special) c.accentSoft else Color.Transparent)
                .let { if (recap) it.clickable(role = Role.Button) { a.openRecap(conv.feedId!!, sys.month!!) } else it }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (m.reactions.total > 0 || special) ReactionRow(m.reactions, a.me, { r -> a.vm.react(m, r) }, null, compact = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: Message, conv: Conversation, a: ConversationActions, seen: List<app.kinframe.core.social.PersonRef>, onReport: (Message) -> Unit, onPut: (Message) -> Unit, onWho: (String) -> Unit) {
    val c = Kin.colors
    val mine = m.author?.userId == a.me
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val moderator = conv.isAdmin && conv.isGroup
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!mine && !conv.isDirect) {
            Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, enabled = m.author?.formerMember == false) { m.author?.let { a.openPerson(it.userId) } }) {
                PersonAvatar(m.author, 28.dp)
            }
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box {
                Column(
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) c.accentSoft else c.surface)
                        .longPressMenu({ if (!m.deleted) menu = true }).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!mine && !conv.isDirect) Text(personName(m.author), style = Kin.type.label, color = c.accent)
                    if (m.deleted) {
                        Text(stringResource(if (m.removedByAdmin) R.string.chat_message_removed else R.string.chat_message_deleted), style = Kin.type.body, color = c.inkFaint, fontStyle = FontStyle.Italic)
                        return@Column
                    }
                    m.replyTo?.let { q -> Quote(q) }
                    m.sharedPost?.let { p -> SharedPost(p) { if (p.available) a.openPost(p.postId) } }
                    if (m.items.isNotEmpty()) MediaGrid(m.items, a.openMedia)
                    m.voice?.let { v -> VoiceMessage(v, { a.voiceUrl(v.itemId) }) { a.showError(R.string.chat_voice_failed) } }
                    m.body?.takeIf { it.isNotBlank() }?.let { MentionBody(it, m.mentions, color = if (mine) c.onAccentSoft else c.ink) }
                    m.linkPreview?.takeIf { it.status == LinkPreviewStatus.OK }?.let { lp ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperDeep).clickable(role = Role.Button) { a.openUrl(lp.url) }.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            lp.imageUrl?.let { Thumb(it, null, Modifier.fillMaxWidth().aspectRatio(1.9f), corner = 8.dp) }
                            lp.siteName?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                            lp.title?.let { Text(it, style = Kin.type.bodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            lp.description?.let { Text(it, style = Kin.type.caption, color = c.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (m.frameActivity.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.frameActivity.forEach { f ->
                            f.reaction?.let { Text(Delivery.EMOJI[it].orEmpty() + " " + stringResource(R.string.social_frame_label, f.frameName), style = Kin.type.caption, color = c.inkSoft) }
                            f.replies.forEach { r -> Text("💬 " + stringResource(Delivery.replyLabel(r)) + " · " + f.frameName, style = Kin.type.caption, color = c.inkSoft) }
                        }
                    }
                }
                ReactionPicker(picking, m.reactions.mine, { picking = false }) { r -> a.vm.react(m, r) }
                ActionMenu(menu, buildList {
                    add(MenuAction(R.string.social_react) { picking = true })
                    if (!conv.readOnly) add(MenuAction(R.string.social_reply) { a.vm.reply(m) })
                    if (m.items.isNotEmpty()) add(MenuAction(R.string.chat_put_on_frame) { onPut(m) })
                    m.body?.takeIf { it.isNotBlank() }?.let { b -> add(MenuAction(R.string.chat_copy) { a.copy(b) }) }
                    if (MessageRules.canEdit(m, a.me, a.editHours)) add(MenuAction(R.string.common_edit) { a.vm.edit(m) })
                    if (MessageRules.canDelete(m, a.me, moderator)) add(MenuAction(if (mine) R.string.common_remove else R.string.chat_remove_message, danger = true) { a.vm.delete(m) })
                    if (a.safety && !mine) {
                        add(MenuAction(R.string.chat_report) { onReport(m) })
                        m.author?.userId?.let { u -> add(MenuAction(R.string.chat_block, danger = true) { a.vm.block(u) }) }
                    }
                }) { menu = false }
            }
            if (m.reactions.total > 0) ReactionRow(m.reactions, a.me, { r -> a.vm.react(m, r) }, { onWho(m.id) }, compact = true)
            val edited = stringResource(R.string.social_edited)
            val seenText = if (seen.isEmpty()) null else if (conv.isDirect) stringResource(R.string.chat_seen)
                else stringResource(R.string.chat_seen_by, seen.map { personName(it) }.joinToString(", "))
            val meta = listOfNotNull(Format.time(m.createdAt), edited.takeIf { m.editedAt != null && !m.deleted }, seenText).joinToString(" · ")
            Text(meta, style = Kin.type.caption, color = c.inkFaint)
        }
    }
}

@Composable
private fun Quote(q: app.kinframe.core.social.MessageQuote) {
    val c = Kin.colors
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.paperDeep).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        q.thumbUrl?.let { Thumb(it, null, Modifier.size(36.dp), corner = 6.dp) }
        Column {
            Text(personName(q.author), style = Kin.type.label, color = c.accent)
            Text(
                when {
                    q.deleted -> stringResource(R.string.chat_quote_unavailable)
                    q.voice -> stringResource(R.string.chat_voice_message)
                    !q.excerpt.isNullOrBlank() -> q.excerpt!!
                    q.mediaKind == MediaKind.VIDEO -> stringResource(R.string.chat_video)
                    q.mediaKind != null -> stringResource(R.string.chat_photo)
                    else -> ""
                },
                style = Kin.type.caption, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SharedPost(p: PostCard, onOpen: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperDeep).clickable(role = Role.Button, enabled = p.available, onClick = onOpen).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!p.available) { Text(stringResource(R.string.chat_post_unavailable), style = Kin.type.caption, color = c.inkFaint, fontStyle = FontStyle.Italic); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { p.thumbUrls.take(4).forEach { Thumb(it, null, Modifier.size(56.dp), corner = 8.dp) } }
        Text(listOfNotNull(personName(p.poster).takeIf { it.isNotEmpty() }, p.feedName ?: p.frameName?.let { stringResource(R.string.social_to_frame, it) }).joinToString(" · "),
            style = Kin.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.caption?.let { Text(it, style = Kin.type.caption, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun MediaGrid(items: List<Item>, onOpen: (Item) -> Unit) {
    val shown = items.take(4)
    val cols = if (shown.size == 1) 1 else 2
    Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(cols).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chunk.forEachIndexed { i, item ->
                    Box(Modifier.weight(1f).aspectRatio(if (cols == 1) (item.width?.toFloat()?.div(item.height ?: 1)?.coerceIn(0.7f, 1.6f) ?: 1f) else 1f)
                        .clip(RoundedCornerShape(10.dp)).clickable(role = Role.Image) { onOpen(item) }) {
                        Thumb(item.thumbUrl, item.caption, Modifier.fillMaxSize(), corner = 10.dp)
                        if (item.status == "processing") CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        if (item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, null, Modifier.align(Alignment.Center).size(28.dp), tint = Color.White)
                        val index = row * cols + i
                        if (index == 3 && items.size > 4) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Text("+${items.size - 3}", style = Kin.type.title, color = Color.White)
                        }
                    }
                }
                if (chunk.size < cols) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Composer(s: ConversationState, conv: Conversation, a: ConversationActions) {
    val c = Kin.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (conv.readOnly) {
        Note(stringResource(R.string.chat_read_only), Modifier.padding(12.dp))
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ConversationViewModel.MAX_ITEMS)) { uris -> a.vm.sendMedia(uris) }
    val recorder = a.voice
    val rec by (recorder?.state ?: kotlinx.coroutines.flow.MutableStateFlow(VoiceRecorder.State.Idle)).collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val recording = rec as? VoiceRecorder.State.Recording
    LaunchedEffect(recording != null) { while (recording != null) { now = System.currentTimeMillis(); a.vm.recording(); delay(250) } }
    fun finish() {
        recorder?.finish()?.let { (f, ms) -> a.vm.sendVoice(f, ms) } ?: a.showError(R.string.chat_voice_too_short)
    }
    val startRecording = { recorder?.start(scope) { finish() }?.takeIf { !it }?.let { a.showError(R.string.chat_voice_cannot_record) }; Unit }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() else a.showError(R.string.chat_voice_permission) }
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val banner = when {
            s.editing != null -> stringResource(R.string.chat_editing)
            s.replyTo != null -> stringResource(R.string.chat_replying_to, personName(s.replyTo.author))
            else -> null
        }
        banner?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(a.vm::cancel) { Icon(KinIcons.Close, stringResource(R.string.common_cancel), Modifier.size(18.dp), tint = c.inkSoft) }
            }
        }
        s.preview?.takeIf { it.status != LinkPreviewStatus.FAILED }?.let { p ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperDeep).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.imageUrl?.let { Thumb(it, null, Modifier.size(40.dp), corner = 6.dp) }
                Text(p.title ?: p.url, style = Kin.type.caption, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(a.vm::dismissPreview) { Icon(KinIcons.Close, stringResource(R.string.chat_no_preview), Modifier.size(18.dp), tint = c.inkSoft) }
            }
        }
        s.error?.let { Text(stringResource(it), style = Kin.type.caption, color = c.danger) }
        if (recording != null) {
            RecordingBar(recording.levels, now - recording.startedAt, onCancel = { recorder?.cancel() }, onSend = { finish() })
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.editing == null) IconButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                Icon(KinIcons.Image, stringResource(R.string.chat_attach), tint = c.inkSoft)
            }
            MentionField(s.draft, { a.vm.setDraft(it); a.vm.onDraft(it.text) }, s.people.filter { it.userId != a.me }, stringResource(R.string.chat_placeholder), Modifier.weight(1f))
            if (s.draft.text.isBlank() && recorder != null && s.editing == null) {
                IconButton({
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(SocialIcons.Mic, stringResource(R.string.chat_voice_record), tint = c.accent) }
            } else IconButton(a.vm::send, enabled = s.draft.text.isNotBlank()) {
                Icon(if (s.editing != null) KinIcons.Check else KinIcons.Send, stringResource(R.string.chat_send), tint = if (s.draft.text.isNotBlank()) c.accent else c.inkFaint)
            }
        }
    }
}

/** "Put on a frame": frames, and (own photos only) families with an optional circle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PutOnFrameDialog(
    targets: PutOnFrameTargets?, mine: Boolean, onDismiss: () -> Unit,
    onPut: (frames: List<String>, feeds: List<String>, circles: List<String>, names: List<String>) -> Unit,
) {
    val c = Kin.colors
    var frames by remember { mutableStateOf(setOf<String>()) }
    var feeds by remember { mutableStateOf(setOf<String>()) }
    var circles by remember { mutableStateOf(mapOf<String, String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_put_on_frame)) },
        text = {
            if (targets == null) Text(stringResource(R.string.social_loading))
            else LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (targets.frames.isEmpty() && (!mine || targets.feeds.isEmpty())) item { Text(stringResource(R.string.chat_put_nothing)) }
                if (targets.frames.isNotEmpty()) item { Text(stringResource(R.string.nav_frames), style = Kin.type.label, color = c.inkSoft) }
                items(targets.frames, key = { "fr" + it.id }) { f ->
                    CheckLine(f.name, f.id in frames) { frames = if (f.id in frames) frames - f.id else frames + f.id }
                }
                if (mine && targets.feeds.isNotEmpty()) item { Text(stringResource(R.string.nav_feeds), style = Kin.type.label, color = c.inkSoft) }
                if (mine) items(targets.feeds, key = { "fd" + it.id }) { f ->
                    Column {
                        CheckLine(f.name, f.id in feeds) { feeds = if (f.id in feeds) feeds - f.id else feeds + f.id; if (f.id !in feeds) circles = circles - f.id }
                        val cs = targets.circles[f.id].orEmpty()
                        if (f.id in feeds && cs.isNotEmpty()) FlowRow(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip(stringResource(R.string.social_audience_whole), circles[f.id] == null, { circles = circles - f.id })
                            cs.forEach { ci -> ToggleChip(ci.name, circles[f.id] == ci.id, { circles = circles + (f.id to ci.id) }) }
                        }
                    }
                }
                if (!mine) item { Text(stringResource(R.string.chat_put_others_note), style = Kin.type.caption, color = c.inkFaint) }
            }
        },
        confirmButton = {
            TextButton({
                val names = (targets?.frames.orEmpty().filter { it.id in frames }.map { it.name } + targets?.feeds.orEmpty().filter { it.id in feeds }.map { it.name })
                onPut(frames.toList(), feeds.toList(), circles.values.toList(), names)
            }, enabled = frames.isNotEmpty() || feeds.isNotEmpty()) { Text(stringResource(R.string.chat_put)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CheckLine(text: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = Kin.colors.accent))
        Text(text, style = Kin.type.body, color = Kin.colors.ink)
    }
}
