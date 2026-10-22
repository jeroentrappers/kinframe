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
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.Skeleton
import app.kinframe.family.ui.frameTint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramesScreen(
    state: FramesState,
    onRefresh: () -> Unit,
    onOpen: (Frame) -> Unit,
    onAdd: () -> Unit,
    onSendTo: (Frame) -> Unit,
) {
    val c = Kin.colors
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().background(c.paper)) {
        when (val f = state.frames) {
            Load.Loading -> FramesSkeleton()
            is Load.Error -> ErrorPanel(f.message, onRefresh, Modifier.padding(top = 80.dp))
            is Load.Ready -> if (f.value.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        stringResource(R.string.frames_empty_title), stringResource(R.string.frames_empty_body),
                        art = { FrameArt(width = 180.dp) },
                    ) { PrimaryButton(stringResource(R.string.frames_empty_cta), onAdd, icon = KinIcons.Plus) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                            Text(stringResource(R.string.frames_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
                            Text(
                                pluralStringResource(R.plurals.frames_summary, f.value.size, f.value.size, state.online),
                                style = Kin.type.caption, color = c.inkSoft,
                            )
                        }
                    }
                    items(f.value, key = { it.id }) { frame -> FrameCard(frame, { onOpen(frame) }, { onSendTo(frame) }) }
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

@Composable
fun FrameCard(frame: Frame, onOpen: () -> Unit, onSend: () -> Unit) {
    val c = Kin.colors
    val (a, b) = frameTint(frame.id)
    val status = stringResource(if (frame.online) R.string.a11y_frame_status_online else R.string.a11y_frame_status_offline, frame.name)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.frames_open, frame.name), onClick = onOpen),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 7f)
                .background(Brush.radialGradient(listOf(a, b), radius = 900f).let { if (c.isDark) Brush.linearGradient(listOf(a.copy(alpha = 0.55f), b.copy(alpha = 0.55f))) else it })
                .semantics { contentDescription = status },
            contentAlignment = Alignment.Center,
        ) {
            val portrait = frame.screen?.orientation == "portrait"
            FrameArt(width = if (portrait) 64.dp else 128.dp, portrait = portrait)
            if (frame.online) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3C6A49)))
                    Text(stringResource(R.string.frame_online), style = Kin.type.caption, color = Color(0xFF2F5A3C))
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frame.name, style = Kin.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                RoleBadge(frame.role)
            }
            frame.location?.takeIf { it.isNotBlank() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(KinIcons.MapPin, null, Modifier.size(15.dp), tint = c.inkSoft)
                    Text(it, style = Kin.type.caption, color = c.inkSoft)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnlineStatus(frame.online, frame.lastSeenAt, Modifier.weight(1f))
                if (frame.can { it.send }) {
                    val sendLabel = stringResource(R.string.frames_send_to, frame.name)
                    Row(
                        Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onSend)
                            .semantics(mergeDescendants = true) { contentDescription = sendLabel }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(KinIcons.Send, null, Modifier.size(18.dp), tint = c.accent)
                        Text(stringResource(R.string.nav_send), style = Kin.type.label, color = c.accent)
                    }
                }
            }
            if (frame.needsUpdate) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(KinIcons.Info, null, Modifier.size(15.dp), tint = c.warn)
                    Text(stringResource(R.string.frames_needs_update), style = Kin.type.caption, color = c.warn)
                }
            }
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
