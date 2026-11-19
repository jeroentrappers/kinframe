package app.kinframe.family.ui

import app.kinframe.family.ui.channels.ChannelRoute
import app.kinframe.family.ui.channels.TelegramLinkSettings
import app.kinframe.family.ui.sources.*
import app.kinframe.core.api.PhotoSourceLinks
import app.kinframe.core.api.SourceLink
import app.kinframe.core.api.Role as ApiRole
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinframe.core.api.CommandType
import app.kinframe.core.api.Invite
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Limits
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.AppGraph
import app.kinframe.family.BuildConfig
import app.kinframe.family.DebugLaunch
import app.kinframe.family.R
import app.kinframe.family.auth.AuthMode
import app.kinframe.family.notify.Notifications
import app.kinframe.family.send.MediaFiles
import app.kinframe.family.send.OutboxState
import app.kinframe.family.send.SendRunner
import app.kinframe.family.send.SendWorker
import app.kinframe.family.ui.calendar.CalendarActions
import app.kinframe.family.ui.tv.TvDialog
import app.kinframe.family.ui.tv.TvDialogActions
import app.kinframe.family.ui.tv.TvMiniBar
import app.kinframe.family.ui.tv.TvNotice
import app.kinframe.family.ui.tv.TvPresenter
import app.kinframe.family.ui.tv.TvScope
import app.kinframe.family.ui.tv.TvViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import app.kinframe.family.ui.calendar.CalendarNotice
import app.kinframe.family.ui.calendar.CalendarTab
import app.kinframe.family.ui.calendar.CalendarViewModel
import app.kinframe.family.ui.frame.FrameActions
import app.kinframe.family.ui.frame.FrameDetailScreen
import app.kinframe.family.ui.frame.FrameDetailViewModel
import app.kinframe.family.ui.frame.FrameEvent
import app.kinframe.family.ui.frame.FrameSettingsViewModel
import app.kinframe.family.ui.frame.LibraryEvent
import app.kinframe.family.ui.frame.LibraryTab
import app.kinframe.family.ui.frame.LibraryViewModel
import app.kinframe.family.ui.frame.PluginsEvent
import app.kinframe.family.ui.frame.PluginsSection
import app.kinframe.family.ui.frame.PluginsViewModel
import app.kinframe.family.ui.share.ShareEvent
import app.kinframe.family.ui.share.ShareSheet
import app.kinframe.family.ui.share.ShareSource
import app.kinframe.family.ui.share.ShareViewModel
import app.kinframe.family.ui.share.Sharing
import app.kinframe.family.ui.frame.SettingsEvent
import app.kinframe.family.ui.frame.SettingsTab
import app.kinframe.core.api.FeedCodes
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.family.ui.feeds.FeedActions
import app.kinframe.family.ui.feeds.FeedDetailScreen
import app.kinframe.family.ui.feeds.FeedDetailViewModel
import app.kinframe.family.ui.feeds.FeedInviteScreen
import app.kinframe.family.ui.feeds.FeedInviteViewModel
import app.kinframe.family.ui.feeds.FeedNotice
import app.kinframe.family.ui.feeds.FeedsScreen
import app.kinframe.family.ui.feeds.FeedsViewModel
import app.kinframe.family.ui.feeds.FrameFeedsTab
import app.kinframe.family.ui.feeds.FrameFeedsViewModel
import app.kinframe.family.ui.feeds.PostToFeedDialog
import app.kinframe.family.ui.frames.AddFrameScreen
import app.kinframe.family.ui.frames.AddFrameViewModel
import app.kinframe.family.ui.frames.FramesScreen
import app.kinframe.family.ui.frames.FramesViewModel
import app.kinframe.family.ui.frames.InviteScreen
import app.kinframe.family.ui.frames.InviteViewModel
import app.kinframe.family.ui.history.HistoryScreen
import app.kinframe.family.ui.history.HistoryViewModel
import app.kinframe.family.ui.history.SendDetailEvent
import app.kinframe.family.ui.history.SendDetailScreen
import app.kinframe.family.ui.history.SendDetailViewModel
import app.kinframe.family.ui.onboarding.NameScreen
import app.kinframe.family.ui.onboarding.OnboardingDeps
import app.kinframe.family.ui.onboarding.OnboardingViewModel
import app.kinframe.family.ui.onboarding.ServerScreen
import app.kinframe.family.ui.onboarding.SignInReason
import app.kinframe.family.ui.onboarding.SignInScreen
import app.kinframe.family.ui.onboarding.WelcomeScreen
import app.kinframe.family.ui.send.ComposeScreen
import app.kinframe.family.ui.send.ComposerEvent
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.send.ItemEditor
import app.kinframe.family.ui.settings.SettingsScreen
import app.kinframe.family.util.DeepLink
import app.kinframe.family.util.Problems
import app.kinframe.family.update.UpdateState
import app.kinframe.family.update.openStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Who may open "Show on TV" for what (web `TvDialog` props). */
data class TvTarget(val frameId: String, val frameName: String, val canMirror: Boolean, val canRemote: Boolean, val scope: TvScope)

/** Opens the "Show on TV" dialog from any screen. */
val LocalShowOnTv = staticCompositionLocalOf<(TvTarget) -> Unit> { {} }

fun tvTarget(f: app.kinframe.core.api.Frame, scope: TvScope) =
    TvTarget(f.id, f.name, canMirror = f.can { it.seeLibrary }, canRemote = f.can { it.changeSettings }, scope = scope)

@Composable
fun FamilyRoot(graph: AppGraph, app: AppViewModel) {
    val phase by app.phase.collectAsStateWithLifecycle()
    val switch by app.switchPrompt.collectAsStateWithLifecycle()
    val update by graph.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Below the server's minimum build (or after a 426) nothing else is usable (README §5.15).
    (update as? UpdateState.Required)?.let { required ->
        val checking by graph.updates.checking.collectAsStateWithLifecycle()
        UpdateRequiredScreen(checking, onUpdate = { context.openStore(required.storeUrl) }, onCheckAgain = graph.updates::checkAgain)
        return
    }
    Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        AnimatedContent(phase, contentKey = { it::class }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { target ->
            when (val p = target) {
                Phase.NoServer, is Phase.SignedOut -> Onboarding(graph, p)
                Phase.Loading -> Box(Modifier.fillMaxSize()) { CenteredSpinner() }
                is Phase.ProfileError -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Column(Modifier.padding(top = 120.dp)) {
                        ErrorPanel(p.message, app::retryProfile)
                        QuietButton(stringResource(R.string.settings_sign_out), { app.signOut(null) }, Modifier.padding(horizontal = 24.dp))
                    }
                }
                Phase.NeedsName -> {
                    val ps by app.profileState.collectAsStateWithLifecycle()
                    NameScreen(ps.nameDraft, ps.savingName, ps.nameError, app::onNameDraft, app::saveName)
                }
                is Phase.Ready -> Home(graph, app, p)
            }
        }
    }
    switch?.let { link ->
        AlertDialog(
            onDismissRequest = app::dismissSwitch,
            title = { Text(stringResource(R.string.server_switch_title, link.server.host)) },
            text = { Text(stringResource(R.string.server_switch_body)) },
            confirmButton = { TextButton(app::confirmSwitch) { Text(stringResource(R.string.server_switch_confirm)) } },
            dismissButton = { TextButton(app::dismissSwitch) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Onboarding(graph: AppGraph, phase: Phase) {
    val context = LocalContext.current
    val res = LocalResources.current
    val vm: OnboardingViewModel = viewModel(key = "onboarding") {
        OnboardingViewModel(OnboardingDeps(
            check = { graph.servers.check(it) },
            choose = { graph.servers.choose(it) },
            signInLogto = { graph.auth.signInWithLogto(it) },
            signInDev = { e, n -> graph.auth.signInDev(e, n) },
        ))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val expired by graph.auth.expired.collectAsStateWithLifecycle()
    val pending by graph.pendingLink.collectAsStateWithLifecycle()
    when {
        phase is Phase.SignedOut -> SignInScreen(
            phase.server, state, expired,
            when (pending) { is DeepLink.FrameCode -> SignInReason.Frame; is DeepLink.Invite, is DeepLink.FeedInvite -> SignInReason.Invite; null -> SignInReason.None },
            onSignIn = { context.findActivity()?.let(vm::signIn) },
            onDevEmail = vm::onDevEmail, onDevName = vm::onDevName, onDevSignIn = vm::signInDev,
            onChangeServer = { graph.servers.clear() },
        )
        state.customOpen -> {
            BackHandler { vm.openCustom(false) }
            ServerScreen(state, vm::onCustomInput, vm::checkCustom, vm::useCustom) { vm.openCustom(false) }
        }
        else -> WelcomeScreen(state, vm::useDefault) { vm.openCustom(true) }
    }
}

private data class TabSpec(val route: Route, val icon: ImageVector, val label: Int)

/** The bottom tabs; Feeds only when the server has them (discovery `feeds`); Home and Chats with family social. */
private fun tabs(feeds: Boolean, social: SocialFlags = SocialFlags(false, false, false, false, false)) = listOfNotNull(
    if (social.social) TabSpec(HomeRoute, KinIcons.Home, R.string.social_nav_home) else null,
    TabSpec(Route.Frames, KinIcons.Frame, R.string.nav_frames),
    TabSpec(Route.Send, KinIcons.Send, R.string.nav_send),
    TabSpec(Route.History, KinIcons.History, R.string.nav_history),
    if (social.chats) TabSpec(ChatsRoute, KinIcons.Message, R.string.chat_nav) else null,
    if (feeds) TabSpec(Route.Feeds, KinIcons.People, R.string.nav_feeds) else null,
    TabSpec(Route.Settings, KinIcons.Settings, R.string.nav_settings),
)

/** The tab's badge: unread chats on Chats, threads with mentions or replies to you on Home (README §5.19). */
private fun tabBadge(route: Route, b: app.kinframe.core.social.Badge): Int = when (route) {
    ChatsRoute -> b.conversations
    HomeRoute -> b.threads
    else -> 0
}

@Composable
private fun Home(graph: AppGraph, app: AppViewModel, ready: Phase.Ready) {
    val api = ready.api
    val context = LocalContext.current
    val res = LocalResources.current
    val socialFlags = remember(ready.server.discovery) { SocialFlags.of(ready.server.discovery) }
    val nav = remember(api) { Navigator(if (socialFlags.social) HomeRoute else Route.Frames) }
    val homeTab: Route = if (socialFlags.social) HomeRoute else Route.Frames
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toaster = remember { Toaster(snackbar, scope) }
    val k = System.identityHashCode(api)
    val feedsOn = ready.server.discovery.feeds
    val TABS = remember(feedsOn, socialFlags) { tabs(feedsOn, socialFlags) }
    // A phone's bottom bar holds five: with Home and Chats, History and Settings move to the top bar of those tabs.
    val BAR = remember(TABS) { if (TABS.size <= 5) TABS else TABS.filter { it.route != Route.History && it.route != Route.Settings } }
    val badge by graph.social.badge.collectAsStateWithLifecycle()

    val frames: FramesViewModel = viewModel(key = "frames-$k") { FramesViewModel(api, graph.live.events) }
    val feeds: FeedsViewModel = viewModel(key = "feeds-$k") { FeedsViewModel(api, graph.live.events) }
    val composer: ComposerViewModel = viewModel(key = "composer-$k") {
        ComposerViewModel(
            api = { graph.api.value }, limits = { graph.servers.current.value?.limits ?: Limits() },
            server = { graph.servers.current.value?.url?.toString().orEmpty() },
            inspect = { uri -> withContext(Dispatchers.IO) { MediaFiles.inspect(context.applicationContext, uri) } },
            enqueue = { send -> queueSend(graph, send) },
            feedsEnabled = { graph.servers.current.value?.discovery?.feeds == true },
            social = { graph.social.api.value?.takeIf { graph.social.socialOn } },
        )
    }
    val history: HistoryViewModel = viewModel(key = "history-$k") { HistoryViewModel(api, graph.live.events, graph.sent) }
    // "Share to another frame" from the library and the history: one picker sheet for the whole app.
    val share: ShareViewModel = viewModel(key = "share-$k") { ShareViewModel(api) }
    val shareState by share.state.collectAsStateWithLifecycle()
    LaunchedEffect(share) {
        share.events.collect { e ->
            when (e) {
                is ShareEvent.Shared -> toaster.show(res.getString(R.string.share_done, e.frames.joinToString(", ")), res.getString(R.string.common_undo), e.undo)
                ShareEvent.NothingNew -> toaster.show(res.getString(R.string.share_nothing_new))
                ShareEvent.Undone -> toaster.show(res.getString(R.string.share_undone))
                is ShareEvent.Error -> toaster.show(res.getString(e.message))
            }
        }
    }

    BackHandler(enabled = nav.stack.size > 1 || nav.tab != homeTab) { if (!nav.pop()) nav.selectTab(homeTab) }

    // Incoming: share sheet, links, notification taps.
    val shared by graph.incomingShare.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) { nav.selectTab(Route.Send); composer.add(shared); graph.incomingShare.value = emptyList() }
    }
    val link by graph.pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        when (val l = link) {
            is DeepLink.FrameCode -> nav.push(Route.AddFrame(l.code))
            is DeepLink.Invite -> nav.push(Route.Invite(l.token))
            is DeepLink.FeedInvite -> if (feedsOn) nav.push(Route.FeedInvite(l.token))
            null -> Unit
        }
        graph.pendingLink.value = null
    }
    val target by graph.openTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        graph.openTarget.value = null
        // Family social pushes (comments, chats, reminders…): ui/SocialRoot.kt.
        if (openSocialTarget(t, nav, ready)) return@LaunchedEffect
        when (t.target) {
            Notifications.TARGET_PEOPLE -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it, FrameTab.People)) }
            Notifications.TARGET_FRAME -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it)) }
            Notifications.TARGET_CALENDAR -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it, FrameTab.Calendar)) }
            Notifications.TARGET_FRAME_FEEDS -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it, FrameTab.Feeds)) }
            // A photo source needs its owner: My photo sources shows it with the fix (photo-sources.md §10).
            Notifications.TARGET_PHOTO_SOURCES -> { nav.selectTab(Route.Settings); nav.push(Route.PhotoSources) }
            Notifications.TARGET_GOOGLE_IMPORT -> t.importId?.let { graph.googleImports.resume(it) }
            // Someone new mailed an address you manage: its pending list (email-inbox.md §11).
            Notifications.TARGET_EMAIL_PENDING -> t.addressId?.let {
                if (!ready.server.discovery.emailInbox) return@let
                if (t.feedId != null && feedsOn) nav.selectTab(Route.Feeds) else nav.selectTab(Route.Frames)
                nav.push(Route.EmailAddress(it))
            }
            // A Telegram chat needs its owner (telegram.md §15.1).
            Notifications.TARGET_TELEGRAM_CHAT -> t.chatId?.let {
                if (!ready.server.discovery.telegram) return@let
                if (t.feedId != null && feedsOn) nav.selectTab(Route.Feeds) else nav.selectTab(Route.Frames)
                nav.push(Route.TelegramChat(it))
            }
            Notifications.TARGET_FEED, Notifications.TARGET_FEED_PEOPLE, Notifications.TARGET_FEED_FRAMES, Notifications.TARGET_FEED_BIRTHDAYS -> t.feedId?.let {
                if (!feedsOn) return@let
                nav.selectTab(Route.Feeds)
                val tab = when (t.target) {
                    Notifications.TARGET_FEED_PEOPLE -> FeedTab.People
                    Notifications.TARGET_FEED_FRAMES -> FeedTab.Frames
                    Notifications.TARGET_FEED_BIRTHDAYS -> if (ready.server.discovery.birthdays) FeedTab.Birthdays else FeedTab.Posts
                    else -> FeedTab.Posts
                }
                nav.push(Route.FeedDetail(it, tab, t.postId))
            }
            else -> {
                nav.selectTab(Route.History)
                val sendId = t.sendId ?: t.placementId?.let { history.sendWithPlacement(it) }
                sendId?.let { nav.push(Route.SendDetail(it)) }
            }
        }
    }
    AskForNotifications()
    PhotoSourcesHome(graph, ready, nav)
    if (BuildConfig.DEBUG) DebugLaunch.Effect(nav, composer)
    // Someone asks to join a frame you manage while the app is open (web: a toast with Review).
    LaunchedEffect(graph) {
        graph.live.events.collect { e ->
            if (e !is app.kinframe.family.data.LiveEvent.JoinRequested) return@collect
            val f = (frames.state.value.frames as? Load.Ready)?.value?.firstOrNull { it.id == e.frameId } ?: return@collect
            if (!f.isManager) return@collect
            toaster.show(res.getString(R.string.live_join_request, e.request?.user?.displayName.orEmpty(), f.name), res.getString(R.string.common_review)) {
                nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(f.id, FrameTab.People))
            }
        }
    }
    // Feed requests while the app is open: a frame asks to subscribe, someone asks to join.
    if (feedsOn) LaunchedEffect(graph) {
        graph.live.events.collect { e ->
            val feedId = when (e) {
                is app.kinframe.family.data.LiveEvent.SubscriptionRequested -> e.feedId
                is app.kinframe.family.data.LiveEvent.FeedJoinRequested -> e.feedId
                else -> return@collect
            }
            val feed = (feeds.state.value.feeds as? Load.Ready)?.value?.firstOrNull { it.id == feedId } ?: return@collect
            if (!feed.isAdmin) return@collect
            val (msg, tab) = when (e) {
                is app.kinframe.family.data.LiveEvent.SubscriptionRequested ->
                    res.getString(R.string.live_subscription_request, e.subscription.frame.name, feed.name) to FeedTab.Frames
                is app.kinframe.family.data.LiveEvent.FeedJoinRequested ->
                    res.getString(R.string.live_feed_join_request, e.request?.user?.displayName.orEmpty(), feed.name) to FeedTab.People
                else -> return@collect
            }
            toaster.show(msg, res.getString(R.string.common_review)) { nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(feed.id, tab)) }
        }
    }

    LaunchedEffect(composer) {
        composer.events.collect { e ->
            when (e) {
                is ComposerEvent.Queued -> { toaster.show(res.getString(R.string.send_sent)); nav.selectTab(Route.History) }
                is ComposerEvent.CaptionApplied -> toaster.show(res.getQuantityString(R.plurals.send_applied_all, e.count, e.count))
            }
        }
    }

    // Show on TV: the presenter lives in the graph, so a show keeps going while the person moves around the app.
    val tvState by graph.tv.state.collectAsStateWithLifecycle()
    var tvOpen by remember { mutableStateOf<TvTarget?>(null) }
    val activity = context.findActivity()
    DisposableEffect(activity) {
        activity?.let { graph.tv.attach(it) }
        onDispose { activity?.let { graph.tv.detach(it) } }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> composer.add(uris) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    CompositionLocalProvider(LocalToaster provides toaster, LocalShowOnTv provides { tvOpen = it }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val showChrome = nav.stack.size == 1
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = Kin.colors.surface, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
                        TABS.forEach { t ->
                            NavigationRailItem(
                                selected = nav.tab == t.route, onClick = { nav.selectTab(t.route) },
                                icon = { BadgedIcon(t.icon, tabBadge(t.route, badge)) }, label = { Text(stringResource(t.label)) },
                                colors = NavigationRailItemDefaults.colors(selectedIconColor = Kin.colors.onAccentSoft, indicatorColor = Kin.colors.accentSoft,
                                    selectedTextColor = Kin.colors.ink, unselectedIconColor = Kin.colors.inkSoft, unselectedTextColor = Kin.colors.inkSoft),
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = Kin.colors.paper,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!wide && showChrome) {
                            NavigationBar(containerColor = Kin.colors.surface) {
                                BAR.forEach { t ->
                                    NavigationBarItem(
                                        selected = nav.tab == t.route, onClick = { nav.selectTab(t.route) },
                                        icon = { BadgedIcon(t.icon, tabBadge(t.route, badge)) }, label = { Text(stringResource(t.label), maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Kin.colors.onAccentSoft, indicatorColor = Kin.colors.accentSoft,
                                            selectedTextColor = Kin.colors.ink, unselectedIconColor = Kin.colors.inkSoft, unselectedTextColor = Kin.colors.inkSoft),
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        val update by graph.updates.state.collectAsStateWithLifecycle()
                        (update as? UpdateState.Available)?.takeIf { showChrome }?.let { available ->
                            UpdateBanner(onUpdate = { context.openStore(available.storeUrl) }, onDismiss = graph.updates::dismiss)
                        }
                        TvMiniBar(
                            tvState,
                            onOpen = { tvState.frameId?.let { id -> tvOpen = TvTarget(id, tvState.frameName, true, false, TvScope.All) } },
                            onPrevious = graph.tv::previous, onToggle = graph.tv::toggle, onNext = graph.tv::next,
                            onStop = { graph.tv.stop(); toaster.show(res.getString(R.string.tv_stopped)) },
                        )
                        val overflow: @Composable () -> Unit = if (!wide && BAR.size < TABS.size) ({
                            OverflowActions({ nav.selectTab(Route.History) }, { nav.selectTab(Route.Settings) })
                        }) else ({})
                        Box(Modifier.weight(1f)) { Screen(graph, app, ready, nav, frames, composer, history, pick, feeds, overflow) }
                    }
                    tvOpen?.let { t -> TvRoute(graph, api, t, ready, tvState, { tvOpen = null }, frames) }
                    ShareSheet(shareState, share::toggle, share::confirm, share::dismiss, share::retry)
                }
            }
        }
    }
}

@Composable
private fun Screen(
    graph: AppGraph,
    app: AppViewModel,
    ready: Phase.Ready,
    nav: Navigator,
    frames: FramesViewModel,
    composer: ComposerViewModel,
    history: HistoryViewModel,
    pick: () -> Unit,
    feeds: FeedsViewModel,
    overflow: @Composable () -> Unit = {},
) {
    val api = ready.api
    val feedsOn = ready.server.discovery.feeds
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    when (val r = nav.current) {
        Route.Frames -> {
            val s by frames.state.collectAsStateWithLifecycle()
            FramesScreen(s, frames::refresh, { nav.push(Route.FrameDetail(it.id)) }, { nav.push(Route.AddFrame()) }) { f ->
                composer.preselectFrame(f.id); nav.selectTab(Route.Send)
            }
        }
        Route.Send -> {
            val s by composer.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { composer.loadFrames() }
            val editing = s.editing
            if (editing != null) {
                BackHandler { composer.edit(null) }
                ItemEditor(
                    s, editing, composer::edit, { composer.edit(null) },
                    composer::setCaption, composer::applyCaptionToAll, composer::setFill, composer::setFocal,
                    composer::setTrim, composer::setIncludeAudio, composer::remove, composer::setSendLive,
                )
            } else {
                ComposeScreen(s, pick, composer::edit, composer::remove, composer::clear, composer::toggleFrame, composer::send,
                    { nav.selectTab(Route.Frames); nav.push(Route.AddFrame()) }, { composer.loadFrames() }, composer::toggleFeed,
                    audience = if (s.circles.isNotEmpty() || s.prompts.isNotEmpty()) ({ SendAudience(s, composer) }) else null)
            }
        }
        Route.History -> {
            val s by history.state.collectAsStateWithLifecycle()
            val outbox by graph.outbox.sends.collectAsStateWithLifecycle()
            val progress by graph.outbox.progress.collectAsStateWithLifecycle()
            HistoryScreen(
                s, outbox.filter { it.chat == null }.sortedByDescending { it.createdAt }, progress, history::setFilter, { nav.push(Route.SendDetail(it.id)) },
                history::refresh, history::loadMore,
                onRetryOutbox = { id -> graph.outbox.update(id) { it.copy(state = OutboxState.QUEUED, error = null) }; SendWorker.enqueue(context, id) },
                onDiscardOutbox = { id -> discardOutbox(graph, context, id) },
            )
        }
        Route.Settings -> {
            val push by graph.push.status.collectAsStateWithLifecycle()
            val ps by app.profileState.collectAsStateWithLifecycle()
            val fs by frames.state.collectAsStateWithLifecycle()
            var deletingAccount by remember { mutableStateOf(false) }
            var allowed by remember { mutableStateOf(Notifications.canPost(context)) }
            val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
            val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) app.setAvatar(uri) { err -> toaster.show(res.getString(err ?: R.string.settings_avatar_saved)) }
            }
            SettingsScreen(
                ready.me, ready.server, graph.auth.mode() == AuthMode.Dev, push, allowed, ps.savingName,
                onSaveName = { app.onNameDraft(it); app.saveName(); toaster.show(res.getString(R.string.settings_name_saved)) },
                onChoosePush = { context.findActivity()?.let { graph.push.choose(it) } },
                onAllowNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                onSignOut = { app.signOut(context.findActivity()) },
                onChangeServer = { app.changeServer() },
                onChangePhoto = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemovePhoto = { app.removeAvatar { err -> toaster.show(res.getString(err ?: R.string.common_saved)) } },
                savingAvatar = ps.savingAvatar,
                onLanguage = { tag -> app.setLanguage(tag) { err -> toaster.show(res.getString(err ?: R.string.common_saved)) } },
                onOpenWeb = { path ->
                    val web = ready.server.discovery.web ?: ready.server.url.toString()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web.trimEnd('/') + path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                onPhotoSources = if (ready.server.discovery.sourceProviders.isNotEmpty()) ({ nav.push(Route.PhotoSources) }) else null,
                telegram = if (ready.server.discovery.telegram) ({ TelegramLinkSettings(graph, ready) }) else null,
                social = { SocialSettingsSection(ready, nav) },
                ownedFrames = (fs.frames as? Load.Ready)?.value.orEmpty().filter { it.role == ApiRole.OWNER }.map { it.name },
                onDeleteAccount = { removeItems ->
                    deletingAccount = true
                    app.deleteAccount(removeItems) { err -> deletingAccount = false; toaster.show(res.getString(err)) }
                },
                deletingAccount = deletingAccount,
            )
        }
        is Route.AddFrame -> {
            val vm: AddFrameViewModel = viewModel(key = "add-${r.code}-${System.identityHashCode(r)}") { AddFrameViewModel(api, r.code, feedsOn) }
            val s by vm.state.collectAsStateWithLifecycle()
            AddFrameScreen(
                s, vm::onInput, vm::submit, { nav.pop() }, vm::onName, vm::onLocation,
                onSaveName = {
                    vm.saveName { f ->
                        frames.upsert(f); toaster.show(res.getString(R.string.add_named, f.name))
                        composer.preselectFrame(f.id); nav.selectTab(Route.Send)
                    }
                },
                onSend = { id -> frames.refresh(); composer.preselectFrame(id); nav.selectTab(Route.Send) },
                onViewFrame = { id -> frames.refresh(); nav.replace(Route.FrameDetail(id)) },
                onDone = { frames.refresh(); nav.selectTab(Route.Frames) },
                onFeedInvite = { token -> vm.feedInviteOpened(); nav.push(Route.FeedInvite(token)) },
            )
        }
        is Route.Invite -> {
            val vm: InviteViewModel = viewModel(key = "invite-${r.token}") { InviteViewModel(api, r.token) }
            val s by vm.state.collectAsStateWithLifecycle()
            InviteScreen(s, vm::accept, { vm.load() }, { nav.pop() },
                onSend = { id -> frames.refresh(); composer.preselectFrame(id); nav.selectTab(Route.Send) },
                onViewFrame = { id -> frames.refresh(); nav.replace(Route.FrameDetail(id)) })
        }
        is Route.SendDetail -> {
            val vm: SendDetailViewModel = viewModel(key = "send-${r.sendId}") { SendDetailViewModel(api, r.sendId, graph.live.events) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { e ->
                    when (e) {
                        SendDetailEvent.CaptionSaved -> toaster.show(res.getString(R.string.detail_caption_saved))
                        SendDetailEvent.Unsent -> { toaster.show(res.getString(R.string.detail_unsent)); history.removeLocally(r.sendId); nav.pop() }
                        is SendDetailEvent.Forwarded -> toaster.show(res.getString(R.string.detail_posted_to_feed, e.feeds.joinToString(", ")))
                    }
                }
            }
            val share: ShareViewModel = viewModel(key = "share-${System.identityHashCode(api)}") { ShareViewModel(api) }
            val fs by frames.state.collectAsStateWithLifecycle()
            val send = (s.send as? Load.Ready)?.value
            val source = send?.let { Sharing.source(it, it.items.filter { i -> i.status != "failed" }.map { i -> i.id }) }
            val canShare = source != null && Sharing.targets((fs.frames as? Load.Ready)?.value.orEmpty(), source.frameId).isNotEmpty()
            // Show on TV: the send's live placements on the first frame that has them (web history detail).
            val showOnTv = LocalShowOnTv.current
            val tvFrame = send?.frames?.firstOrNull { f -> f.placements.any { it.status != "removed" && it.status != "failed" } }
            val tvIds = tvFrame?.placements?.filter { it.status != "removed" && it.status != "failed" }?.map { it.placementId }.orEmpty()
            val known = (fs.frames as? Load.Ready)?.value?.firstOrNull { it.id == tvFrame?.frameId }
            var postingToFeed by remember { mutableStateOf(false) }
            val myFeeds = (feeds.state.collectAsStateWithLifecycle().value.feeds as? Load.Ready)?.value.orEmpty()
            if (postingToFeed && send != null) PostToFeedDialog(myFeeds, send.feeds.filter { !it.removed }.map { it.feedId }.toSet(), { postingToFeed = false }) { chosen ->
                postingToFeed = false; vm.forwardToFeeds(chosen.map { it.id }, chosen.map { it.name })
            }
            SendDetailScreen(s, { nav.pop() }, { vm.load() }, vm::startEdit, vm::onCaption, vm::saveCaption, vm::cancelEdit, vm::unsend,
                onPostToFeed = if (feedsOn && myFeeds.isNotEmpty()) ({ postingToFeed = true }) else null,
                onOpenFeed = if (feedsOn) ({ f -> nav.push(Route.FeedDetail(f.feedId, FeedTab.Posts, f.postId)) }) else null,
                onShare = if (canShare && send != null) { ids -> Sharing.source(send, ids)?.let(share::open) } else null,
                onShowOnTv = if (tvFrame != null && tvIds.isNotEmpty()) ({
                    showOnTv(TvTarget(tvFrame.frameId, tvFrame.frameName, canMirror = known?.can { it.seeLibrary } == true,
                        canRemote = known?.can { it.changeSettings } == true, scope = TvScope.Selection(tvIds)))
                }) else null)
        }
        is Route.FrameDetail -> FrameDetailRoute(graph, api, r, nav, frames, composer, ready)
        Route.Feeds -> {
            val s by feeds.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { feeds.refresh() }
            FeedsScreen(s, feeds::refresh, { nav.push(Route.FeedDetail(it.id)) }, { name -> feeds.create(name) { f -> nav.push(Route.FeedDetail(f.id)) } },
                onEnterCode = { nav.push(Route.AddFrame()) })
        }
        is Route.FeedDetail -> FeedDetailRoute(graph, api, r, nav, frames, feeds, composer, ready)
        is SocialRoute -> SocialRouteScreen(graph, ready, nav, r, composer, frames, feeds, overflow)
        Route.PhotoSources -> {
            val vm: PhotoSourcesViewModel = viewModel(key = "my-sources-${System.identityHashCode(api)}") {
                PhotoSourcesViewModel(api, SourceScope.Mine, graph.live.events)
            }
            val st by vm.state.collectAsStateWithLifecycle()
            SourceNotices(vm)
            MyPhotoSourcesScreen(st, vm, { nav.pop() })
        }
        is Route.AddPhotoSource -> {
            val d = ready.server.discovery
            val vm: AddSourceViewModel = viewModel(key = "add-source-${r.hashCode()}-${System.identityHashCode(r)}") {
                AddSourceViewModel(api, r.target, d.sourceProviders, d.photoSourcesPrivateNetworks, r.link)
            }
            val st by vm.state.collectAsStateWithLifecycle()
            AddSourceScreen(st, vm, { nav.pop() }) { created ->
                toaster.show(res.getString(R.string.source_created, created.name))
                nav.pop()
            }
        }
        is Route.EmailInbox, is Route.EmailAddress, is Route.TelegramChats, is Route.TelegramChat ->
            ChannelRoute(graph, ready, r, nav)
        is Route.FeedInvite -> {
            val vm: FeedInviteViewModel = viewModel(key = "feed-invite-${r.token}") {
                FeedInviteViewModel(api, r.token, birthdays = graph.servers.current.value?.discovery?.birthdays == true)
            }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(s.result) { if (s.result != null) { feeds.refresh(); frames.refresh() } }
            FeedInviteScreen(s, vm, { nav.pop() },
                onOpenFeed = { id -> nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(id)) },
                onOpenFrame = { id -> nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(id, FrameTab.Feeds)) })
        }
    }
}

@Composable
private fun FeedDetailRoute(graph: AppGraph, api: KinApi, r: Route.FeedDetail, nav: Navigator, frames: FramesViewModel, feeds: FeedsViewModel, composer: ComposerViewModel, ready: Phase.Ready) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val vm: FeedDetailViewModel = viewModel(key = "feed-${r.feedId}-${System.identityHashCode(api)}") {
        FeedDetailViewModel(api, r.feedId, { graph.me.value?.id }, graph.live.events)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is FeedNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is FeedNotice.Error -> toaster.show(res.getString(e.message))
                is FeedNotice.ShareInvite -> shareFeedInvite(context, res, e.invite, e.feedName)
                FeedNotice.Gone -> { feeds.remove(r.feedId); nav.selectTab(Route.Feeds) }
            }
        }
    }
    LaunchedEffect(s.feed) { (s.feed as? Load.Ready)?.value?.let { feeds.upsert(it) } }
    // Opened from a notification or the history: show that post's receipts.
    LaunchedEffect(r.postId, s.posts) {
        val id = r.postId ?: return@LaunchedEffect
        if ((s.posts as? Load.Ready)?.value?.any { it.id == id } == true && s.receiptsFor == null) vm.openReceipts(id)
    }
    val fs by frames.state.collectAsStateWithLifecycle()
    val managed = (fs.frames as? Load.Ready)?.value.orEmpty().filter { it.can { p -> p.changeSettings } }
    val me by graph.me.collectAsStateWithLifecycle()
    val feedName = s.feedOrNull?.name.orEmpty()
    // Birthdays (docs/spec/birthdays.md), only when the server has them.
    val birthdaysOn = graph.servers.current.value?.discovery?.birthdays == true
    val bvm: app.kinframe.family.ui.feeds.FeedBirthdaysViewModel? = if (!birthdaysOn) null else viewModel(key = "birthdays-${r.feedId}-${System.identityHashCode(api)}") {
        app.kinframe.family.ui.feeds.FeedBirthdaysViewModel(
            api, r.feedId, graph.live.events,
            readPhoto = { uri -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.kinframe.family.ui.Avatars.squareJpeg(graph.app, uri) } },
        )
    }
    val bs = bvm?.state?.collectAsStateWithLifecycle()?.value
    if (bvm != null) LaunchedEffect(bvm) {
        bvm.events.collect { e ->
            when (e) {
                is app.kinframe.family.ui.feeds.BirthdayNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is app.kinframe.family.ui.feeds.BirthdayNotice.Error -> toaster.show(res.getString(e.message))
                is app.kinframe.family.ui.feeds.BirthdayNotice.Removed -> toaster.show(
                    res.getString(R.string.birthday_removed, e.person.displayName), res.getString(R.string.common_undo),
                ) { bvm.undoRemove(e.person) }
            }
        }
    }
    FeedDetailScreen(
        s, r.tab, { t -> nav.replace(r.copy(tab = t, postId = null)) }, { nav.pop() },
        FeedActions(
            vm, me?.id, managed,
            post = { composer.preselectFeed(r.feedId); nav.selectTab(Route.Send) },
            shareInvite = { shareFeedInvite(context, res, it, feedName) },
            copyInvite = { inv -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(feedName, inv.url))); toaster.show(res.getString(R.string.people_copied)) } },
            openFrame = { id -> nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(id, FrameTab.Feeds)) },
            birthdays = bvm?.let { b ->
                app.kinframe.family.ui.feeds.BirthdayActions(b, me?.id, me?.displayName, s.members, setNotifications = { on -> vm.setBirthdayNotifications(on) })
            },
            birthdaysState = bs,
            sources = if (graph.servers.current.value?.discovery?.sourceProviders.isNullOrEmpty()) null else ({
                val svm: PhotoSourcesViewModel = viewModel(key = "feed-sources-${r.feedId}-${System.identityHashCode(api)}") {
                    PhotoSourcesViewModel(api, SourceScope.Feed(r.feedId), graph.live.events)
                }
                val sst by svm.state.collectAsStateWithLifecycle()
                SourceNotices(svm)
                PhotoSourcesSection(sst, svm, feed = true,
                    onAdd = { nav.push(Route.AddPhotoSource(SourceTarget.feed(r.feedId, feedName))) }, onGoogle = null)
            }),
            channels = graph.servers.current.value?.discovery?.takeIf { it.emailInbox || it.telegram }?.let { d ->
                val scope = app.kinframe.family.ui.channels.ChannelScope.feed(r.feedId, feedName)
                ({
                    app.kinframe.family.ui.channels.ChannelsSection(feed = true,
                        onEmail = if (d.emailInbox) ({ nav.push(Route.EmailInbox(scope)) }) else null,
                        onTelegram = if (d.telegram) ({ nav.push(Route.TelegramChats(scope)) }) else null)
                })
            },
            addFromGoogle = if (graph.servers.current.value?.discovery?.googlePhotosImport == true) ({
                graph.googleImports.start(SourceTarget.feed(r.feedId, feedName))
            }) else null,
            social = feedSocialSlots(graph, ready, nav, s.feedOrNull, onFeedChanged = { f -> feeds.upsert(f); vm.load() }, setFlag = vm::setFlag, busy = s.busy),
        ),
    )
}

private fun shareFeedInvite(context: Context, res: android.content.res.Resources, invite: FeedInvite, feedName: String) {
    val code = FeedCodes.format(invite.code)
    val text = if (invite.purpose == FeedInvitePurpose.SUBSCRIBE) res.getString(R.string.feed_share_subscribe_text, feedName, invite.url, code)
    else res.getString(R.string.feed_share_join_text, feedName, invite.url, code)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.feed_share_title, feedName))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, res.getString(R.string.feed_share_title, feedName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun FrameDetailRoute(graph: AppGraph, api: KinApi, r: Route.FrameDetail, nav: Navigator, frames: FramesViewModel, composer: ComposerViewModel, ready: Phase.Ready) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val vm: FrameDetailViewModel = viewModel(key = "frame-${r.frameId}-${System.identityHashCode(api)}") {
        FrameDetailViewModel(api, r.frameId, { graph.me.value?.id }, graph.live.events)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is FrameEvent.Command -> {
                    val name = (s.frame as? Load.Ready)?.value?.name.orEmpty()
                    toaster.show(when (e.type) {
                        CommandType.PREVIOUS -> res.getString(R.string.cmd_done_previous)
                        CommandType.NEXT -> res.getString(R.string.cmd_done_next)
                        CommandType.SLEEP -> res.getString(R.string.cmd_done_sleep, name)
                        CommandType.WAKE -> res.getString(R.string.cmd_done_wake, name)
                        CommandType.IDENTIFY -> res.getString(R.string.cmd_done_identify, name)
                        else -> res.getString(R.string.cmd_done_sync, name)
                    })
                }
                is FrameEvent.Error -> toaster.show(res.getString(e.message))
                is FrameEvent.Message -> toaster.show(when {
                    e.arg2 != null -> res.getString(e.message, e.arg, e.arg2)
                    e.arg != null -> res.getString(e.message, e.arg)
                    else -> res.getString(e.message)
                })
                is FrameEvent.ShareInvite -> shareInvite(context, res, e.invite, (s.frame as? Load.Ready)?.value?.name.orEmpty())
                FrameEvent.Gone -> { frames.remove(r.frameId); nav.selectTab(Route.Frames) }
            }
        }
    }
    val frameName = (s.frame as? Load.Ready)?.value?.name.orEmpty()
    val me by graph.me.collectAsStateWithLifecycle()
    val actions = FrameActions(
        me = me?.id,
        command = vm::command, rename = vm::rename, setFlag = vm::setFrameFlag, leave = vm::leave, remove = vm::remove,
        decide = vm::decide, updateMember = vm::updateMember, removeMember = vm::removeMember, createInvite = vm::createInvite,
        shareInvite = { shareInvite(context, res, it, frameName) },
        copyInvite = { inv -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(frameName, inv.url))); toaster.show(res.getString(R.string.people_copied)) } },
        revokeInvite = vm::revokeInvite,
        transfer = vm::transfer, replaceDevice = vm::replaceDevice, resetReplace = vm::resetReplace,
    )
    FrameDetailScreen(
        s, r.tab, { t -> nav.replace(r.copy(tab = t)) }, { nav.pop() }, { vm.load() },
        onSend = { composer.preselectFrame(r.frameId); nav.selectTab(Route.Send) },
        overview = actions,
        library = {
            val canSettings = (s.frame as? Load.Ready)?.value?.can { it.changeSettings } == true
            val lib: LibraryViewModel = viewModel(key = "lib-${r.frameId}-${System.identityHashCode(api)}") { LibraryViewModel(api, r.frameId, canSettings) }
            val ls by lib.state.collectAsStateWithLifecycle()
            LaunchedEffect(lib) {
                lib.events.collect { e ->
                    when (e) {
                        is LibraryEvent.Error -> toaster.show(res.getString(e.message))
                        is LibraryEvent.Message -> {
                            val msg = if (e.plural && e.count != null) res.getQuantityString(e.message, e.count, *e.args.toTypedArray()) else res.getString(e.message, *e.args.toTypedArray())
                            toaster.show(msg, if (e.undo != null) res.getString(R.string.common_undo) else null, e.undo)
                        }
                        is LibraryEvent.Done -> {
                            val plural = when (e.action) {
                                "hide" -> R.plurals.library_done_hide; "unhide" -> R.plurals.library_done_unhide
                                "trash" -> R.plurals.library_done_trash; else -> R.plurals.library_done_restore
                            }
                            var msg = res.getQuantityString(plural, e.count, e.count)
                            if (e.failed > 0) msg += " " + res.getQuantityString(R.plurals.library_some_failed, e.failed, e.failed)
                            toaster.show(msg, if (e.undo != null) res.getString(R.string.common_undo) else null, e.undo)
                        }
                    }
                }
            }
            val share: ShareViewModel = viewModel(key = "share-${System.identityHashCode(api)}") { ShareViewModel(api) }
            LaunchedEffect(share, lib) { share.events.collect { if (it is ShareEvent.Shared) lib.clearSelection() } }
            val fs by frames.state.collectAsStateWithLifecycle()
            val canShare = Sharing.targets((fs.frames as? Load.Ready)?.value.orEmpty(), r.frameId).isNotEmpty()
            (s.frame as? Load.Ready)?.value?.let { f ->
                app.kinframe.family.ui.frame.LibraryTab(f, ls, ready.server.limits.trashRetentionDays, lib::setTab, lib::toggle, lib::selectAll, lib::clearSelection,
                    { lib.act(it) }, lib::loadMore, lib::load,
                    onShare = if (canShare) ({ share.open(ShareSource(r.frameId, ls.selected.toList())) }) else null,
                    albums = app.kinframe.family.ui.frame.LibraryAlbumActions(
                        select = lib::selectAlbum, create = { lib.createAlbum(it) }, rename = lib::renameAlbum, schedule = lib::scheduleAlbum,
                        delete = lib::deleteAlbum, play = { id -> lib.play(id, f.name) }, addTo = lib::addToAlbum,
                        removeFromAlbum = { lib.removeFromAlbum(it) }, makeCover = lib::makeCover,
                        startArranging = lib::startArranging, move = lib::move, saveOrder = lib::saveOrder, cancelArranging = lib::cancelArranging,
                        act = { action, ids -> lib.act(action, ids) },
                        shareOne = if (canShare) ({ id -> share.open(ShareSource(r.frameId, listOf(id))) }) else null,
                        sources = albumSources(graph, api, ready, nav, f),
                        deleteWithSources = { a, remove -> lib.deleteAlbum(a, remove) },
                        onlyInAlbumMode = lib::setOnlyInAlbumMode,
                    ))
                // A Google Photos import into one of this frame's albums finished: show the new photos.
                LaunchedEffect(lib) { graph.googleImports.finished.collect { g -> if (g.target.frameId == f.id) lib.load() } }
            }
        },
        settings = {
            val fs: FrameSettingsViewModel = viewModel(key = "fs-${r.frameId}-${System.identityHashCode(api)}") {
                FrameSettingsViewModel(api, r.frameId) { Locale.getDefault().language }
            }
            val ss by fs.state.collectAsStateWithLifecycle()
            LaunchedEffect(fs) {
                fs.events.collect { e ->
                    when (e) {
                        SettingsEvent.Saved -> toaster.show(res.getString(R.string.fs_saved, frameName))
                        SettingsEvent.MergedSaved -> toaster.show(res.getString(R.string.fs_merged_saved))
                        is SettingsEvent.Error -> toaster.show(res.getString(e.message))
                    }
                }
            }
            val pv: PluginsViewModel = viewModel(key = "plugins-${r.frameId}-${System.identityHashCode(api)}") { PluginsViewModel(api, r.frameId) }
            val ps by pv.state.collectAsStateWithLifecycle()
            LaunchedEffect(pv) {
                pv.events.collect { e ->
                    when (e) {
                        PluginsEvent.Saved -> toaster.show(res.getString(R.string.plugins_saved))
                        PluginsEvent.Removed -> toaster.show(res.getString(R.string.plugins_removed))
                        is PluginsEvent.UpdateApproved -> toaster.show(res.getString(R.string.plugins_update_approved, e.version))
                        is PluginsEvent.Error -> toaster.show(res.getString(e.message))
                    }
                }
            }
            (s.frame as? Load.Ready)?.value?.let { f ->
                val d = ready.server.discovery
                val scope = app.kinframe.family.ui.channels.ChannelScope.frame(f.id, f.name)
                val manages = f.can { it.changeSettings }
                val socialFrame = manages && (SocialFlags.of(d).social || SocialFlags.of(d).engagement)
                SettingsTab(f, ss, d.weather, fs, plugins = { PluginsSection(ps, pv) }, channels = if (!manages || (!d.emailInbox && !d.telegram && !socialFrame)) null else ({
                    if (d.emailInbox || d.telegram) app.kinframe.family.ui.channels.ChannelsSection(feed = false,
                        onEmail = if (d.emailInbox) ({ nav.push(Route.EmailInbox(scope)) }) else null,
                        onTelegram = if (d.telegram) ({ nav.push(Route.TelegramChats(scope)) }) else null)
                    // Family social on the frame: show comments, ask for photos, prompt cards, "new photos from".
                    if (socialFrame) FrameSocialSettingsSlot(ready, fs)
                }))
            }
        },
        calendar = if (!ready.server.discovery.calendar) null else ({
            (s.frame as? Load.Ready)?.value?.let { f -> CalendarRoute(graph, api, f, me) }
        }),
        feeds = if (!ready.server.discovery.feeds) null else ({
            (s.frame as? Load.Ready)?.value?.let { f ->
                val ffs: FrameFeedsViewModel = viewModel(key = "frame-feeds-${f.id}-${System.identityHashCode(api)}") {
                    FrameFeedsViewModel(api, f.id, f.can { it.changeSettings }, graph.live.events)
                }
                val st by ffs.state.collectAsStateWithLifecycle()
                LaunchedEffect(ffs) {
                    ffs.events.collect { e ->
                        when (e) {
                            is FeedNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                            is FeedNotice.Error -> toaster.show(res.getString(e.message))
                            else -> Unit
                        }
                    }
                }
                FrameFeedsTab(f, st, ffs, onOpenFeed = { id ->
                    // Only feeds you are in open; a frame can subscribe to a feed you are not a member of.
                    if (st.myFeeds.any { it.id == id }) { nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(id)) }
                }, onEnterCode = { nav.push(Route.AddFrame()) }, birthdays = ready.server.discovery.birthdays)
            }
        }),
    )
}

@Composable
private fun CalendarRoute(graph: AppGraph, api: KinApi, f: app.kinframe.core.api.Frame, me: app.kinframe.core.api.User?) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val k = System.identityHashCode(api)
    val manage = f.can { it.changeSettings }
    val cal: CalendarViewModel = viewModel(key = "cal-${f.id}-$k") {
        CalendarViewModel(api, f.id, manage, f.can { it.send }, graph.live.events) { uri, p -> graph.uploadEventPhoto(uri, p) }
    }
    val st by cal.state.collectAsStateWithLifecycle()
    val form by cal.form.collectAsStateWithLifecycle()
    LaunchedEffect(cal) {
        cal.events.collect { e ->
            when (e) {
                is CalendarNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is CalendarNotice.Added -> toaster.show(res.getQuantityString(R.plurals.cal_added, e.count, e.count))
                is CalendarNotice.Error -> toaster.show(res.getString(e.message))
            }
        }
    }
    LaunchedEffect(cal) {
        graph.live.events.collect { e ->
            if (e is app.kinframe.family.data.LiveEvent.InvitationDecided && e.frameId == f.id && e.invitation.status in setOf("accepted", "declined")) {
                toaster.show(res.getString(if (e.invitation.status == "accepted") R.string.cal_decided_accepted else R.string.cal_decided_declined, f.name, e.invitation.title))
            }
        }
    }
    val settingsVm: FrameSettingsViewModel? = if (!manage) null else viewModel(key = "cal-fs-${f.id}-$k") {
        FrameSettingsViewModel(api, f.id) { Locale.getDefault().language }
    }
    val ss = settingsVm?.state?.collectAsStateWithLifecycle()?.value
    if (settingsVm != null) LaunchedEffect(settingsVm) {
        settingsVm.events.collect { e ->
            when (e) {
                SettingsEvent.Saved, SettingsEvent.MergedSaved -> { toaster.show(res.getString(R.string.cal_settings_saved)); cal.refresh() }
                is SettingsEvent.Error -> toaster.show(res.getString(e.message))
            }
        }
    }
    CalendarTab(f, true, st, form, ss, CalendarActions(cal, settingsVm, me?.id, me?.displayName.orEmpty()))
}

@Composable
private fun TvRoute(graph: AppGraph, api: KinApi, t: TvTarget, ready: Phase.Ready, tvState: TvPresenter.State, onClose: () -> Unit, frames: FramesViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val vm: TvViewModel = viewModel(key = "tv-${t.frameId}-${t.scope.hashCode()}-${System.identityHashCode(api)}") { TvViewModel(api, t.frameId, t.scope) }
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is TvNotice.Message -> toaster.show(if (e.arg != null) res.getString(e.message, e.arg) else res.getString(e.message))
                is TvNotice.Present -> graph.tv.start(e.link, t.frameName, st.interval)
            }
        }
    }
    TvDialog(
        t.frameName, t.scope, t.canMirror, t.canRemote, ready.server.discovery.web, st,
        TvDialogActions(
            vm, tvState,
            onCopy = { url -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(t.frameName, url))); toaster.show(res.getString(R.string.tv_copied)) } },
            onShare = { url ->
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
                context.startActivity(Intent.createChooser(send, res.getString(R.string.tv_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            onStop = { graph.tv.stop(); toaster.show(res.getString(R.string.tv_stopped)) },
            onPrevious = graph.tv::previous, onNext = graph.tv::next, onToggle = graph.tv::toggle,
            onClaimed = { frames.refresh() },
            onClose = onClose,
        ),
    )
}

private fun shareInvite(context: Context, res: android.content.res.Resources, invite: Invite, frameName: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.people_share_title, frameName))
        putExtra(Intent.EXTRA_TEXT, res.getString(R.string.people_share_text, frameName, invite.url))
    }
    context.startActivity(Intent.createChooser(send, res.getString(R.string.people_share_title, frameName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Keeps the picked files readable after the screen closes, then queues the send for the background worker. */
private suspend fun queueSend(graph: AppGraph, send: app.kinframe.family.send.OutboxSend) = withContext(Dispatchers.IO) {
    val ctx = graph.app
    val cr = ctx.contentResolver
    val dir = SendRunner.outboxDir(ctx, send.id)
    val items = send.items.map { item ->
        val uri = android.net.Uri.parse(item.uri)
        if (MediaFiles.persist(cr, uri)) item
        else {
            val ext = item.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.let { ".$it" }.orEmpty()
            val target = File(dir, item.id + ext)
            val n = MediaFiles.copy(cr, uri, target)
            item.copy(localPath = target.absolutePath, size = n)
        }
    }
    val ready = send.copy(items = items)
    graph.outbox.add(ready)
    SendWorker.enqueue(ctx, ready.id)
}

private fun discardOutbox(graph: AppGraph, context: Context, id: String) {
    SendWorker.cancel(context, id)
    graph.outbox.get(id)?.let { s ->
        SendRunner.cleanup(s)
        s.items.filter { it.localPath == null }.forEach { MediaFiles.release(context.contentResolver, android.net.Uri.parse(it.uri)) }
    }
    graph.outbox.remove(id)
}

/** Asks once for the notification permission (Android 13+), explained by the Settings screen if declined. */
@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val res = LocalResources.current
    val prefs = remember { context.getSharedPreferences("kinframe_ui", Context.MODE_PRIVATE) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("asked_notifications", false) && !Notifications.canPost(context)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Toasts for a photo source list. */
@Composable
private fun SourceNotices(vm: PhotoSourcesViewModel) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is SourceNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
                is SourceNotice.Error -> toaster.show(res.getString(e.message))
            }
        }
    }
}

/** "Synced from…" and "Add from Google Photos" under an open album; null when the server offers neither. */
private fun albumSources(graph: AppGraph, api: KinApi, ready: Phase.Ready, nav: Navigator, f: app.kinframe.core.api.Frame): (@Composable (app.kinframe.core.api.Album) -> Unit)? {
    val d = ready.server.discovery
    val sourcesOn = d.sourceProviders.isNotEmpty()
    if (!sourcesOn && !d.googlePhotosImport) return null
    return { album ->
        val target = SourceTarget.album(f.id, f.name, album.id, album.name, album.onlyInAlbumMode)
        if (sourcesOn) {
            val vm: PhotoSourcesViewModel = viewModel(key = "album-sources-${album.id}-${System.identityHashCode(api)}") {
                PhotoSourcesViewModel(api, SourceScope.Album(f.id, album.id), graph.live.events)
            }
            val st by vm.state.collectAsStateWithLifecycle()
            SourceNotices(vm)
            PhotoSourcesSection(st, vm, feed = false,
                onAdd = { nav.push(Route.AddPhotoSource(target)) },
                onGoogle = if (d.googlePhotosImport) ({ graph.googleImports.start(target) }) else null)
        } else {
            app.kinframe.family.ui.SecondaryButton(androidx.compose.ui.res.stringResource(R.string.google_add), { graph.googleImports.start(target) }, icon = app.kinframe.core.design.KinIcons.Image)
        }
    }
}

/**
 * App-wide photo-source pieces: the Google Photos import dialog and its trips to Google, and links to Immich or iCloud
 * albums shared into the app ("Sync this album to…").
 */
@Composable
private fun PhotoSourcesHome(graph: AppGraph, ready: Phase.Ready, nav: Navigator) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val d = ready.server.discovery
    val flow = graph.googleImports
    val gs by flow.state.collectAsStateWithLifecycle()
    LaunchedEffect(flow) {
        flow.opens.collect { o ->
            val ctx = context.findActivity() ?: context
            when (o) {
                is GoogleOpen.Consent -> GoogleOpener.openConsent(ctx, o.url)
                is GoogleOpen.Picker -> GoogleOpener.openPicker(ctx, o.url)
            }
        }
    }
    // The app was closed while Google Photos was open: pick the import up again.
    if (d.googlePhotosImport) LaunchedEffect(ready.api) { flow.recover() }
    GoogleImportDialog(gs, flow)

    val shared by graph.incomingSourceLink.collectAsStateWithLifecycle()
    val link = remember(shared) { PhotoSourceLinks.parse(shared) }
    LaunchedEffect(shared) {
        if (shared == null) return@LaunchedEffect
        val provider = link?.provider
        when {
            d.sourceProviders.isEmpty() -> { toaster.show(res.getString(R.string.source_problem_disabled)); graph.incomingSourceLink.value = null }
            provider == null || provider !in d.sourceProviders -> {
                toaster.show(res.getString(if (link is SourceLink.ICloudPhotosLink) R.string.source_err_unsupported_link else R.string.source_shared_not_supported))
                graph.incomingSourceLink.value = null
            }
        }
    }
    val l = link
    if (shared != null && l?.provider != null && l.provider in d.sourceProviders) {
        val choices by androidx.compose.runtime.produceState<List<SyncChoice>?>(null, ready.api) {
            value = syncChoices(ready.api, d.feeds,
                feedLabel = { res.getString(R.string.source_shared_feed_choice, it) },
                albumLabel = { a, f -> res.getString(R.string.source_shared_album_choice, a, f) })
        }
        SyncLinkDialog(l, choices, { graph.incomingSourceLink.value = null }) { ch ->
            graph.incomingSourceLink.value = null
            nav.push(Route.AddPhotoSource(ch.target, l.url))
        }
    }
}
