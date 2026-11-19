package app.kinframe.family.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinframe.core.api.Feed
import app.kinframe.core.api.Frame
import app.kinframe.core.leaving.LeavingPush
import app.kinframe.core.leaving.PlacementUpdateReason
import app.kinframe.family.AppGraph
import app.kinframe.family.OpenTarget
import app.kinframe.family.R
import app.kinframe.family.accounts.AddServerRequest
import app.kinframe.family.data.LeavingLive
import app.kinframe.family.notify.LeavingNotifications
import app.kinframe.family.ui.leaving.FormerPlacesScreen
import app.kinframe.family.ui.leaving.FormerPlacesViewModel
import app.kinframe.family.ui.leaving.KeepRequestsScreen
import app.kinframe.family.ui.leaving.KeepRequestsViewModel
import app.kinframe.family.ui.leaving.LeaveSheet
import app.kinframe.family.ui.leaving.LeaveSheetViewModel
import app.kinframe.family.ui.leaving.LeavingNotice
import app.kinframe.family.ui.leaving.LeavingSettingsSection
import app.kinframe.family.ui.leaving.MemoriesScreen
import app.kinframe.family.ui.leaving.MemoriesViewModel
import app.kinframe.family.ui.leaving.TakenAwaySection
import app.kinframe.family.ui.leaving.TakenAwayViewModel
import app.kinframe.family.ui.leaving.resultText
import app.kinframe.family.ui.servers.AccountRow
import app.kinframe.family.ui.servers.AccountsSection
import app.kinframe.family.ui.servers.FeedRemoteSection
import app.kinframe.family.ui.servers.FeedRemoteViewModel
import app.kinframe.family.ui.servers.RemoteFollowsPage
import app.kinframe.family.ui.servers.RemoteFollowsSection
import app.kinframe.family.ui.servers.RemoteFollowsViewModel
import app.kinframe.family.ui.servers.ServersNotice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/*
 * Leaving a family (docs/spec/leaving.md) and families on other servers (docs/spec/multi-server.md): the routes and
 * the slots the existing screens show them in. Everything is hidden while discovery `leaving` / `remote_families` of
 * the server in question is false.
 */

private fun k(o: Any?) = System.identityHashCode(o)

/** Discovery `leaving` on the server [key] (a frame or family id) belongs to. */
fun leavingOn(graph: AppGraph, key: String?): Boolean = graph.discoveryFor(key)?.leaving == true

/** Discovery `remote_families` on the server [key] belongs to. */
fun remoteOn(graph: AppGraph, key: String?): Boolean = graph.discoveryFor(key)?.remoteFamilies == true

@Composable
private fun LeavingNotices(events: SharedFlow<LeavingNotice>, onDone: (LeavingNotice.Done) -> Unit = {}) {
    val toaster = LocalToaster.current
    val res = LocalResources.current
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is LeavingNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is LeavingNotice.Done -> { toaster.show(resultText(res, e.result)); onDone(e) }
            }
        }
    }
}

@Composable
private fun ServersNotices(events: SharedFlow<ServersNotice>) {
    val toaster = LocalToaster.current
    val res = LocalResources.current
    val context = LocalContext.current
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is ServersNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is ServersNotice.ShareInvite -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, res.getString(R.string.servers_feed_invite_text, e.invite.url))
                    }
                    context.startActivity(Intent.createChooser(send, res.getString(R.string.servers_feed_invite)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

/** Every leaving and other-server screen. */
@Composable
fun LeavingRouteScreen(graph: AppGraph, nav: Navigator, r: LeavingRoute) {
    when (r) {
        is MemoriesRoute -> {
            val account = graph.accounts.byId(r.accountId) ?: graph.accounts.primary.collectAsStateWithLifecycle().value
            val route = account.route()
            val api = route?.leaving ?: return
            val social = route.social
            val vm: MemoriesViewModel = viewModel(key = "memories-${account.id}-${k(api)}") {
                MemoriesViewModel(api) { social?.contacts()?.map { it.person }.orEmpty() }
            }
            val s by vm.state.collectAsStateWithLifecycle()
            LeavingNotices(vm.events)
            MemoriesScreen(s, vm) { nav.pop() }
        }
        is KeepRequestsRoute -> {
            val vm: KeepRequestsViewModel = viewModel(key = "keep-requests-${k(graph.api.collectAsStateWithLifecycle().value)}") { KeepRequestsViewModel(graph.leaving) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(r.requestId) { r.requestId?.let(vm::open) }
            LaunchedEffect(vm) { graph.live.events.collect { e -> if (e is LeavingLive.KeepRequestUpdated) vm.onUpdated(e.request) } }
            LeavingNotices(vm.events)
            KeepRequestsScreen(s, vm) { nav.pop() }
        }
        FormerPlacesRoute -> {
            val vm: FormerPlacesViewModel = viewModel(key = "former-${k(graph.api.collectAsStateWithLifecycle().value)}") { FormerPlacesViewModel(graph.leaving) }
            val s by vm.state.collectAsStateWithLifecycle()
            LeavingNotices(vm.events)
            FormerPlacesScreen(s, vm) { nav.pop() }
        }
        is RemoteFollowRoute -> {
            val api = graph.remoteFor(r.frameId) ?: return
            val vm: RemoteFollowsViewModel = viewModel(key = "remote-follow-${r.frameId}-${k(api)}") {
                RemoteFollowsViewModel(api, r.frameId).also { v -> r.inviteUrl?.let(v::start) }
            }
            val s by vm.state.collectAsStateWithLifecycle()
            ServersNotices(vm.events)
            RemoteFollowsPage(s, vm, "", onBack = { nav.pop() })
        }
    }
}

/** Settings: Accounts (always: anyone can add a server) and, with `leaving`, Memories, keep requests, places you left. */
@Composable
fun LeavingSettingsSlot(graph: AppGraph, nav: Navigator) {
    val accounts by graph.accounts.accounts.collectAsStateWithLifecycle()
    val version by graph.accounts.version.collectAsStateWithLifecycle()
    val primary by graph.accounts.primary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rows = androidx.compose.runtime.remember(accounts, version, primary) {
        accounts.map { a -> AccountRow(a.id, a.label, a.host, a.me.value?.displayName, a.id == primary.id, a.signedIn) }
    }
    AccountsSection(rows,
        onAdd = { graph.addServer.value = AddServerRequest() },
        onPrimary = { graph.accounts.setPrimary(it) },
        onSignOut = { id -> graph.accounts.byId(id)?.let { a -> scope.launch { graph.signOut(null, a) } } },
    )
    if (accounts.any { it.route()?.leaving != null }) {
        var open by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        LaunchedEffect(version) { open = runCatching { graph.leaving.myKeepRequests().requests.count { it.isOpen } }.getOrDefault(0) }
        LeavingSettingsSection(open,
            onMemories = { nav.push(MemoriesRoute(accounts.firstOrNull { it.route()?.leaving != null }?.id)) },
            onKeepRequests = { nav.push(KeepRequestsRoute()) },
            onFormerPlaces = { nav.push(FormerPlacesRoute) })
        // Memories are per server: one entry per other account with leaving.
        accounts.drop(1).filter { it.route()?.leaving != null }.forEach { a ->
            QuietButton(LocalResources.current.getString(R.string.leaving_memories_on, a.label), { nav.push(MemoriesRoute(a.id)) })
        }
    }
}

/** The family leave sheet (leaving.md story 1), or null without `leaving` (the old dialog then shows). */
fun feedLeaveSheet(graph: AppGraph, feedId: String, onGone: () -> Unit): (@Composable (() -> Unit) -> Unit)? {
    if (!leavingOn(graph, feedId)) return null
    return { onDismiss ->
        val vm: LeaveSheetViewModel = viewModel(key = "leave-feed-$feedId") { LeaveSheetViewModel(graph.leaving, feedId, null) }
        val s by vm.state.collectAsStateWithLifecycle()
        LeavingNotices(vm.events) { onDismiss(); onGone() }
        LeaveSheet(s, family = true, vm::setPhotos, vm::setComments, vm::toggleFrame, { vm.load() }, vm::leave, onDismiss)
    }
}

/** The frame leave sheet (leaving.md story 6), or null without `leaving`. */
fun frameLeaveSheet(graph: AppGraph, frameId: String, onGone: () -> Unit): (@Composable (() -> Unit) -> Unit)? {
    if (!leavingOn(graph, frameId)) return null
    return { onDismiss ->
        val vm: LeaveSheetViewModel = viewModel(key = "leave-frame-$frameId") { LeaveSheetViewModel(graph.leaving, null, frameId) }
        val s by vm.state.collectAsStateWithLifecycle()
        LeavingNotices(vm.events) { onDismiss(); onGone() }
        LeaveSheet(s, family = false, vm::setPhotos, vm::setComments, vm::toggleFrame, { vm.load() }, vm::leave, onDismiss)
    }
}

/** "Taken away by Tom" in a frame's trash (managers), with "Ask Tom to keep them". */
@Composable
fun FrameTakenAway(graph: AppGraph, frame: Frame) {
    if (!leavingOn(graph, frame.id) || !frame.can { it.manageLibrary }) return
    val vm: TakenAwayViewModel = viewModel(key = "taken-frame-${frame.id}-${k(graph.api.collectAsStateWithLifecycle().value)}") { TakenAwayViewModel(graph.leaving, frame.id, null) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        graph.live.events.collect { e ->
            if ((e is LeavingLive.PlacementUpdated && e.frameId == frame.id) || e is LeavingLive.KeepRequestUpdated) vm.load()
        }
    }
    LeavingNotices(vm.events)
    TakenAwaySection(s, vm)
}

/** Family settings: photos taken away from the family, and frames on other servers. */
@Composable
fun FeedLeavingSettings(graph: AppGraph, feed: Feed, onFeed: (Feed) -> Unit) {
    if (!feed.isAdmin) return
    if (leavingOn(graph, feed.id)) {
        val vm: TakenAwayViewModel = viewModel(key = "taken-feed-${feed.id}-${k(graph.api.collectAsStateWithLifecycle().value)}") { TakenAwayViewModel(graph.leaving, null, feed.id) }
        val s by vm.state.collectAsStateWithLifecycle()
        LeavingNotices(vm.events)
        TakenAwaySection(s, vm)
    }
    val remote = graph.remoteFor(feed.id) ?: return
    val rvm: FeedRemoteViewModel = viewModel(key = "feed-remote-${feed.id}-${k(remote)}") { FeedRemoteViewModel(remote, feed.id, feed.allowRemoteFrames, onFeed) }
    val rs by rvm.state.collectAsStateWithLifecycle()
    ServersNotices(rvm.events)
    FeedRemoteSection(rs, rvm)
}

/** Frame → Families: families on other servers this frame follows, "Add a family from another server". */
@Composable
fun FrameRemoteFollows(graph: AppGraph, frame: Frame) {
    val remote = graph.remoteFor(frame.id) ?: return
    val vm: RemoteFollowsViewModel = viewModel(key = "remote-follows-${frame.id}-${k(remote)}") { RemoteFollowsViewModel(remote, frame.id) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { graph.live.events.collect { e -> if (e is LeavingLive.RemoteSubscriptionUpdated) vm.replace(e.subscription) } }
    ServersNotices(vm.events)
    RemoteFollowsSection(s, vm, frame.name, canManage = frame.can { it.changeSettings })
}

/** A leaving push was tapped (README §6.3): the trash, the family, or the keep request. */
fun openLeavingTarget(t: OpenTarget, nav: Navigator): Boolean {
    if (t.target != LeavingNotifications.TARGET) return false
    when (t.ids["leaving_key"]) {
        LeavingPush.KEEP_REQUEST -> { nav.selectTab(Route.Settings); nav.push(KeepRequestsRoute(t.ids["request_id"])) }
        else -> when {
            t.frameId != null -> { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(t.frameId, FrameTab.Library)) }
            t.feedId != null -> { nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(t.feedId, FeedTab.Settings)) }
            else -> nav.selectTab(Route.Settings)
        }
    }
    return true
}

/** Placements taken away or restored: the library reloads (README §6.2 `placement.updated`). */
fun isLibraryChange(e: app.kinframe.family.data.LiveEvent, frameId: String): Boolean =
    e is LeavingLive.PlacementUpdated && e.frameId == frameId && e.reason in setOf(PlacementUpdateReason.TAKEN_AWAY, PlacementUpdateReason.RESTORED, PlacementUpdateReason.PURGED)
