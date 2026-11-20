package app.kinframe.family.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Feed
import app.kinframe.core.api.Frame
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Milestone
import app.kinframe.core.social.OnThisDayGroup
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.R
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.engagement.milestoneTitle
import app.kinframe.family.ui.social.HomeEntry
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.PostMedia
import app.kinframe.family.ui.social.ReactionRow
import app.kinframe.family.ui.social.UnreadPill
import app.kinframe.family.ui.social.freshnessLabel
import app.kinframe.family.ui.social.personName
import app.kinframe.core.social.PersonReaction
import app.kinframe.family.util.Format
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** What Home can do; wired by the root. */
class HomeActions(
    val vm: HomeViewModel,
    val me: String?,
    val openPost: (TimelinePost) -> Unit,
    val openPerson: (String) -> Unit,
    val openFrame: (Frame) -> Unit,
    val openFeed: (Feed) -> Unit,
    val who: (TimelinePost) -> Unit,
    val share: ((TimelinePost) -> Unit)?,
    val shareAgain: (OnThisDayGroup) -> Unit,
    val openSummary: (HomeSummary) -> Unit,
    val openMilestones: () -> Unit,
    val openReminders: () -> Unit,
    val post: () -> Unit,
    /** "Don't show this memory again" (README §5.21); null without discovery `leaving`. */
    val hideMemory: ((OnThisDayGroup) -> Unit)? = null,
    /** The server chip of a post when signed in to several servers (multi-server.md §2.2), else null. */
    val serverChip: (TimelinePost) -> String? = { null },
    /** The person's user id on a post's server (Home merges every account's timeline). */
    val meFor: (TimelinePost) -> String? = { me },
    /** Whether a memory's server lets it be hidden (discovery `leaving` there). */
    val canHideMemory: (OnThisDayGroup) -> Boolean = { true },
    /**
     * The strip's server label for a frame or family whose name is also used on another server (multi-server.md
     * §2.2: grouped by server only when the same name appears twice), else null.
     */
    val stripServer: (String) -> String? = { null },
    /** The server chip of a weekly summary card when there are several accounts, else null. */
    val summaryServer: (HomeSummary) -> String? = { null },
    // ---- the person's families (docs/spec/simple-app.md §3.1)
    /** Every family, the home family first: with several, filter chips and a family label on each post. */
    val families: List<Feed> = emptyList(),
    /** The filter chip: one family's id, or null for All. */
    val filter: String? = null,
    val onFilter: (String?) -> Unit = {},
    /** The activity button: new comments, mentions and photo requests. */
    val onActivity: () -> Unit = {},
    val activityBadge: Int = 0,
    /** The "Invite" bubble and "Invite family": the family invite share sheet; null when you may not invite. */
    val invite: (() -> Unit)? = null,
)

@Composable
fun HomeScreen(s: HomeState, a: HomeActions) {
    val c = Kin.colors
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) a.vm.loadMore() }
    Column(Modifier.fillMaxSize().background(c.surface)) {
        HomeTopBar(a)
        if (a.families.size > 1) FilterChips(a)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                item("row") { FamilyRow(s, a) }
                // Cards around the posts keep the page's side margins; the posts go edge to edge.
                s.reminders?.takeIf { it.autoReduced }?.let {
                    item("reduced") {
                        Card {
                            Text(stringResource(R.string.engagement_auto_reduced), style = Kin.type.body, color = c.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(stringResource(R.string.common_done), a.vm::ackAutoReduced)
                                QuietButton(stringResource(R.string.engagement_reminder_settings), a.openReminders)
                            }
                        }
                    }
                }
                items(s.openRequests, key = { "req" + it.id }) { f ->
                    Card(onClick = { a.openFrame(f) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(KinIcons.HeartFilled, null, tint = c.sun)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.engagement_frame_would_like, f.name), style = Kin.type.bodyStrong, color = c.ink)
                                freshnessLabel(f.freshness)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                            }
                            Icon(KinIcons.ChevronRight, null, tint = c.inkFaint)
                        }
                    }
                }
                items(s.milestones, key = { "ms" + it.key }) { m -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { MilestoneCard(m, { a.vm.dismissMilestone(m.key) }, a.openMilestones) } }
                items(s.summaries, key = { "summary" + it.accountId + it.summary.weekOf }) { card ->
                    Card(onClick = { a.openSummary(card) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.engagement_summary_title), style = Kin.type.label, color = c.inkSoft, modifier = Modifier.weight(1f))
                            a.summaryServer(card)?.let { app.kinframe.family.ui.Pill(it, c.paperDeep, c.inkSoft, icon = KinIcons.Server) }
                        }
                        Text(summaryLine(card.summary), style = Kin.type.bodyStrong, color = c.ink)
                        QuietButton(stringResource(R.string.common_close), { a.vm.dismissSummary(card) })
                    }
                }
                items(s.prompts, key = { "prompt" + it.id }) { p ->
                    Card {
                        Text(stringResource(R.string.engagement_prompt_this_week, s.feeds.firstOrNull { it.id == p.feedId }?.name.orEmpty()), style = Kin.type.label, color = c.inkSoft)
                        Text(p.title, style = Kin.type.headline, color = c.ink)
                        if (p.answerCount > 0) Text(pluralStringResource(R.plurals.engagement_prompt_answers, p.answerCount, p.answerCount), style = Kin.type.caption, color = c.inkSoft)
                        PrimaryButton(stringResource(R.string.engagement_prompt_answer), a.post, icon = KinIcons.Plus)
                    }
                }
                s.onThisDay?.groups?.takeIf { it.isNotEmpty() }?.let { groups ->
                    item("otd") { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { OnThisDayCard(groups, a.shareAgain, a.hideMemory, a.canHideMemory) } }
                }
                when (val p = s.posts) {
                    Load.Loading -> item("loading") { Box(Modifier.padding(16.dp)) { SkeletonList(2, 320.dp) } }
                    is Load.Error -> item("error") { ErrorPanel(p.message, { a.vm.refresh() }) }
                    is Load.Ready -> if (p.value.isEmpty()) item("empty") {
                        EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body), Modifier.padding(top = 24.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                PrimaryButton(stringResource(R.string.home_empty_title), a.post, icon = KinIcons.Plus)
                                if (a.invite != null && HomeRow.alone(s.members, a.me)) {
                                    Text(stringResource(R.string.home_only_you), style = Kin.type.caption, color = c.inkSoft)
                                    SecondaryButton(stringResource(R.string.home_invite_family), a.invite, icon = KinIcons.UserPlus)
                                }
                            }
                        }
                    } else items(s.entries, key = { e -> when (e) { is HomeEntry.Post -> e.post.id; is HomeEntry.PromptHeader -> "h" + e.promptId + e.count } }) { e ->
                        when (e) {
                            is HomeEntry.PromptHeader -> Text(
                                pluralStringResource(R.plurals.engagement_answers_to, e.count, e.count, e.title), style = Kin.type.title, color = c.ink,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            )
                            is HomeEntry.Post -> Column {
                                a.serverChip(e.post)?.let { chip ->
                                    app.kinframe.family.ui.Pill(chip, c.paperDeep, c.inkSoft, Modifier.padding(start = 16.dp, top = 8.dp), icon = KinIcons.Server)
                                }
                                val me = a.meFor(e.post)
                                FamilyPost(
                                    e.post, me, family = e.post.feedName.takeIf { a.families.size > 1 }, onOpen = { a.openPost(e.post) }, onReact = { r -> a.vm.react(e.post, r) }, onWho = { a.who(e.post) },
                                    onPoster = { a.openPerson(e.post.poster.userId) }.takeIf { !e.post.poster.formerMember && e.post.poster.userId != me },
                                    onShare = a.share?.let { f -> { f(e.post) } },
                                )
                            }
                        }
                    }
                }
                if (s.loadingMore) item("more") { Box(Modifier.padding(16.dp)) { SkeletonList(1, 200.dp) } }
            }
        }
    }
}

/** "Home" (with one family its name) and the activity button. */
@Composable
private fun HomeTopBar(a: HomeActions) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a.families.singleOrNull()?.name ?: stringResource(R.string.social_nav_home), style = Kin.type.headline, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(a.onActivity) { app.kinframe.family.ui.BadgedIcon(KinIcons.Heart, a.activityBadge, stringResource(R.string.home_activity)) }
    }
}

/** All · each family (home family first): which families Home shows. */
@Composable
private fun FilterChips(a: HomeActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item("all") { app.kinframe.family.ui.ToggleChip(stringResource(R.string.home_all), a.filter == null, { a.onFilter(null) }) }
        items(a.families, key = { "chip" + it.id }) { f -> app.kinframe.family.ui.ToggleChip(f.name, a.filter == f.id, { a.onFilter(f.id) }) }
    }
}

/** People and frames of the family, then "Invite". */
@Composable
private fun FamilyRow(s: HomeState, a: HomeActions) {
    val c = Kin.colors
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(s.members, key = { "p" + it.userId }) { m ->
                RowBubble(m.displayName, ring = null, onClick = { a.openPerson(m.userId) }.takeIf { m.userId != a.me }) {
                    app.kinframe.family.ui.Avatar(m.displayName, m.avatarUrl, 56.dp)
                }
            }
            items(s.familyFrames, key = { "f" + it.id }) { f ->
                val known = s.frames.firstOrNull { it.id == f.id }
                RowBubble(f.name, ring = if (f.online) c.sage else c.line, onClick = known?.let { k -> { a.openFrame(k) } },
                    label = if (f.online) stringResource(R.string.home_frame_online, f.name) else f.name) {
                    val thumb = s.frameThumbs[f.id]
                    if (thumb != null) Thumb(thumb, null, Modifier.fillMaxSize(), corner = 28.dp)
                    else Box(Modifier.fillMaxSize().background(c.paperDeep), contentAlignment = Alignment.Center) { Icon(KinIcons.Frame, null, Modifier.size(26.dp), tint = c.inkSoft) }
                }
            }
            a.invite?.let { invite ->
                item("invite") {
                    RowBubble(stringResource(R.string.home_invite), ring = null, dashed = true, onClick = invite) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(KinIcons.Plus, null, Modifier.size(24.dp), tint = c.inkSoft) }
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = c.line)
    }
}

@Composable
private fun RowBubble(name: String, ring: androidx.compose.ui.graphics.Color?, onClick: (() -> Unit)?, dashed: Boolean = false, label: String = name, content: @Composable () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).let { m -> if (onClick != null) m.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else m }.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(62.dp).clip(CircleShape)
                .let { m -> when { dashed -> m.border(2.dp, c.inkFaint, CircleShape); ring != null -> m.border(2.dp, ring, CircleShape); else -> m } }
                .padding(if (ring != null || dashed) 3.dp else 0.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(name, style = Kin.type.caption, color = if (dashed) c.inkSoft else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A card between the posts, with the page's side margins. */
@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Surface(Modifier.fillMaxWidth(), onClick = onClick, content = content) }
}

/**
 * A post on Home: poster and time, the photos edge to edge, heart and comment, "On 3 frames", who loved it, the
 * caption and "View comments".
 */
@Composable
fun FamilyPost(p: TimelinePost, me: String?, family: String? = null, onOpen: () -> Unit, onReact: (String?) -> Unit, onWho: () -> Unit, onPoster: (() -> Unit)?, onShare: (() -> Unit)?) {
    val c = Kin.colors
    val t = p.thread
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(CircleShape).let { m -> if (onPoster != null) m.clickable(role = Role.Button, onClick = onPoster) else m }) { PersonAvatar(p.poster, 36.dp) }
            Column(Modifier.weight(1f)) {
                val via = p.via?.let { " · " + stringResource(R.string.social_via, it.label) }.orEmpty()
                Text(personName(p.poster) + via, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The family it was posted in (with several families), its circle, or the frame of a frame post.
                val where = listOfNotNull(family?.takeIf { !p.isFramePost }, p.circleName, p.frameName?.takeIf { p.isFramePost })
                Text((listOf(Format.relative(p.createdAt)) + where).joinToString(" · "), style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.unreadMentions > 0) app.kinframe.family.ui.Pill(stringResource(R.string.social_mentioned_you), c.accent, c.onAccent)
            else if (t.unreadCount > 0) UnreadPill(t.unreadCount)
            if (onShare != null) IconButton(onShare) { Icon(KinIcons.Share, stringResource(R.string.chat_share_in_chat), tint = c.inkSoft) }
        }
        PostMedia(p.items, onOpen = { onOpen() }, corner = 0.dp)
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val loved = t.reactions.mine == PersonReaction.LOVE
            IconButton({ onReact(if (loved) null else PersonReaction.LOVE) }) {
                Icon(if (loved) KinIcons.HeartFilled else KinIcons.Heart, stringResource(R.string.simple_send_heart), Modifier.size(26.dp), tint = if (loved) c.love else c.ink)
            }
            IconButton(onOpen) { Icon(KinIcons.Message, stringResource(R.string.social_add_comment), Modifier.size(24.dp), tint = c.ink) }
            Box(Modifier.weight(1f))
            p.seen?.takeIf { it.frames > 0 }?.let { s ->
                Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(KinIcons.Eye, null, Modifier.size(16.dp), tint = c.sage)
                    Text(pluralStringResource(R.plurals.home_on_frames, s.frames, s.frames), style = Kin.type.caption, color = c.onSageSoft)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (t.reactions.total > 0) ReactionRow(t.reactions, me, null, onWho)
            p.prompt?.let { app.kinframe.family.ui.Pill(stringResource(R.string.engagement_answers_prompt, it.title), c.sageSoft, c.onSageSoft, icon = KinIcons.Star) }
            p.caption?.let { Text(it, style = Kin.type.body, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            if (t.commentCount > 0) Text(
                pluralStringResource(R.plurals.social_view_all_comments, t.commentCount, t.commentCount), style = Kin.type.body, color = c.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun summaryLine(w: app.kinframe.core.social.WeeklySummary): String {
    val seen = pluralStringResource(R.plurals.engagement_summary_seen, w.seenCount, w.seenCount, w.frameCount)
    return if (w.loveCount > 0) seen + " " + pluralStringResource(R.plurals.engagement_summary_loved, w.loveCount, w.loveCount) else seen
}

@Composable
private fun OnThisDayCard(groups: List<OnThisDayGroup>, onShare: (OnThisDayGroup) -> Unit, onHide: ((OnThisDayGroup) -> Unit)? = null, canHide: (OnThisDayGroup) -> Boolean = { true }) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.engagement_on_this_day), style = Kin.type.title, color = c.ink)
        groups.forEach { g ->
            Text(pluralStringResource(R.plurals.engagement_years_ago_in, g.yearsAgo, g.yearsAgo, g.feedName), style = Kin.type.label, color = c.inkSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(g.items, key = { it.id }) { item -> Thumb(item.thumbUrl, item.caption, Modifier.size(96.dp).aspectRatio(1f)) }
            }
            SecondaryButton(stringResource(R.string.engagement_share_again), { onShare(g) }, icon = KinIcons.Send)
            if (onHide != null && canHide(g)) QuietButton(stringResource(R.string.leaving_memory_hide), { onHide(g) })
        }
    }
}

@Composable
private fun MilestoneCard(m: Milestone, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("✨", style = Kin.type.title)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.engagement_milestone_new), style = Kin.type.caption, color = c.inkSoft)
                Text(milestoneTitle(m.key), style = Kin.type.bodyStrong, color = c.ink)
            }
            IconButton(onDismiss) { Icon(KinIcons.Close, stringResource(R.string.common_close), tint = c.inkSoft) }
        }
    }
}

/** A short note when the server doesn't have family social yet. */
@Composable
fun SocialOffNote() = Note(stringResource(R.string.social_problem_not_ready), tone = NoteTone.Info)
