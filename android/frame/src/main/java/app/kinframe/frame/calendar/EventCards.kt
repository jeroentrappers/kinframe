package app.kinframe.frame.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.frame.R
import kotlinx.coroutines.delay
import java.time.ZoneId

/** Event details close themselves after 5 minutes without a touch (behaviour spec B.12). */
const val EVENT_DETAIL_IDLE_MS = 5 * 60_000L

/**
 * Event details as a sheet from the end edge (like the forecast): calendar, title, when, where, notes, who sent it,
 * the photo of a received event, and the countdown switch.
 */
@Composable
fun EventDetailOverlay(
    event: CalEvent?,
    photo: ImageBitmap?,
    h24: Boolean,
    onCountdown: (CalEvent, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(event?.id, touched) {
        if (event != null) { delay(EVENT_DETAIL_IDLE_MS); onClose() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val w = if (landscape) 560.dp else maxWidth - 64.dp
        AnimatedVisibility(event != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        }
        AnimatedVisibility(
            event != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(180)),
        ) {
            val e = event ?: return@AnimatedVisibility
            Box(
                Modifier.width(w).fillMaxHeight()
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched = System.currentTimeMillis() } } }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { },
            ) {
                EventDetailSheet(e, photo, h24, onCountdown = { onCountdown(e, it) }, onClose = onClose)
            }
        }
    }
}

/** The sheet's content (separate so screenshot tests can render it directly). */
@Composable
fun EventDetailSheet(e: CalEvent, photo: ImageBitmap?, h24: Boolean, onCountdown: (Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    val sw = CalendarPalette.of(e.color)
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(sw.strong))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 36.dp, end = 24.dp, top = 22.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (e.local) KinIcon(KinIcons.HeartFilled, null, tint = sw.strong, size = 18.dp)
                    else Box(Modifier.size(12.dp).clip(CircleShape).background(sw.strong))
                    Text(
                        sourceLabel(e.sourceName, e.local), style = Kin.type.label, color = Kin.colors.inkSoft,
                        modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    KinIcon(KinIcons.Close, stringResource(R.string.action_close), size = 26.dp)
                }
            }
            if (photo != null) {
                Image(
                    photo, null, Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(e.title, style = Kin.type.headline, color = Kin.colors.ink, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(18.dp))
            DetailRow(KinIcons.Clock) {
                Text(CalendarFormat.dateLabel(context, e, locale), style = Kin.type.bodyStrong, color = Kin.colors.ink)
                Text(CalendarFormat.whenLabel(context, e, null, h24, locale), style = Kin.type.body, color = Kin.colors.inkSoft)
            }
            if (e.recurring) DetailRow(CalendarIcons.Repeat) { Text(stringResource(R.string.event_repeats), style = Kin.type.body, color = Kin.colors.inkSoft) }
            e.location?.let { DetailRow(CalendarIcons.Pin) { Text(it, style = Kin.type.body, color = Kin.colors.ink) } }
            e.notes?.let { DetailRow(CalendarIcons.Notes) { Text(it, style = Kin.type.body, color = Kin.colors.ink) } }
            e.senderName?.let { DetailRow(KinIcons.Heart) { Text(stringResource(R.string.event_sent_by, it), style = Kin.type.body, color = Kin.colors.ink) } }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kin.colors.surface).border(1.dp, Kin.colors.line, RoundedCornerShape(18.dp))
                    .clickable { onCountdown(!e.countdown) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KinIcon(CalendarIcons.Hourglass, null, tint = Kin.colors.accent, size = 26.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(stringResource(R.string.event_countdown), style = Kin.type.bodyStrong)
                    Text(stringResource(R.string.event_countdown_hint), style = Kin.type.caption, color = Kin.colors.inkSoft)
                }
                Switch(
                    e.countdown, onCountdown,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Kin.colors.accent, checkedThumbColor = Kin.colors.onAccent, uncheckedTrackColor = Kin.colors.paperDeep,
                        uncheckedBorderColor = Kin.colors.line, uncheckedThumbColor = Kin.colors.inkFaint,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, content: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 8.dp)) {
        KinIcon(icon, null, tint = Kin.colors.inkSoft, size = 24.dp, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(start = 16.dp)) { content() }
    }
}

// ------------------------------------------------------------------------------------------ invitations

/**
 * "Oma sent you an event": the calm full-screen card for an invitation (behaviour spec B.12), in the style of the
 * join-request card. The photo sits on top of the card, or fills it behind the text (`background` placement).
 * Accepting puts the event in the frame's received-events calendar; the decision is final.
 */
@Composable
fun InvitationCard(
    inv: EventInvitation,
    photo: ImageBitmap?,
    zone: ZoneId,
    h24: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onLater: () -> Unit,
) {
    val ev = remember(inv, zone) { invitationEvent(inv, zone) }
    val context = LocalContext.current
    val locale = currentLocale()
    val background = photo != null && inv.photoPlacement == "background"
    Box(
        Modifier.fillMaxSize().background(Kin.colors.night.copy(alpha = 0.72f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            val narrow = maxWidth < 600.dp
            val cardWidth = Modifier.widthIn(max = 680.dp)
            val details = @Composable { onDark: Boolean ->
                val ink = if (onDark) Color.White else Kin.colors.ink
                val soft = if (onDark) Color.White.copy(alpha = 0.85f) else Kin.colors.inkSoft
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Kin.colors.love), contentAlignment = Alignment.Center) {
                        KinIcon(KinIcons.HeartFilled, null, tint = Color.White, size = 20.dp)
                    }
                    Text(
                        stringResource(R.string.invitation_title, inv.senderName), style = Kin.type.label, color = soft,
                        modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(inv.title, style = Kin.type.headline, color = ink, modifier = Modifier.padding(top = 14.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (ev != null) {
                    val allDay = stringResource(R.string.calendar_all_day)
                    Text(
                        CalendarFormat.dateLabel(context, ev, locale) + "  ·  " +
                            (if (ev.multiDay && ev.allDay) allDay else CalendarFormat.whenLabel(context, ev, null, h24, locale)),
                        style = Kin.type.bodyStrong, color = ink, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                inv.location?.takeIf { it.isNotBlank() }?.let {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        KinIcon(CalendarIcons.Pin, null, tint = soft, size = 20.dp)
                        Text(it, style = Kin.type.body, color = soft, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                inv.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Kin.type.body, color = soft, modifier = Modifier.padding(top = 10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            val buttons = @Composable { onDark: Boolean ->
                val accept = @Composable { KinButton(stringResource(R.string.invitation_accept), onAccept, leading = { KinIcon(KinIcons.Check, null, tint = Color.White, size = 22.dp) }) }
                val decline = @Composable { KinButton(stringResource(R.string.invitation_decline), onDecline, style = KinButtonStyle.Secondary) }
                if (narrow) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { accept(); decline() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.invitation_later), style = Kin.type.label,
                            color = if (onDark) Color.White.copy(alpha = 0.85f) else Kin.colors.accent,
                            modifier = Modifier.clip(CircleShape).clickable(onClick = onLater).padding(horizontal = 18.dp, vertical = 14.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        decline(); accept()
                    }
                }
            }
            if (background) {
                Box(cardWidth.fillMaxWidth().aspectRatio(if (narrow) 0.8f else 1.45f).clip(KinShapeL)) {
                    Image(photo!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x33000000), 1f to Color(0xE0140F0C))))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp)) {
                        details(true)
                        Spacer(Modifier.height(24.dp))
                        buttons(true)
                    }
                }
            } else {
                Column(cardWidth.clip(KinShapeL).background(Kin.colors.surface)) {
                    if (photo != null) {
                        Image(photo, null, Modifier.fillMaxWidth().aspectRatio(2f), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.padding(horizontal = 40.dp, vertical = 32.dp)) {
                        details(false)
                        Text(stringResource(R.string.invitation_body), style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 18.dp))
                        Spacer(Modifier.height(22.dp))
                        buttons(false)
                    }
                }
            }
            if (narrow) {
                KinButton(stringResource(R.string.invitation_later), onLater, style = KinButtonStyle.Quiet, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** The invitation as an event, for the shared date and time labels. */
fun invitationEvent(inv: EventInvitation, zone: ZoneId): CalEvent? = CalendarData.resolve(
    app.kinframe.core.api.CalendarEvent(
        id = "inv-${inv.id}", sourceId = "", title = inv.title, allDay = inv.allDay, startAt = inv.startAt, endAt = inv.endAt,
        startDate = inv.startDate, endDate = inv.endDate, location = inv.location, notes = inv.notes,
    ),
    null, zone,
)

/** Small pill on the slideshow while invitations wait; opens the card again. */
@Composable
fun InvitationsPill(items: List<EventInvitation>, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(items.isNotEmpty(), modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        val first = items.firstOrNull() ?: return@AnimatedVisibility
        Row(
            Modifier.clip(CircleShape).background(Kin.colors.surface.copy(alpha = 0.96f)).clickable(onClick = onClick)
                .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Kin.colors.love), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.HeartFilled, null, tint = Color.White, size = 20.dp)
            }
            Text(
                if (items.size == 1) stringResource(R.string.invitation_pill_one, first.senderName)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.invitation_pill_many, items.size, items.size),
                style = Kin.type.label, color = Kin.colors.ink, modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
