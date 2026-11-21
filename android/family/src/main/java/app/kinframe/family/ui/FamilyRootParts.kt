package app.kinframe.family.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinframe.core.api.Feed
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.PersonReaction
import app.kinframe.family.AppGraph
import app.kinframe.family.R
import app.kinframe.family.ui.family.Families
import app.kinframe.family.ui.family.FamilyPrefs
import app.kinframe.family.ui.family.GettingStartedScreen
import app.kinframe.family.ui.family.LinkList
import app.kinframe.family.ui.family.SettingsLink
import app.kinframe.family.ui.family.SetupActions
import app.kinframe.family.ui.family.SetupStep
import app.kinframe.family.ui.family.SimpleViewActions
import app.kinframe.family.ui.family.SimpleViewScreen
import app.kinframe.family.ui.feeds.FeedsViewModel
import app.kinframe.family.ui.frames.FrameAdded

/** What "Add to Peeters family" says: shown now, or waiting for an admin of the family. */
internal fun frameAddedMessage(res: android.content.res.Resources, r: FrameAdded): String = when (r) {
    is FrameAdded.Active -> res.getString(R.string.add_frame_added, r.frame, r.family)
    FrameAdded.Pending -> res.getString(R.string.add_frame_pending)
    is FrameAdded.Failed -> res.getString(r.message)
}

/** Getting started (docs/spec/simple-app.md §8): start a family (name, Oma's frame, invite) or join one. */
@Composable
internal fun GettingStarted(
    graph: AppGraph, ready: Phase.Ready, feeds: FeedsViewModel, nav: Navigator, step: SetupStep, onStep: (SetupStep?) -> Unit,
    invite: (feedId: String, name: String) -> Unit, onCreated: (Feed) -> Unit,
    /** Someone on a frame can go back to the app; otherwise getting started links to sent history and settings. */
    canCancel: Boolean = false,
) {
    val fs by feeds.state.collectAsStateWithLifecycle()
    val me = graph.me.collectAsStateWithLifecycle().value ?: ready.me
    val surname = Families.surname(me.displayName)
    val defaultName = surname?.let { stringResource(R.string.start_name_default, it) }.orEmpty()
    GettingStartedScreen(step, defaultName, fs.creating, fs.createError, SetupActions(
        onStep = onStep,
        onCreate = { name -> feeds.create(name) { f -> onCreated(f); onStep(SetupStep.Frame(f.id, f.name)) } },
        onJoin = { nav.push(Route.AddFrame(join = true)) },
        onPair = { feedId ->
            (step as? SetupStep.Frame)?.let { onStep(SetupStep.Invite(it.feedId, it.name)) }
            nav.push(Route.AddFrame(family = feedId))
        },
        onShareInvite = invite,
        onCancel = if (canCancel) ({ onStep(null) }) else null,
        onHistory = if (canCancel) null else ({ nav.push(Route.History) }),
        onSettings = if (canCancel) null else ({ nav.push(Route.Settings()) }),
    ))
}

/** The simple view of the current family. */
@Composable
internal fun SimpleViewRoute(graph: AppGraph, ready: Phase.Ready, app: AppViewModel, families: List<Feed>, prefs: FamilyPrefs) {
    val context = LocalContext.current
    val social = graph.social.api.collectAsStateWithLifecycle().value ?: return
    // Every family together, like Home with All.
    val vm = homeViewModel(graph, ready, social, graph.social.flags, null)
    val s by vm.state.collectAsStateWithLifecycle()
    val me = graph.me.collectAsStateWithLifecycle().value ?: ready.me
    SimpleViewScreen(families.singleOrNull()?.name ?: stringResource(R.string.social_nav_home), s, me, showFamily = families.size > 1, a = SimpleViewActions(
        onHeart = { p -> if (p.thread.reactions.mine != PersonReaction.LOVE) vm.react(p, PersonReaction.LOVE) },
        onLoadMore = vm::loadMore,
        onRefresh = { vm.refresh() },
        onNormalView = { prefs.setSimpleView(false) },
        onSignOut = { app.signOut(context.findActivity()) },
    ))
}

/**
 * Settings › This family (docs/spec/simple-app.md §3.4): name and cover, circles, birthdays, more ways to add photos,
 * who can invite and approvals, leave or delete.
 */
@Composable
internal fun ThisFamilySettings(graph: AppGraph, f: Feed, nav: Navigator) {
    val c = Kin.colors
    val d = graph.accounts.contextFor(f.id).discovery
    Column {
        Text(stringResource(R.string.settings_this_family), style = Kin.type.headline, color = c.ink)
        Text(f.name, style = Kin.type.body, color = c.inkSoft)
    }
    LinkList {
        if (f.isAdmin) SettingsLink(stringResource(R.string.settings_family_name_cover), KinIcons.Edit, { nav.push(Route.FeedDetail(f.id, FeedTab.Settings)) },
            badge = f.pendingJoinRequestCount + f.pendingSubscriptionCount)
        if (d?.social != false) SettingsLink(stringResource(R.string.settings_family_circles), KinIcons.People, { nav.push(CirclesRoute(f.id)) })
        if (d?.birthdays == true) SettingsLink(stringResource(R.string.settings_family_birthdays), KinIcons.Calendar, { nav.push(Route.FeedDetail(f.id, FeedTab.Birthdays)) },
            badge = f.pendingBirthdayCount)
        if (d != null && (d.sourceProviders.isNotEmpty() || d.googlePhotosImport || (f.isAdmin && (d.emailInbox || d.telegram))))
            SettingsLink(stringResource(R.string.settings_family_more_ways), KinIcons.Image, { nav.push(Route.FeedDetail(f.id, FeedTab.Sources)) })
        if (!f.isAdmin) SettingsLink(stringResource(R.string.settings_family_leave), KinIcons.SignOut, { nav.push(Route.FeedDetail(f.id, FeedTab.Settings)) })
        else SettingsLink(stringResource(R.string.settings_family_leave_delete), KinIcons.SignOut, { nav.push(Route.FeedDetail(f.id, FeedTab.Settings)) })
    }
}
