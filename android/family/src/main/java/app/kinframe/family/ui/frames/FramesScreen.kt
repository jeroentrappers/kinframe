package app.kinframe.family.ui.frames

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Frame
import app.kinframe.core.api.SubscriptionFeed
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.family.ui.Thumb
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.OnlineStatus
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.RoleBadge
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.Skeleton
import app.kinframe.family.ui.frameTint

/**
 * The Frames tab (docs/spec/simple-app.md §3.3): cards of the frames the person manages or is on, then the frames that
 * show their families, and "Add a frame".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramesScreen(
    state: FramesState,
    onRefresh: () -> Unit,
    onOpen: (Frame) -> Unit,
    onAdd: () -> Unit,
    /** "Add a family" for a frame you manage that doesn't show a family yet; null hides it. */
    onAddFamily: ((Frame) -> Unit)? = null,
    /** A frame that shows your families but that you aren't on. */
    onOpenFamilyFrame: ((SubscriptionFrame) -> Unit)? = null,
    /** A frame's server chip when signed in to several servers (multi-server.md §2.2), else null. */
    serverChip: (Frame) -> String? = { null },
) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        app.kinframe.family.ui.KinTabHeader(stringResource(R.string.nav_frames))
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when (val f = state.frames) {
                Load.Loading -> FramesSkeleton()
                is Load.Error -> ErrorPanel(f.message, onRefresh, Modifier.padding(top = 80.dp))
                is Load.Ready -> if (f.value.isEmpty() && state.familyFrames.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            stringResource(R.string.frames_none_title), stringResource(R.string.frames_none_body),
                            art = { FrameArt(width = 180.dp) },
                        ) { PrimaryButton(stringResource(R.string.frames_add), onAdd, icon = KinIcons.Plus) }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 300.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(f.value, key = { it.id }) { frame ->
                            val add = onAddFamily?.takeIf { frame.can { p -> p.changeSettings } && state.shows[frame.id].orEmpty().isEmpty() }
                            FrameCard(frame, state, add?.let { a -> { a(frame) } }, { onOpen(frame) }, serverChip(frame))
                        }
                        if (state.familyFrames.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.frames_other), style = Kin.type.title, color = c.ink, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(state.familyFrames, key = { "ff" + it.frame.id }) { ff -> FamilyFrameCard(ff, onOpenFamilyFrame) }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                                SecondaryButton(stringResource(R.string.frames_add), onAdd, icon = KinIcons.Plus)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A frame that shows your families but that you aren't on: name, status, which families. */
@Composable
private fun FamilyFrameCard(ff: FamilyFrame, onOpen: ((SubscriptionFrame) -> Unit)?) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
            .let { m -> if (onOpen != null) m.clickable(role = Role.Button) { onOpen(ff.frame) } else m }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) { Icon(KinIcons.Frame, null, tint = c.inkSoft) }
        Column(Modifier.weight(1f)) {
            Text(ff.frame.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(showsLine(ff.shows), style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ff.frame.online) stringResource(R.string.frame_online) else stringResource(R.string.frames_offline), style = Kin.type.caption,
                color = if (ff.frame.online) c.onSageSoft else c.inkFaint)
        }
    }
}

/** "Online · showing now" (the frame reported the photo on screen), "Online", or "Offline since 2 hours ago". */
@Composable
fun frameStatus(frame: Frame): String = when {
    FrameCards.showingNow(frame) -> stringResource(R.string.frames_online_now)
    frame.online -> stringResource(R.string.frame_online)
    frame.lastSeenAt != null -> stringResource(R.string.frames_offline_since, app.kinframe.family.util.Format.relative(frame.lastSeenAt!!))
    else -> stringResource(R.string.frames_offline)
}

/** "Shows Trappers", "Shows Trappers and 4 other families", or "Doesn't show a family yet". */
@Composable
fun showsLine(shows: List<SubscriptionFeed>): String = when (shows.size) {
    0 -> stringResource(R.string.frames_shows_none)
    1 -> stringResource(R.string.frames_shows, shows.single().name)
    else -> pluralStringResource(R.plurals.frames_shows_more, shows.size - 1, shows.first().name, shows.size - 1)
}

/** "Oma’s frame loved 3 photos today"; null when it reacted to nothing today (or the person can't see that). */
@Composable
fun lovedTodayLine(frame: Frame): String? =
    FrameCards.lovedToday(frame)?.let { n -> pluralStringResource(R.plurals.frames_loved_today, n, frame.name, n) }

/** A frame's card: what it shows now, its status, name, which family it shows, today's reactions. */
@Composable
fun FrameCard(frame: Frame, state: FramesState, onAddFamily: (() -> Unit)?, onOpen: () -> Unit, server: String? = null) {
    val c = Kin.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.frames_open, frame.name), onClick = onOpen),
    ) {
        FrameHero(frame, FrameCards.photo(frame))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frame.name, style = Kin.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (frame.deviceKind == "browser") Pill(stringResource(R.string.frames_browser_badge), Kin.colors.paperDeep, Kin.colors.inkSoft, icon = KinIcons.Tv)
            }
            server?.let { Pill(it, c.paperDeep, c.inkSoft, icon = KinIcons.Server) }
            Text(showsLine(state.shows[frame.id].orEmpty()), style = Kin.type.body, color = c.inkSoft)
            LovedToday(frame)
            if (onAddFamily != null) SecondaryButton(stringResource(R.string.frames_add_family), onAddFamily, enabled = state.adding == null, icon = KinIcons.Plus)
            if (frame.needsUpdate) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(KinIcons.Info, null, Modifier.size(15.dp), tint = c.warn)
                    Text(stringResource(R.string.frames_needs_update), style = Kin.type.caption, color = c.warn)
                }
            }
        }
    }
}

/** Today's reactions under a frame card's name, with a heart; nothing when there are none. */
@Composable
fun LovedToday(frame: Frame) {
    val line = lovedTodayLine(frame) ?: return
    val c = Kin.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(KinIcons.Heart, null, Modifier.size(15.dp), tint = c.accent)
        Text(line, style = Kin.type.caption, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The photo the frame shows ([photo]: the one on screen now, else the last placed one), or the frame drawing when
 * there is none, with the status chip.
 */
@Composable
fun FrameHero(frame: Frame, photo: String?, modifier: Modifier = Modifier) {
    val c = Kin.colors
    val (a, b) = frameTint(frame.id)
    val status = frameStatus(frame)
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(Brush.radialGradient(listOf(a, b), radius = 900f).let { if (c.isDark) Brush.linearGradient(listOf(a.copy(alpha = 0.55f), b.copy(alpha = 0.55f))) else it })
            .semantics { contentDescription = status },
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) Thumb(photo, null, Modifier.fillMaxSize(), corner = 0.dp)
        else {
            val portrait = frame.screen?.orientation == "portrait"
            FrameArt(width = if (portrait) 64.dp else 128.dp, portrait = portrait)
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(if (frame.online) c.sageSoft else c.paperDeep).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (frame.online) c.sage else c.inkFaint))
            Text(status, style = Kin.type.caption, color = if (frame.online) c.onSageSoft else c.inkSoft)
        }
    }
}

@Composable
private fun FramesSkeleton() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Skeleton(Modifier.fillMaxWidth(0.5f).height(30.dp))
        repeat(2) {
            Skeleton(Modifier.fillMaxWidth().height(230.dp), corner = 20.dp)
        }
    }
}
