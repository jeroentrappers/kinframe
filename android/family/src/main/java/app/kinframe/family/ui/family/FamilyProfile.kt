package app.kinframe.family.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.MediaKind
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FeedTab
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.ui.feeds.FeedActions
import app.kinframe.family.ui.feeds.FeedDetailState
import app.kinframe.family.ui.feeds.FramesTab
import app.kinframe.family.ui.feeds.PeopleTab

/** What the family profile does besides the family's own actions ([FeedActions]). */
class ProfileActions(
    val onSettings: () -> Unit,
    val onInvite: () -> Unit,
    /** "Add a frame": pick one of your frames or pair a new one; null when you can't. */
    val onAddFrame: (() -> Unit)?,
    val openPost: (FeedPost) -> Unit,
    /** A family that isn't the current one, opened from elsewhere. */
    val onBack: (() -> Unit)? = null,
)

/** The segments of the profile. */
val ProfileSegments = listOf(FeedTab.Posts, FeedTab.People, FeedTab.Frames)

/** Posts of [me] only ("Only mine", which replaces the old History tab). */
fun onlyMine(posts: List<FeedPost>, me: String?, on: Boolean): List<FeedPost> = if (on) posts.filter { it.poster.userId == me } else posts

/** "Oma, Anna, Ben, Emma, Lucas and 2 more". */
@Composable
fun memberNames(names: List<String>): String {
    val (shown, more) = Families.names(names)
    val list = shown.joinToString(", ")
    return if (more > 0) stringResource(R.string.family_names_more, list, more) else list
}

/**
 * The family as a profile (docs/spec/simple-app.md §3.4): cover, counts, name and members, "Invite family" and "Add a
 * frame", then Photos (with "Only mine"), People and Frames.
 */
@Composable
fun FamilyProfileScreen(state: FeedDetailState, segment: FeedTab, onSegment: (FeedTab) -> Unit, a: FeedActions, p: ProfileActions) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = if (p.onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            p.onBack?.let { IconButton(it) { Icon(KinIcons.ChevronLeft, stringResource(R.string.common_back), tint = c.ink) } }
            Text(stringResource(R.string.nav_family), style = Kin.type.title, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(p.onSettings) { Icon(KinIcons.Settings, stringResource(R.string.nav_settings), tint = c.ink) }
        }
        when (val f = state.feed) {
            Load.Loading -> SkeletonList(3, 120.dp)
            is Load.Error -> ErrorPanel(f.message, { a.vm.load() })
            is Load.Ready -> Profile(f.value, state, segment, onSegment, a, p)
        }
    }
}

@Composable
private fun Profile(feed: Feed, state: FeedDetailState, segment: FeedTab, onSegment: (FeedTab) -> Unit, a: FeedActions, p: ProfileActions) {
    val c = Kin.colors
    val current = if (segment in ProfileSegments) segment else FeedTab.Posts
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(86.dp).clip(CircleShape).background(c.paperDeep), contentAlignment = Alignment.Center) {
                if (feed.coverUrl != null) Thumb(feed.coverUrl, null, Modifier.fillMaxSize(), corner = 43.dp)
                else Icon(KinIcons.People, null, Modifier.size(36.dp), tint = c.inkSoft)
            }
            Row(Modifier.weight(1f)) {
                val posts = feed.postCount ?: (state.posts as? Load.Ready)?.value?.size ?: 0
                val people = state.members.size.takeIf { it > 0 } ?: feed.memberCount
                val frames = state.liveSubscriptions.size.takeIf { state.subscriptions.isNotEmpty() } ?: feed.subscriptionCount
                Count(posts, pluralStringResource(R.plurals.family_posts, posts), Modifier.weight(1f)) { onSegment(FeedTab.Posts) }
                Count(people, pluralStringResource(R.plurals.family_people, people), Modifier.weight(1f)) { onSegment(FeedTab.People) }
                Count(frames, pluralStringResource(R.plurals.family_frames, frames), Modifier.weight(1f)) { onSegment(FeedTab.Frames) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(feed.name, style = Kin.type.headline, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val names = state.members.map { it.displayName }
            if (names.isNotEmpty()) Text(memberNames(names), style = Kin.type.body, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (feed.canInvite) ProfileButton(stringResource(R.string.home_invite_family), true, p.onInvite, Modifier.weight(1f))
            p.onAddFrame?.let { ProfileButton(stringResource(R.string.family_add_frame), false, it, Modifier.weight(1f)) }
        }
        Segments(current, onSegment, state)
        Box(Modifier.weight(1f).background(c.paper)) {
            when (current) {
                FeedTab.People -> PeopleTab(feed, state, a)
                FeedTab.Frames -> FramesTab(feed, state, a)
                else -> PhotosGrid(state, a, p)
            }
        }
    }
}

@Composable
private fun Count(n: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(n.toString(), style = Kin.type.title, color = Kin.colors.ink)
        Text(label, style = Kin.type.caption, color = Kin.colors.inkSoft, maxLines = 1)
    }
}

@Composable
private fun ProfileButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Kin.colors
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (primary) c.accent else c.paperDeep).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Kin.type.label, color = if (primary) c.onAccent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun Segments(current: FeedTab, onSegment: (FeedTab) -> Unit, state: FeedDetailState) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth()) {
        ProfileSegments.forEach { t ->
            val label = stringResource(when (t) { FeedTab.People -> R.string.family_seg_people; FeedTab.Frames -> R.string.family_seg_frames; else -> R.string.family_seg_photos })
            val badge = when (t) { FeedTab.People -> state.requests.size; FeedTab.Frames -> state.pendingSubscriptions.size; else -> 0 }
            val on = t == current
            Column(
                Modifier.weight(1f).clickable(role = Role.Tab) { onSegment(t) }.semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (badge > 0) "$label ($badge)" else label, style = if (on) Kin.type.bodyStrong else Kin.type.body, color = if (on) c.ink else c.inkSoft,
                    modifier = Modifier.padding(vertical = 12.dp), maxLines = 1)
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) c.ink else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color = c.line)
}

@Composable
private fun PhotosGrid(state: FeedDetailState, a: FeedActions, p: ProfileActions) {
    val c = Kin.colors
    var mine by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) a.vm.loadMore() }
    when (val posts = state.posts) {
        Load.Loading -> SkeletonList(2, 120.dp)
        is Load.Error -> ErrorPanel(posts.message, { a.vm.load() })
        is Load.Ready -> {
            val shown = onlyMine(posts.value, a.me, mine).filter { it.items.isNotEmpty() }
            LazyVerticalGrid(
                GridCells.Adaptive(minSize = 110.dp), Modifier.fillMaxSize(), grid,
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        ToggleChip(stringResource(R.string.family_only_mine), mine, { mine = !mine })
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Note(stringResource(if (mine) R.string.family_photos_mine_empty else R.string.family_photos_empty), Modifier.padding(16.dp))
                }
                items(shown, key = { it.id }) { post ->
                    val item = post.items.first()
                    Box(Modifier.aspectRatio(1f).clickable(role = Role.Image) { p.openPost(post) }) {
                        Thumb(item.thumbUrl, item.caption, Modifier.fillMaxSize(), corner = 0.dp)
                        if (item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, stringResource(R.string.social_video), Modifier.align(Alignment.Center).size(28.dp), tint = Color.White)
                        if (post.items.size > 1) Icon(KinIcons.Album, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** A settings row: icon, title, optional hint, chevron. */
@Composable
fun SettingsLink(title: String, icon: ImageVector?, onClick: () -> Unit, hint: String? = null, badge: Int = 0) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(22.dp), tint = c.inkSoft) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Kin.type.bodyStrong, color = c.ink)
            hint?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
        }
        if (badge > 0) app.kinframe.family.ui.Pill(badge.toString(), c.accent, c.onAccent)
        Icon(KinIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.inkFaint)
    }
}

/** The bordered list of [SettingsLink]s used by the frame screen and Settings. */
@Composable
fun LinkList(content: @Composable () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        content()
    }
}

/** A short centred line ("That's all for now"). */
@Composable
fun QuietLine(text: String, modifier: Modifier = Modifier) =
    Text(text, style = Kin.type.caption, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
