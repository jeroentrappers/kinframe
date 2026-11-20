package app.kinframe.family.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Item
import app.kinframe.core.api.MediaKind
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.PageDots
import app.kinframe.core.social.Comment
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.ui.LiveBadge
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.util.Format

/** The label above a post: the family (and circle), or "To Oma's frame". */
@Composable
fun postLabel(p: TimelinePost): String = when {
    p.isFramePost -> stringResource(R.string.social_to_frame, p.frameName.orEmpty())
    p.circleName != null -> stringResource(R.string.social_family_circle, p.feedName.orEmpty(), p.circleName!!)
    else -> p.feedName.orEmpty()
}

/** Photos large, swipe through a multi-photo post (Home and the post screen). */
@Composable
fun PostMedia(items: List<Item>, modifier: Modifier = Modifier, onPage: (Int) -> Unit = {}, onOpen: ((Int) -> Unit)? = null, corner: androidx.compose.ui.unit.Dp = 14.dp, overlay: @Composable (Int) -> Unit = {}) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(aspectOf(items.first()))) { i ->
            val item = items[i]
            Box(Modifier.fillMaxSize().let { m -> if (onOpen != null) m.clickable(role = Role.Image) { onOpen(i) } else m }) {
                // Uncropped display rendition, cropped to the pager's aspect (Thumb crops by default).
                Thumb(item.largeImageUrl, item.caption, Modifier.fillMaxSize(), corner = corner)
                if (item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, stringResource(R.string.social_video), Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
                if (item.isLive) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                overlay(i)
            }
        }
        if (items.size > 1) PageDots(items.size, pager.currentPage)
    }
}

private fun aspectOf(item: Item): Float {
    val w = item.width ?: return 1f
    val h = item.height?.takeIf { it > 0 } ?: return 1f
    return (w.toFloat() / h).coerceIn(0.8f, 1.6f)
}

/** One comment as a line under a post: "Anna Look at her face 😂". */
@Composable
fun CommentLine(c: Comment) {
    val name = personName(c.author)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = Kin.type.bodyStrong, color = Kin.colors.ink, maxLines = 1)
        MentionBody(c.body.orEmpty(), c.mentions, Modifier.weight(1f), maxLines = 2)
    }
}

/** A timeline post: poster and family, photos, caption, the reaction row, "Seen on 4 frames", the newest comments. */
@Composable
fun TimelineCard(
    p: TimelinePost,
    me: String?,
    onOpen: () -> Unit,
    onReact: (String?) -> Unit,
    onWho: () -> Unit,
    onPoster: (() -> Unit)?,
    onShare: (() -> Unit)?,
) {
    val c = Kin.colors
    val t = p.thread
    Surface(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(CircleShape).let { m -> if (onPoster != null) m.clickable(role = Role.Button, onClick = onPoster) else m }) { PersonAvatar(p.poster, 40.dp) }
            Column(Modifier.weight(1f)) {
                val via = p.via?.let { " · " + stringResource(R.string.social_via, it.label) }.orEmpty()
                Text(personName(p.poster) + via, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(postLabel(p) + " · " + Format.relative(p.createdAt), style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.unreadMentions > 0) Pill(stringResource(R.string.social_mentioned_you), c.accent, c.onAccent)
            else if (t.unreadCount > 0) UnreadPill(t.unreadCount)
            if (onShare != null) IconButton(onShare) { Icon(KinIcons.Share, stringResource(R.string.chat_share_in_chat), tint = c.inkSoft) }
        }
        PostMedia(p.items, onOpen = { onOpen() })
        p.prompt?.let { Pill(stringResource(R.string.engagement_answers_prompt, it.title), c.sageSoft, c.onSageSoft, icon = KinIcons.Star) }
        p.caption?.let { Text(it, style = Kin.type.body, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        ReactionRow(t.reactions, me, onReact, onWho)
        p.seen?.takeIf { it.frames > 0 }?.let { s -> Text(pluralStringResource(R.plurals.social_seen_on_frames, s.frames, s.frames), style = Kin.type.caption, color = c.inkFaint) }
        if (t.commentCount > t.latestComments.size) {
            Text(
                pluralStringResource(R.plurals.social_view_all_comments, t.commentCount, t.commentCount), style = Kin.type.label, color = c.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
            )
        }
        t.latestComments.forEach { CommentLine(it) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperDeep).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KinIcons.Message, null, Modifier.size(18.dp), tint = c.inkSoft)
            Text(stringResource(if (t.canComment && !t.closed) R.string.social_add_comment else R.string.social_comments_closed), style = Kin.type.caption, color = c.inkSoft)
        }
    }
}
