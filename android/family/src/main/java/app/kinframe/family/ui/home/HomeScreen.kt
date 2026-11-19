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
import app.kinframe.family.ui.KinTopBar
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
import app.kinframe.family.ui.social.FreshnessRing
import app.kinframe.family.ui.social.HomeEntry
import app.kinframe.family.ui.social.TimelineCard
import app.kinframe.family.ui.social.freshnessLabel

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
    val openSummary: (String) -> Unit,
    val openMilestones: () -> Unit,
    val openReminders: () -> Unit,
    val post: () -> Unit,
    /** Shown in the top bar when the bottom bar has no room for them (History, Settings). */
    val topActions: @Composable () -> Unit = {},
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
)

@Composable
fun HomeScreen(s: HomeState, a: HomeActions) {
    val c = Kin.colors
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) a.vm.loadMore() }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.social_home_title)) {
            IconButton(a.post) { Icon(KinIcons.Send, stringResource(R.string.social_share_photos), tint = c.accent) }
            a.topActions()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s.frames.isNotEmpty() || s.feeds.isNotEmpty()) item("strip") { Strip(s, a) }
                s.reminders?.takeIf { it.autoReduced }?.let {
                    item("reduced") {
                        Surface(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.engagement_auto_reduced), style = Kin.type.body, color = c.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(stringResource(R.string.common_done), a.vm::ackAutoReduced)
                                QuietButton(stringResource(R.string.engagement_reminder_settings), a.openReminders)
                            }
                        }
                    }
                }
                items(s.openRequests, key = { "req" + it.id }) { f ->
                    Surface(Modifier.fillMaxWidth(), onClick = { a.openFrame(f) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("💛", style = Kin.type.title)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.engagement_frame_would_like, f.name), style = Kin.type.bodyStrong, color = c.ink)
                                freshnessLabel(f.freshness)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                            }
                            Icon(KinIcons.ChevronRight, null, tint = c.inkFaint)
                        }
                    }
                }
                items(s.milestones, key = { "ms" + it.key }) { m -> MilestoneCard(m, { a.vm.dismissMilestone(m.key) }, a.openMilestones) }
                s.summary?.let { w ->
                    item("summary") {
                        Surface(Modifier.fillMaxWidth(), onClick = { a.openSummary(w.weekOf) }) {
                            Text(stringResource(R.string.engagement_summary_title), style = Kin.type.label, color = c.inkSoft)
                            Text(summaryLine(w), style = Kin.type.bodyStrong, color = c.ink)
                            QuietButton(stringResource(R.string.common_close), a.vm::dismissSummary)
                        }
                    }
                }
                items(s.prompts, key = { "prompt" + it.id }) { p ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engagement_prompt_this_week, s.feeds.firstOrNull { it.id == p.feedId }?.name.orEmpty()), style = Kin.type.label, color = c.inkSoft)
                        Text(p.title, style = Kin.type.headline, color = c.ink)
                        if (p.answerCount > 0) Text(pluralStringResource(R.plurals.engagement_prompt_answers, p.answerCount, p.answerCount), style = Kin.type.caption, color = c.inkSoft)
                        PrimaryButton(stringResource(R.string.engagement_prompt_answer), a.post, icon = KinIcons.Send)
                    }
                }
                s.onThisDay?.groups?.takeIf { it.isNotEmpty() }?.let { groups ->
                    item("otd") { OnThisDayCard(groups, a.shareAgain, a.hideMemory, a.canHideMemory) }
                }
                when (val p = s.posts) {
                    Load.Loading -> item("loading") { SkeletonList(2, 320.dp) }
                    is Load.Error -> item("error") { ErrorPanel(p.message, { a.vm.refresh() }) }
                    is Load.Ready -> if (p.value.isEmpty()) item("empty") {
                        EmptyState(stringResource(R.string.social_home_empty_title), stringResource(R.string.social_home_empty_body)) {
                            PrimaryButton(stringResource(R.string.social_share_photos), a.post, icon = KinIcons.Send)
                        }
                    } else items(s.entries, key = { e -> when (e) { is HomeEntry.Post -> e.post.id; is HomeEntry.PromptHeader -> "h" + e.promptId + e.count } }) { e ->
                        when (e) {
                            is HomeEntry.PromptHeader -> Text(
                                pluralStringResource(R.plurals.engagement_answers_to, e.count, e.count, e.title), style = Kin.type.title, color = c.ink,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            is HomeEntry.Post -> Column {
                                a.serverChip(e.post)?.let { chip ->
                                    app.kinframe.family.ui.Pill(chip, c.paperDeep, c.inkSoft, Modifier.padding(bottom = 4.dp), icon = KinIcons.Server)
                                }
                                val me = a.meFor(e.post)
                                TimelineCard(
                                e.post, me, onOpen = { a.openPost(e.post) }, onReact = { r -> a.vm.react(e.post, r) }, onWho = { a.who(e.post) },
                                onPoster = { a.openPerson(e.post.poster.userId) }.takeIf { !e.post.poster.formerMember && e.post.poster.userId != me },
                                onShare = a.share?.let { f -> { f(e.post) } },
                            )
                            }
                        }
                    }
                }
                if (s.loadingMore) item("more") { SkeletonList(1, 200.dp) }
            }
        }
    }
}

@Composable
private fun summaryLine(w: app.kinframe.core.social.WeeklySummary): String {
    val seen = pluralStringResource(R.plurals.engagement_summary_seen, w.seenCount, w.seenCount, w.frameCount)
    return if (w.loveCount > 0) seen + " " + pluralStringResource(R.plurals.engagement_summary_loved, w.loveCount, w.loveCount) else seen
}

/** The frames and families strip with freshness rings ("Fresh", "4 days", "2 weeks quiet"). */
@Composable
private fun Strip(s: HomeState, a: HomeActions) {
    val c = Kin.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(s.frames, key = { "fr" + it.id }) { f ->
            StripItem(f.name, freshnessLabel(f.freshness), f.freshness?.level, badge = f.photoRequest?.isOpen == true, onClick = { a.openFrame(f) }, server = a.stripServer(f.id)) {
                Icon(KinIcons.Frame, null, Modifier.size(26.dp), tint = c.inkSoft)
            }
        }
        items(s.feeds, key = { "fd" + it.id }) { f ->
            val streak = f.streak?.takeIf { it.currentWeeks > 0 }?.let { stringResource(R.string.engagement_streak_short, it.currentWeeks) }
            StripItem(f.name, streak ?: freshnessLabel(f.freshness), f.freshness?.level, badge = false, onClick = { a.openFeed(f) }, server = a.stripServer(f.id)) {
                if (f.coverUrl != null) Thumb(f.coverUrl, null, Modifier.fillMaxSize(), corner = 40.dp) else Icon(KinIcons.People, null, Modifier.size(26.dp), tint = c.inkSoft)
            }
        }
    }
}

@Composable
private fun StripItem(name: String, label: String?, level: Double?, badge: Boolean, onClick: () -> Unit, server: String? = null, content: @Composable () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            FreshnessRing(level) { Box(Modifier.fillMaxSize().background(c.paperDeep), contentAlignment = Alignment.Center) { content() } }
            if (badge) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text("!", style = Kin.type.caption, color = c.onAccent)
            }
        }
        Text(name, style = Kin.type.caption, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        // The same name on two servers: which one this is.
        server?.let { Text(it, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
        label?.let { Text(it, style = Kin.type.caption, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
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
