package app.kinframe.family.ui

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
import app.kinframe.family.R
import app.kinframe.family.auth.AuthMode
import app.kinframe.family.notify.Notifications
import app.kinframe.family.send.MediaFiles
import app.kinframe.family.send.OutboxState
import app.kinframe.family.send.SendRunner
import app.kinframe.family.send.SendWorker
import app.kinframe.family.ui.frame.FrameActions
import app.kinframe.family.ui.frame.FrameDetailScreen
import app.kinframe.family.ui.frame.FrameDetailViewModel
import app.kinframe.family.ui.frame.FrameEvent
import app.kinframe.family.ui.frame.FrameSettingsViewModel
import app.kinframe.family.ui.frame.LibraryEvent
import app.kinframe.family.ui.frame.LibraryTab
import app.kinframe.family.ui.frame.LibraryViewModel
import app.kinframe.family.ui.frame.SettingsEvent
import app.kinframe.family.ui.frame.SettingsTab
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

@Composable
fun FamilyRoot(graph: AppGraph, app: AppViewModel) {
    val phase by app.phase.collectAsStateWithLifecycle()
    val switch by app.switchPrompt.collectAsStateWithLifecycle()
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
            when (pending) { is DeepLink.FrameCode -> SignInReason.Frame; is DeepLink.Invite -> SignInReason.Invite; null -> SignInReason.None },
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

private val TABS = listOf(
    TabSpec(Route.Frames, KinIcons.Frame, R.string.nav_frames),
    TabSpec(Route.Send, KinIcons.Send, R.string.nav_send),
    TabSpec(Route.History, KinIcons.History, R.string.nav_history),
    TabSpec(Route.Settings, KinIcons.Settings, R.string.nav_settings),
)

@Composable
private fun Home(graph: AppGraph, app: AppViewModel, ready: Phase.Ready) {
    val api = ready.api
    val context = LocalContext.current
    val res = LocalResources.current
    val nav = remember(api) { Navigator() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toaster = remember { Toaster(snackbar, scope) }
    val k = System.identityHashCode(api)

    val frames: FramesViewModel = viewModel(key = "frames-$k") { FramesViewModel(api, graph.live.events) }
    val composer: ComposerViewModel = viewModel(key = "composer-$k") {
        ComposerViewModel(
            api = { graph.api.value }, limits = { graph.servers.current.value?.limits ?: Limits() },
            server = { graph.servers.current.value?.url?.toString().orEmpty() },
            inspect = { uri -> withContext(Dispatchers.IO) { MediaFiles.inspect(context.applicationContext, uri) } },
            enqueue = { send -> queueSend(graph, send) },
        )
    }
    val history: HistoryViewModel = viewModel(key = "history-$k") { HistoryViewModel(api, graph.live.events, graph.sent) }

    BackHandler(enabled = nav.stack.size > 1 || nav.tab != Route.Frames) { if (!nav.pop()) nav.selectTab(Route.Frames) }

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
            null -> Unit
        }
        graph.pendingLink.value = null
    }
    val target by graph.openTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        graph.openTarget.value = null
        when (t.target) {
            Notifications.TARGET_PEOPLE -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it, FrameTab.People)) }
            Notifications.TARGET_FRAME -> t.frameId?.let { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(it)) }
            else -> {
                nav.selectTab(Route.History)
                val sendId = t.sendId ?: t.placementId?.let { history.sendWithPlacement(it) }
                sendId?.let { nav.push(Route.SendDetail(it)) }
            }
        }
    }
    AskForNotifications()

    LaunchedEffect(composer) {
        composer.events.collect { e ->
            when (e) {
                is ComposerEvent.Queued -> { toaster.show(res.getString(R.string.send_sent)); nav.selectTab(Route.History) }
                is ComposerEvent.CaptionApplied -> toaster.show(res.getQuantityString(R.plurals.send_applied_all, e.count, e.count))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> composer.add(uris) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    CompositionLocalProvider(LocalToaster provides toaster) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val showChrome = nav.stack.size == 1
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = Kin.colors.surface, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
                        TABS.forEach { t ->
                            NavigationRailItem(
                                selected = nav.tab == t.route, onClick = { nav.selectTab(t.route) },
                                icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
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
                                TABS.forEach { t ->
                                    NavigationBarItem(
                                        selected = nav.tab == t.route, onClick = { nav.selectTab(t.route) },
                                        icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Kin.colors.onAccentSoft, indicatorColor = Kin.colors.accentSoft,
                                            selectedTextColor = Kin.colors.ink, unselectedIconColor = Kin.colors.inkSoft, unselectedTextColor = Kin.colors.inkSoft),
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        Screen(graph, app, ready, nav, frames, composer, history, pick)
                    }
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
) {
    val api = ready.api
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
                    { nav.selectTab(Route.Frames); nav.push(Route.AddFrame()) }, { composer.loadFrames() })
            }
        }
        Route.History -> {
            val s by history.state.collectAsStateWithLifecycle()
            val outbox by graph.outbox.sends.collectAsStateWithLifecycle()
            val progress by graph.outbox.progress.collectAsStateWithLifecycle()
            HistoryScreen(
                s, outbox.sortedByDescending { it.createdAt }, progress, history::setFilter, { nav.push(Route.SendDetail(it.id)) },
                history::refresh, history::loadMore,
                onRetryOutbox = { id -> graph.outbox.update(id) { it.copy(state = OutboxState.QUEUED, error = null) }; SendWorker.enqueue(context, id) },
                onDiscardOutbox = { id -> discardOutbox(graph, context, id) },
            )
        }
        Route.Settings -> {
            val push by graph.push.status.collectAsStateWithLifecycle()
            val ps by app.profileState.collectAsStateWithLifecycle()
            var allowed by remember { mutableStateOf(Notifications.canPost(context)) }
            val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
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
            )
        }
        is Route.AddFrame -> {
            val vm: AddFrameViewModel = viewModel(key = "add-${r.code}-${System.identityHashCode(r)}") { AddFrameViewModel(api, r.code) }
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
                    }
                }
            }
            SendDetailScreen(s, { nav.pop() }, { vm.load() }, vm::startEdit, vm::onCaption, vm::saveCaption, vm::cancelEdit, vm::unsend)
        }
        is Route.FrameDetail -> FrameDetailRoute(graph, api, r, nav, frames, composer, ready)
    }
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
                is FrameEvent.Message -> toaster.show(if (e.arg != null) res.getString(e.message, e.arg) else res.getString(e.message))
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
    )
    FrameDetailScreen(
        s, r.tab, { t -> nav.replace(r.copy(tab = t)) }, { nav.pop() }, { vm.load() },
        onSend = { composer.preselectFrame(r.frameId); nav.selectTab(Route.Send) },
        overview = actions,
        library = {
            val lib: LibraryViewModel = viewModel(key = "lib-${r.frameId}-${System.identityHashCode(api)}") { LibraryViewModel(api, r.frameId) }
            val ls by lib.state.collectAsStateWithLifecycle()
            LaunchedEffect(lib) {
                lib.events.collect { e ->
                    when (e) {
                        is LibraryEvent.Error -> toaster.show(res.getString(e.message))
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
            (s.frame as? Load.Ready)?.value?.let { f ->
                app.kinframe.family.ui.frame.LibraryTab(f, ls, ready.server.limits.trashRetentionDays, lib::setTab, lib::toggle, lib::selectAll, lib::clearSelection,
                    { lib.act(it) }, lib::loadMore, lib::load)
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
            (s.frame as? Load.Ready)?.value?.let { f -> SettingsTab(f, ss, ready.server.discovery.weather, fs) }
        },
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
