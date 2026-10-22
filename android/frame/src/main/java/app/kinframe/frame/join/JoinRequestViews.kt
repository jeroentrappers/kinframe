package app.kinframe.frame.join

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.slideshow.Avatars
import app.kinframe.frame.ui.Format

/** Round avatar: the photo when there is one, otherwise initials on a colour picked from the name. */
@Composable
fun PersonAvatar(name: String, seed: String, image: ImageBitmap?, size: Dp, ring: Color = Color.White) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(Avatars.color(seed))).border(3.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(Avatars.initials(name), color = Color.White, fontSize = (size.value * 0.36f).sp, style = Kin.type.title)
        }
    }
}

/**
 * The calm full-screen card for "<name> wants to join": a warm dim over the photo, one card, three clear choices.
 * Allow is the obvious action; "Not now" keeps the request in the notifications list.
 */
@Composable
fun JoinRequestCard(
    req: JoinRequestUi,
    avatar: ImageBitmap?,
    frameName: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onLater: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Kin.colors.night.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            val narrow = maxWidth < 600.dp
            Column(
                Modifier.widthIn(max = 720.dp).clip(KinShapeL).background(Kin.colors.surface).padding(horizontal = 56.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    PersonAvatar(req.name, req.userId ?: req.name, avatar, 132.dp, ring = Kin.colors.surface)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Kin.colors.accent).border(3.dp, Kin.colors.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { KinIcon(KinIcons.UserPlus, null, tint = Color.White, size = 24.dp) }
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.join_title, req.name), style = Kin.type.headline, color = Kin.colors.ink,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (req.invitedBy != null) stringResource(R.string.join_body_invited, req.invitedBy, frameName, req.name)
                    else stringResource(R.string.join_body_code, req.name, frameName),
                    style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
                if (req.note != null) {
                    Text(
                        "“${req.note}”", style = Kin.type.body.copy(fontSize = 20.sp), color = Kin.colors.ink, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 20.dp).clip(KinShapeL).background(Kin.colors.paper).padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.height(36.dp))
                val buttons = @Composable {
                    KinButton(stringResource(R.string.join_deny), onDeny, style = KinButtonStyle.Secondary)
                    KinButton(stringResource(R.string.join_allow), onAllow, leading = { KinIcon(KinIcons.Check, null, tint = Color.White, size = 22.dp) })
                }
                if (narrow) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        KinButton(stringResource(R.string.join_allow), onAllow, leading = { KinIcon(KinIcons.Check, null, tint = Color.White, size = 22.dp) })
                        KinButton(stringResource(R.string.join_deny), onDeny, style = KinButtonStyle.Secondary)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
                }
                Spacer(Modifier.height(10.dp))
                KinButton(stringResource(R.string.join_later), onLater, style = KinButtonStyle.Quiet)
                Text(stringResource(R.string.join_later_hint), style = Kin.type.caption, color = Kin.colors.inkFaint, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Small pill on the slideshow while requests wait ("Anna wants to join"); opens the notifications. */
@Composable
fun JoinRequestsPill(items: List<JoinRequestUi>, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(items.isNotEmpty(), modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val first = items.firstOrNull() ?: return@AnimatedVisibility
        Row(
            Modifier.clip(CircleShape).background(Kin.colors.surface.copy(alpha = 0.96f)).clickable(onClick = onClick)
                .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(first.name, first.userId ?: first.name, null, 40.dp, ring = Kin.colors.surface)
            Text(
                if (items.size == 1) stringResource(R.string.join_pill_one, first.name)
                else pluralStringResource(R.plurals.join_pill_many, items.size, items.size),
                style = Kin.type.label, color = Kin.colors.ink, modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The notifications side panel: everything that waits for a decision on the frame. */
@Composable
fun NotificationsPanel(
    visible: Boolean,
    items: List<JoinRequestUi>,
    avatars: Map<String, ImageBitmap>,
    nowMs: Long,
    onAllow: (String) -> Unit,
    onDeny: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = if (maxWidth > maxHeight) 520.dp else maxWidth
        AnimatedVisibility(
            visible, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            Column(Modifier.width(w).fillMaxHeight().background(Kin.colors.paper).padding(top = 28.dp, start = 28.dp, end = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KinIcon(KinIcons.Bell, null, tint = Kin.colors.accent)
                    Text(stringResource(R.string.notifications_title), style = Kin.type.headline, modifier = Modifier.padding(start = 14.dp).weight(1f))
                    Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        KinIcon(KinIcons.Close, stringResource(R.string.action_close))
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (items.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp).clip(CircleShape).background(Kin.colors.sageSoft), contentAlignment = Alignment.Center) {
                            KinIcon(KinIcons.Check, null, tint = Kin.colors.sage, size = 40.dp)
                        }
                        Text(stringResource(R.string.notifications_empty), style = Kin.type.title, modifier = Modifier.padding(top = 20.dp))
                        Text(stringResource(R.string.notifications_empty_body), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.id }) { r ->
                            Column(Modifier.fillMaxWidth().clip(KinShapeL).background(Kin.colors.surface).border(1.dp, Kin.colors.line, KinShapeL).padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PersonAvatar(r.name, r.userId ?: r.name, avatars[r.id], 56.dp, ring = Kin.colors.surface)
                                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                        Text(stringResource(R.string.join_title, r.name), style = Kin.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val sub = listOfNotNull(
                                            r.invitedBy?.let { stringResource(R.string.join_invited_by, it) },
                                            r.createdAtMs?.let { Format.relativeDate(context, it, nowMs, java.time.ZoneId.systemDefault()) },
                                        ).joinToString(" · ")
                                        if (sub.isNotEmpty()) Text(sub, style = Kin.type.caption, color = Kin.colors.inkSoft)
                                    }
                                }
                                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                                    KinButton(stringResource(R.string.join_deny), { onDeny(r.id) }, style = KinButtonStyle.Secondary)
                                    KinButton(stringResource(R.string.join_allow), { onAllow(r.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Wires the card to [JoinRequests]: loads the avatar and shows the card while one is focused. */
@Composable
fun JoinRequestLayer(c: FrameController, frameName: String) {
    val focused by c.joins.focused.collectAsState()
    val avatars = rememberJoinAvatars(c)
    AnimatedVisibility(focused != null, enter = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.96f), exit = fadeOut(tween(250))) {
        val r = focused ?: return@AnimatedVisibility
        JoinRequestCard(
            r, avatars[r.id], frameName,
            onAllow = { c.joins.decide(r.id, approve = true) },
            onDeny = { c.joins.decide(r.id, approve = false) },
            onLater = { c.joins.later() },
        )
    }
}

/** Avatars of pending requests, loaded once each (by request id). */
@Composable
fun rememberJoinAvatars(c: FrameController): Map<String, ImageBitmap> {
    val pending by c.joins.pending.collectAsState()
    val map = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(pending) {
        for (r in pending) {
            if (r.id in map || r.userId == null || r.avatarUrl == null) continue
            Avatars.loadUrl(c.g, r.userId, r.avatarUrl)?.let { map[r.id] = it.asImageBitmap() }
        }
    }
    return map
}
