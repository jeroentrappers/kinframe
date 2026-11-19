package app.kinframe.family.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Comment
import app.kinframe.core.social.ReactionList
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.ReportTargetKind
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.history.Delivery
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.util.Format

/** What the post screen can do besides its view model; wired by the root. */
class PostActions(
    val vm: PostViewModel,
    val me: String?,
    val onBack: () -> Unit,
    val openPerson: (String) -> Unit,
    val shareInChat: ((TimelinePost) -> Unit)?,
    val whoReacted: (kind: String, id: String, postId: String?) -> suspend () -> ReactionList,
    /** Reports and blocks only with discovery `chats` (the safety tag). */
    val safety: Boolean,
    /** The post item's reaction summary (there is none in the post itself), for the photo on screen. */
    val itemReactions: suspend (itemId: String) -> ReactionSummary?,
)

@Composable
fun PostScreen(s: PostState, a: PostActions) {
    val c = Kin.colors
    val post = s.postOrNull
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var who by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var deleting by remember { mutableStateOf<Comment?>(null) }
    Column(Modifier.fillMaxSize().background(c.paper).imePadding()) {
        KinTopBar(post?.let { postLabel(it) } ?: stringResource(R.string.social_post_title), onBack = a.onBack) {
            if (post != null) {
                a.shareInChat?.let { share -> IconButton({ share(post) }) { Icon(KinIcons.Share, stringResource(R.string.chat_share_in_chat), tint = c.inkSoft) } }
                Box {
                    IconButton({ menu = true }) { Icon(KinIcons.More, stringResource(R.string.common_more), tint = c.inkSoft) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (post.thread.muted) R.string.social_thread_unmute else R.string.social_thread_mute)) },
                            onClick = { menu = false; a.vm.setMuted(!post.thread.muted) },
                        )
                        if (post.thread.canModerate || post.poster.userId == a.me) DropdownMenuItem(
                            text = { Text(stringResource(if (post.thread.closed) R.string.social_thread_open else R.string.social_thread_close)) },
                            onClick = { menu = false; a.vm.setClosed(!post.thread.closed) },
                        )
                        if (a.safety && post.poster.userId != a.me) DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_report_post), color = c.danger) },
                            onClick = { menu = false; reporting = ReportTargetKind.POST to post.id },
                        )
                    }
                }
            }
        }
        when (val p = s.post) {
            Load.Loading -> SkeletonList(2, 260.dp)
            is Load.Error -> ErrorPanel(p.message, { a.vm.load() })
            is Load.Ready -> {
                val list = rememberLazyListState()
                LaunchedEffect(s.comments.size) { if (s.comments.isNotEmpty() && list.firstVisibleItemIndex > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item("post") { PostHeader(p.value, a, onWho = { k, id, pid -> who = Triple(k, id, pid) }) }
                        if (s.groups.isEmpty()) item("none") {
                            Text(stringResource(R.string.social_no_comments), style = Kin.type.body, color = c.inkSoft, modifier = Modifier.padding(vertical = 12.dp))
                        }
                        items(s.groups, key = { it.top.id }) { g ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CommentItem(g.top, a, reply = false, onWho = { who = Triple(ReactionTarget.COMMENT, g.top.id, null) },
                                    onReport = { reporting = ReportTargetKind.COMMENT to g.top.id }, onDelete = { deleting = g.top })
                                g.replies.forEach { r ->
                                    Box(Modifier.padding(start = 44.dp)) {
                                        CommentItem(r, a, reply = true, onWho = { who = Triple(ReactionTarget.COMMENT, r.id, null) },
                                            onReport = { reporting = ReportTargetKind.COMMENT to r.id }, onDelete = { deleting = r })
                                    }
                                }
                            }
                        }
                        if (s.hasMore) item("more") { TextButton(a.vm::loadMore, enabled = !s.loadingMore) { Text(stringResource(R.string.common_load_more)) } }
                    }
                }
                Composer(s, p.value, a)
            }
        }
    }
    reporting?.let { (kind, id) -> ReportDialog({ reporting = null }) { reason, note -> a.vm.report(kind, id, reason, note) } }
    who?.let { (kind, id, pid) -> WhoReactedSheet(a.whoReacted(kind, id, pid)) { who = null } }
    deleting?.let { cm ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(if (cm.author?.userId == a.me) R.string.social_comment_delete_title else R.string.social_comment_remove_title)) },
            text = { Text(stringResource(if (cm.author?.userId == a.me) R.string.social_comment_delete_body else R.string.social_comment_remove_body)) },
            confirmButton = { TextButton({ deleting = null; a.vm.delete(cm) }) { Text(stringResource(R.string.common_remove), color = c.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PostHeader(p: TimelinePost, a: PostActions, onWho: (String, String, String?) -> Unit) {
    val c = Kin.colors
    var page by remember { mutableIntStateOf(0) }
    val itemSummaries = remember { mutableStateMapOf<String, ReactionSummary>() }
    val current = p.items.getOrNull(page)
    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        if (p.items.size > 1 && id !in itemSummaries) a.itemReactions(id)?.let { itemSummaries[id] = it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(CircleShape).clickable(role = Role.Button, enabled = p.poster.userId != a.me && !p.poster.formerMember) { a.openPerson(p.poster.userId) }) { PersonAvatar(p.poster, 40.dp) }
            Column(Modifier.weight(1f)) {
                Text(personName(p.poster), style = Kin.type.bodyStrong, color = c.ink)
                Text(Format.relative(p.createdAt), style = Kin.type.caption, color = c.inkSoft)
            }
        }
        PostMedia(p.items, onPage = { page = it })
        // React to the photo on screen of a multi-photo post (target `post_item`).
        if (current != null && p.items.size > 1) {
            val sum = itemSummaries[current.id] ?: ReactionSummary.EMPTY
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.social_this_photo), style = Kin.type.caption, color = c.inkSoft)
                ReactionRow(sum, a.me, onReact = { r ->
                    itemSummaries[current.id] = sum.withMine(r)
                    a.vm.react(ReactionTarget.POST_ITEM, current.id, r)
                }, onWho = { onWho(ReactionTarget.POST_ITEM, current.id, p.id) }, compact = true)
            }
        }
        p.caption?.let { Text(it, style = Kin.type.body, color = c.ink) }
        ReactionRow(p.thread.reactions, a.me, { r -> a.vm.react(ReactionTarget.POST, p.id, r) }, { onWho(ReactionTarget.POST, p.id, null) })
        p.seen?.takeIf { it.frames > 0 }?.let { s -> Text(pluralStringResource(R.plurals.social_seen_on_frames, s.frames, s.frames), style = Kin.type.caption, color = c.inkFaint) }
        if (p.thread.closed) Note(stringResource(R.string.social_comments_closed))
        Hairline()
    }
}

@Composable
private fun CommentItem(cm: Comment, a: PostActions, reply: Boolean, onWho: () -> Unit, onReport: () -> Unit, onDelete: () -> Unit) {
    val c = Kin.colors
    if (cm.isFrameEntry) return FrameEntry(cm)
    var menu by remember { mutableStateOf(false) }
    val mine = cm.author?.userId == a.me
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button, enabled = !mine && cm.author?.formerMember == false) { cm.author?.let { a.openPerson(it.userId) } }) {
            PersonAvatar(cm.author, if (reply) 28.dp else 34.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                        .longPressMenu({ if (!cm.deleted) menu = true }).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(personName(cm.author), style = Kin.type.label, color = c.ink)
                    when {
                        cm.removedByAdmin -> Text(stringResource(R.string.social_removed_by_admin), style = Kin.type.body, color = c.inkFaint, fontStyle = FontStyle.Italic)
                        cm.deleted -> Text(stringResource(R.string.social_comment_tombstone), style = Kin.type.body, color = c.inkFaint, fontStyle = FontStyle.Italic)
                        else -> {
                            val quoted = cm.quoted?.let { "@" + personName(it) + " " }.orEmpty()
                            if (quoted.isNotEmpty()) Text(quoted, style = Kin.type.caption, color = c.accent)
                            MentionBody(cm.body.orEmpty(), cm.mentions)
                        }
                    }
                }
                ActionMenu(menu, buildList {
                    add(MenuAction(R.string.social_reply) { a.vm.reply(cm) })
                    if (cm.canEdit || mine) add(MenuAction(R.string.common_edit) { a.vm.edit(cm) })
                    if (cm.canDelete || mine) add(MenuAction(if (mine) R.string.common_remove else R.string.social_comment_remove, danger = true) { onDelete() })
                    if (a.safety && !mine) {
                        add(MenuAction(R.string.chat_report) { onReport() })
                        cm.author?.userId?.let { u -> add(MenuAction(R.string.chat_block, danger = true) { a.vm.block(u) }) }
                    }
                }) { menu = false }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Format.relative(cm.createdAt) + if (cm.editedAt != null) " · " + stringResource(R.string.social_edited) else "", style = Kin.type.caption, color = c.inkFaint)
                if (!cm.deleted && !cm.removedByAdmin) {
                    Text(stringResource(R.string.social_reply), style = Kin.type.caption, color = c.inkSoft,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { a.vm.reply(cm) }.padding(4.dp))
                    ReactionRow(cm.reactions, a.me, { r -> a.vm.react(ReactionTarget.COMMENT, cm.id, r) }, onWho, compact = true)
                }
            }
        }
    }
}

/** "Oma's frame loved this", "Oma's frame: Call me!" — read-only. */
@Composable
private fun FrameEntry(cm: Comment) {
    val c = Kin.colors
    val f = cm.frame ?: return
    val text = when {
        f.reply != null -> stringResource(R.string.social_frame_replied, f.frameName, stringResource(Delivery.replyLabel(f.reply!!)))
        f.reaction != null -> stringResource(R.string.social_frame_reacted, f.frameName, Delivery.EMOJI[f.reaction!!].orEmpty())
        else -> f.frameName
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KinIcons.Frame, null, Modifier.size(18.dp), tint = c.onAccentSoft)
        Text(text, style = Kin.type.body, color = c.onAccentSoft, modifier = Modifier.weight(1f))
        Text(Format.relative(cm.createdAt), style = Kin.type.caption, color = c.onAccentSoft)
    }
}

@Composable
private fun Composer(s: PostState, p: TimelinePost, a: PostActions) {
    val c = Kin.colors
    val canWrite = p.thread.canComment && !p.thread.closed
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hairline()
        if (!canWrite && s.editing == null) {
            Text(stringResource(R.string.social_comments_closed), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.padding(8.dp))
            return@Column
        }
        val banner = when {
            s.editing != null -> stringResource(R.string.social_editing_comment)
            s.replyTo != null -> stringResource(R.string.social_replying_to, s.replyTo.name)
            else -> null
        }
        banner?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
                IconButton(a.vm::cancel) { Icon(KinIcons.Close, stringResource(R.string.common_cancel), Modifier.size(18.dp), tint = c.inkSoft) }
            }
        }
        s.error?.let { Text(stringResource(it), style = Kin.type.caption, color = c.danger) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MentionField(s.draft, a.vm::setDraft, s.people, stringResource(R.string.social_comment_placeholder), Modifier.weight(1f))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (s.sending) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
                else IconButton(a.vm::send, enabled = s.draft.text.isNotBlank()) {
                    Icon(KinIcons.Send, stringResource(R.string.social_send_comment), tint = if (s.draft.text.isNotBlank()) c.accent else c.inkFaint)
                }
            }
        }
    }
}
