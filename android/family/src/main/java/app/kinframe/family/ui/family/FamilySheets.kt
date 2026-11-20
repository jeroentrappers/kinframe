package app.kinframe.family.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInviteCreate
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.Thumb

/** Showing a family on a frame (docs/spec/simple-app.md §5, §7). */
object FamilyFrames {
    /** The history the apps ask for: the family's last 50 posts come along (changeable in Frame settings). */
    const val HISTORY_COUNT = 50

    /** "Add a frame" to a family: no dialog, the last 50, birthdays on. */
    fun request(frameId: String) = SubscriptionRequest(frameId, SubscriptionHistory.LAST_N, HISTORY_COUNT, true)

    /** Frames you manage that don't show (or wait to show) this family yet. */
    fun candidates(managed: List<Frame>, subscriptions: List<Subscription>): List<Frame> =
        managed.filter { f -> subscriptions.none { it.frame.id == f.id && (it.isLive || it.status == SubscriptionStatus.PENDING) } }

    /** The server made it active at once (you manage the frame and are in the family), or an admin has to approve. */
    fun pending(sub: Subscription): Boolean = sub.status == SubscriptionStatus.PENDING

    /** Ask the family to show the frame; the subscription tells whether it is active or waiting. */
    suspend fun add(api: KinApi, feedId: String, frameId: String, key: String): Subscription = api.requestSubscription(feedId, request(frameId), key)
}

/** Invites to the family: one link anyone can use for a week. */
object FamilyInvites {
    const val HOURS = 168

    suspend fun create(api: KinApi, feedId: String, key: String): FeedInvite =
        api.createFeedInvite(feedId, FeedInviteCreate(FeedInvitePurpose.JOIN, null, HOURS, null), key)
}

@Composable
fun FamilyRow(f: Feed, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.surface)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FamilyCover(f, 40)
        Column(Modifier.weight(1f)) {
            Text(f.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
        }
        if (selected) Icon(KinIcons.Check, null, tint = c.onAccentSoft)
    }
}

@Composable
fun FamilyCover(f: Feed?, size: Int) {
    val c = Kin.colors
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.paperDeep), contentAlignment = Alignment.Center) {
        if (f?.coverUrl != null) Thumb(f.coverUrl, null, Modifier.fillMaxSize(), corner = (size / 2).dp)
        else Icon(KinIcons.People, null, Modifier.size((size / 2).dp), tint = c.inkSoft)
    }
}

/** "Add a frame to Peeters family": one of your frames (shown at once), or pair a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFrameSheet(familyName: String, frames: List<Frame>, busy: Boolean, onPick: (Frame) -> Unit, onPair: () -> Unit, onDismiss: () -> Unit) {
    val c = Kin.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.add_frame_sheet_title, familyName), style = Kin.type.title, color = c.ink)
            Text(stringResource(R.string.add_frame_sheet_body), style = Kin.type.caption, color = c.inkSoft)
            frames.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button) { onPick(f) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.paperDeep), contentAlignment = Alignment.Center) {
                        Icon(KinIcons.Frame, null, tint = c.inkSoft)
                    }
                    Text(f.name, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(KinIcons.Plus, null, tint = c.accent)
                }
            }
            if (frames.isEmpty()) Note(stringResource(R.string.add_frame_all_done))
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
            SettingsLink(stringResource(R.string.add_frame_pair), KinIcons.Plus, onPair)
        }
    }
}

/** "12 people · 3 frames". */
@Composable
fun familyCounts(f: Feed): String =
    "${f.memberCount} " + androidx.compose.ui.res.pluralStringResource(R.plurals.family_people, f.memberCount) + " · " +
        "${f.subscriptionCount} " + androidx.compose.ui.res.pluralStringResource(R.plurals.family_frames, f.subscriptionCount)

/**
 * The Family tab with several families (docs/spec/simple-app.md §3.4): each family (home family first) opens its
 * profile; "Start a family" at the bottom; the gear opens the App settings.
 */
@Composable
fun FamilyListScreen(families: List<Feed>, home: String?, onOpen: (Feed) -> Unit, onSettings: () -> Unit, onStart: () -> Unit, onJoin: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.family_list_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onSettings) { Icon(KinIcons.Settings, stringResource(R.string.nav_settings), tint = c.ink) }
        }
        app.kinframe.family.ui.PageColumn(spacing = 8.dp) {
            families.forEach { f -> FamilyRow(f, selected = false, subtitle = familyCounts(f)) { onOpen(f) } }
            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
            SettingsLink(stringResource(R.string.family_start_another), KinIcons.Plus, onStart)
            SettingsLink(stringResource(R.string.family_join_another), KinIcons.Link, onJoin)
        }
    }
}

/** "Add a family" to a frame you manage that shows none yet: one of your families, shown at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFamilySheet(frameName: String, families: List<Feed>, busy: Boolean, onPick: (Feed) -> Unit, onDismiss: () -> Unit) {
    val c = Kin.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.add_family_sheet_title, frameName), style = Kin.type.title, color = c.ink)
            Text(stringResource(R.string.add_frame_sheet_body), style = Kin.type.caption, color = c.inkSoft)
            families.forEach { f -> FamilyRow(f, selected = false, subtitle = familyCounts(f)) { if (!busy) onPick(f) } }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
        }
    }
}
