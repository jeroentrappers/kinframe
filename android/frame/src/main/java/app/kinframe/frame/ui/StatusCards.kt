package app.kinframe.frame.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeM
import app.kinframe.frame.R

/** A situation worth a calm card on the slideshow (frameo-behaviour-spec B.14: storage, network, captive portal). */
enum class FrameStatus { Offline, ServerUnreachable, CaptivePortal, StorageCritical }

object FrameStatuses {
    /** The one card to show, most actionable first. [conn] is null before the frame has a session (setup). */
    fun of(
        conn: app.kinframe.frame.sync.FrameSession.Connection?,
        networkUp: Boolean,
        captivePortal: Boolean,
        storage: app.kinframe.frame.storage.StoragePolicy.Level,
    ): FrameStatus? = when {
        networkUp && captivePortal -> FrameStatus.CaptivePortal
        !networkUp || conn == app.kinframe.frame.sync.FrameSession.Connection.Offline -> FrameStatus.Offline
        conn == app.kinframe.frame.sync.FrameSession.Connection.ServerUnreachable -> FrameStatus.ServerUnreachable
        storage == app.kinframe.frame.storage.StoragePolicy.Level.Critical -> FrameStatus.StorageCritical
        else -> null
    }

    /** Network trouble shows after a minute (short blips don't flash a card); the others right away. */
    fun delayMs(s: FrameStatus?): Long = if (s == FrameStatus.Offline || s == FrameStatus.ServerUnreachable) 60_000 else 0

    /** A dismissed storage card comes back after a day; network cards when the situation changes. */
    const val STORAGE_SNOOZE_MS = 24 * 3_600_000L
}

/**
 * A small, dismissible status card: icon, one-line title, a sentence with the next step, an optional action.
 * It sits in a corner over the photo, never blocks it, and goes away by itself when the situation resolves.
 */
@Composable
fun StatusCard(
    icon: ImageVector,
    title: String,
    body: String,
    tone: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    dismissible: Boolean = true,
) {
    Row(
        modifier.width(440.dp).clip(KinShapeM).background(Kin.colors.surface.copy(alpha = 0.97f)).border(1.dp, Kin.colors.line, KinShapeM)
            .padding(start = 18.dp, top = 16.dp, bottom = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            KinIcon(icon, null, tint = tone, size = 24.dp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp)) {
            Text(title, style = Kin.type.bodyStrong, color = Kin.colors.ink)
            Text(body, style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 2.dp))
            if (action != null && onAction != null) {
                Row(
                    Modifier.padding(top = 10.dp).clip(CircleShape).background(Kin.colors.accent).clickable(onClick = onAction)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(action, style = Kin.type.label, color = Kin.colors.onAccent) }
            }
        }
        if (dismissible) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.Close, stringResource(R.string.action_close), tint = Kin.colors.inkFaint, size = 20.dp)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** The card for [status], or nothing. [onAction] is the captive-portal sign-in. */
@Composable
fun FrameStatusCard(status: FrameStatus?, onDismiss: () -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(status != null, modifier, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut() + slideOutVertically { -it / 3 }) {
        val s = status ?: return@AnimatedVisibility
        when (s) {
            FrameStatus.Offline -> StatusCard(
                KinIcons.WifiOff, stringResource(R.string.status_card_offline_title), stringResource(R.string.status_card_offline_body),
                Kin.colors.inkSoft, onDismiss,
            )
            FrameStatus.ServerUnreachable -> StatusCard(
                KinIcons.Server, stringResource(R.string.status_card_server_title), stringResource(R.string.status_card_server_body),
                Kin.colors.inkSoft, onDismiss,
            )
            FrameStatus.CaptivePortal -> StatusCard(
                KinIcons.Login, stringResource(R.string.status_card_portal_title), stringResource(R.string.status_card_portal_body),
                Kin.colors.accent, onDismiss, action = stringResource(R.string.status_card_portal_action), onAction = onAction,
            )
            FrameStatus.StorageCritical -> StatusCard(
                KinIcons.Storage, stringResource(R.string.status_card_storage_title), stringResource(R.string.status_card_storage_body),
                Kin.colors.danger, onDismiss,
            )
        }
    }
}
