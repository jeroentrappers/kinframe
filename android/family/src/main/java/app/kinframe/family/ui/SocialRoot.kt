package app.kinframe.family.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinframe.core.api.Feed
import app.kinframe.core.servers.RoutingSocialApi
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.SendCreate
import app.kinframe.core.api.SendItemCreate
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.OnThisDayGroup
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.SocialApi
import app.kinframe.family.AppGraph
import app.kinframe.family.OpenTarget
import app.kinframe.family.R
import app.kinframe.family.notify.SocialNotifications
import app.kinframe.family.ui.chats.ChatMedia
import app.kinframe.family.ui.chats.ChatsNotice
import app.kinframe.family.ui.chats.ChatsScreen
import app.kinframe.family.ui.chats.ChatsViewModel
import app.kinframe.family.ui.chats.ConversationActions
import app.kinframe.family.ui.chats.ConversationInfoScreen
import app.kinframe.family.ui.chats.ConversationInfoViewModel
import app.kinframe.family.ui.chats.ConversationNotice
import app.kinframe.family.ui.chats.ConversationScreen
import app.kinframe.family.ui.chats.ConversationViewModel
import app.kinframe.family.ui.chats.InfoNotice
import app.kinframe.family.ui.chats.NewChatScreen
import app.kinframe.family.ui.chats.PersonNotice
import app.kinframe.family.ui.chats.PersonScreen
import app.kinframe.family.ui.chats.PersonViewModel
import app.kinframe.family.ui.chats.PutOnFrameDialog
import app.kinframe.family.ui.chats.PutOnFrameTargets
import app.kinframe.family.ui.chats.ShareInChatDialog
import app.kinframe.family.ui.chats.VoiceRecorder
import app.kinframe.family.ui.engagement.DocumentViewModel
import app.kinframe.family.ui.engagement.EngagementNotice
import app.kinframe.family.ui.engagement.FeedEngagementScreen
import app.kinframe.family.ui.engagement.FeedEngagementViewModel
import app.kinframe.family.ui.engagement.FrameSocialSettings
import app.kinframe.family.ui.engagement.FreshFrameScreen
import app.kinframe.family.ui.engagement.FreshFrameViewModel
import app.kinframe.family.ui.engagement.MilestonesScreen
import app.kinframe.family.ui.engagement.MilestonesViewModel
import app.kinframe.family.ui.engagement.RecapScreen
import app.kinframe.family.ui.engagement.RemindersScreen
import app.kinframe.family.ui.engagement.RemindersViewModel
import app.kinframe.family.ui.engagement.WeeklySummaryScreen
import app.kinframe.family.ui.feeds.FeedsViewModel
import app.kinframe.family.ui.frames.FramesViewModel
import app.kinframe.family.ui.home.HomeActions
import app.kinframe.family.ui.home.HomeNotice
import app.kinframe.family.ui.home.HomeScreen
import app.kinframe.family.ui.home.HomeViewModel
import app.kinframe.family.ui.safety.BlockedPeopleScreen
import app.kinframe.family.ui.safety.BlocksViewModel
import app.kinframe.family.ui.safety.ReportsScreen
import app.kinframe.family.ui.safety.ReportsViewModel
import app.kinframe.family.ui.safety.SafetyNotice
import app.kinframe.family.ui.send.ComposerState
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.social.CircleNotice
import app.kinframe.family.ui.social.CircleScreen
import app.kinframe.family.ui.social.CirclesScreen
import app.kinframe.family.ui.social.CirclesViewModel
import app.kinframe.family.ui.social.FeedMuteSection
import app.kinframe.family.ui.social.FeedPostFooter
import app.kinframe.family.ui.social.FeedSocialHeader
import app.kinframe.family.ui.social.FeedSocialNotice
import app.kinframe.family.ui.social.FeedSocialSettings
import app.kinframe.family.ui.social.FeedSocialSlots
import app.kinframe.family.ui.social.FeedSocialViewModel
import app.kinframe.family.ui.social.PostActions
import app.kinframe.family.ui.social.PostNotice
import app.kinframe.family.ui.social.PostScreen
import app.kinframe.family.ui.social.PostViewModel
import app.kinframe.family.ui.social.SocialProblems
import app.kinframe.family.ui.social.WhoReactedSheet
import kotlinx.coroutines.launch
import java.util.UUID

/** Which family social areas the server has (discovery flags; README §2). Everything is hidden while false. */
data class SocialFlags(val social: Boolean, val chats: Boolean, val engagement: Boolean, val voice: Boolean, val linkPreviews: Boolean) {
    companion object {
        fun of(d: app.kinframe.core.api.Discovery) =
            SocialFlags(social = d.feeds && d.social, chats = d.chats, engagement = d.engagement, voice = d.chats && d.voiceMessages, linkPreviews = d.chats && d.linkPreviews)

        val NONE = SocialFlags(social = false, chats = false, engagement = false, voice = false, linkPreviews = false)

        /** What any of the accounts has: the merged tabs (Home, Chats) show when one server has them. */
        fun any(all: List<app.kinframe.core.api.Discovery>): SocialFlags = all.map(::of).fold(NONE) { a, b ->
            SocialFlags(a.social || b.social, a.chats || b.chats, a.engagement || b.engagement, a.voice || b.voice, a.linkPreviews || b.linkPreviews)
        }
    }
}

private fun k(o: Any?) = System.identityHashCode(o)

/** The Chats tab's view model, shared by the tab and "Share in chat". */
@Composable
fun chatsViewModel(graph: AppGraph, api: SocialApi): ChatsViewModel =
    viewModel(key = "chats-${k(api)}") {
        ChatsViewModel(api, { graph.me.value?.id }, graph.live.events, { graph.social.openConversation }, isMe = graph.accounts::isMe)
    }

/** Toasts for "Share in chat" and everything else that only says something. */
private suspend fun shareInChat(api: SocialApi, conversationId: String, postId: String, line: String) {
    val id = UUID.randomUUID().toString()
    api.sendMessage(conversationId, MessageCreate(clientId = id, body = line.ifBlank { null }, sharedPostId = postId), idempotencyKey = id)
}

/** "Share in chat" for a post, from any screen. */
@Composable
fun ShareInChatHost(graph: AppGraph, api: SocialApi, postId: String?, onDone: () -> Unit) {
    postId ?: return
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chats = chatsViewModel(graph, api)
    val st by chats.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { chats.refresh() }
    // A post can only be shared in a chat on its own server (multi-server.md §2.2: a chat lives on one server).
    val conversations = remember(st.conversations, postId) {
        when (val l = st.conversations) {
            is Load.Ready -> Load.Ready(l.value.filter { graph.accounts.sameAccount(it.id, postId) })
            else -> l
        }
    }
    ShareInChatDialog(conversations, onDone) { conv, line ->
        onDone()
        scope.launch {
            runCatching { shareInChat(api, conv.id, postId, line) }
                .onSuccess { toaster.show(res.getString(R.string.chat_shared_to, conv.name)) }
                .onFailure { toaster.show(res.getString(SocialProblems.message(it))) }
        }
    }
}

/**
 * "Share again" from On this day (README §5.20): the caller's own photos go out again with `createSend` (their item
 * ids as `upload_id`); someone else's can be shared in a chat as the post.
 */
@Composable
private fun ShareAgainHost(graph: AppGraph, ready: Phase.Ready, social: SocialApi, group: OnThisDayGroup?, onDone: () -> Unit) {
    group ?: return
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // The memory's own server: the person's photos there, and only that server's frames and families (the items'
    // ids are that server's uploads).
    val acct = graph.accounts.contextFor(group.feedId)
    val me = acct.meId
    val own = group.items.filter { it.senderId == me }
    var targets by remember { mutableStateOf<PutOnFrameTargets?>(null) }
    var inChat by remember { mutableStateOf(own.isEmpty()) }
    LaunchedEffect(group) {
        val same = { id: String -> graph.accounts.sameAccount(id, group.feedId) }
        val frames = runCatching { ready.api.frames() }.getOrDefault(emptyList()).filter { it.can { p -> p.send } && same(it.id) }
        val feeds = runCatching { ready.api.feeds() }.getOrDefault(emptyList()).filter { it.canPost && same(it.id) }
        targets = PutOnFrameTargets(frames, feeds)
    }
    if (inChat) {
        if (acct.discovery?.chats == true) ShareInChatHost(graph, social, group.postIds.firstOrNull(), onDone)
        else LaunchedEffect(Unit) { toaster.show(res.getString(R.string.engagement_share_again_none)); onDone() }
        return
    }
    PutOnFrameDialog(targets, mine = true, onDismiss = onDone) { frames, feeds, circles, names ->
        onDone()
        scope.launch {
            runCatching {
                ready.api.createSend(SendCreate.of(frames, feeds, own.map { SendItemCreate(uploadId = it.id) }, circles), UUID.randomUUID().toString())
            }.onSuccess { toaster.show(res.getString(R.string.chat_put_on_frame_done, names.joinToString(", "))) }
                .onFailure { toaster.show(res.getString(SocialProblems.message(it))) }
        }
    }
}

/** Opens an item's full-size media (signed URL) in the phone's viewer. */
private suspend fun openMedia(context: android.content.Context, api: SocialApi, item: app.kinframe.core.api.Item) {
    val url = api.mediaUrl(item.id, "master")
    val type = if (item.kind == MediaKind.VIDEO) "video/*" else "image/*"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Every family social screen. */
@Composable
fun SocialRouteScreen(
    graph: AppGraph, ready: Phase.Ready, nav: Navigator, r: SocialRoute,
    composer: ComposerViewModel, frames: FramesViewModel, feeds: FeedsViewModel,
    topActions: @Composable () -> Unit,
) {
    val social = graph.social.api.collectAsStateWithLifecycle().value ?: return
    // The account of what this screen shows: its flags, limits and the person's user id there (multi-server.md §2.2).
    val key = socialRouteKey(r)
    // Per-server settings (reminders, blocked people, milestones, operator reports, the weekly summary) name their
    // account; with none, the primary one. Their calls go to that server only (multi-server.md §2.1).
    val scoped = isAccountScoped(r)
    val acct = rememberAccountContext(graph, key, ready, socialRouteAccount(r))
    val flags = if (key == null && !scoped) graph.social.flags else acct.flags
    val own: SocialApi = remember(social, acct.accountId) { (social as? RoutingSocialApi)?.forAccount(acct.accountId) ?: social }
    // The view models of one entity's screen are made again when its account becomes known (multi-server.md §2.2).
    val ak = acct.accountId
    val limits = acct.limits
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val primaryMe = graph.me.collectAsStateWithLifecycle().value?.id ?: ready.me.id
    val me = if (key == null) primaryMe else acct.meId
    val meOf: () -> String? = { graph.accounts.meIdFor(key) }
    val api = ready.api
    var sharing by remember { mutableStateOf<String?>(null) }
    var who by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var again by remember { mutableStateOf<OnThisDayGroup?>(null) }
    val openFeed = { id: String -> nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(id)) }
    when (r) {
        HomeRoute -> {
            val vm: HomeViewModel = viewModel(key = "home-${k(social)}") {
                HomeViewModel(social, api, { graph.me.value?.id }, graph.live.events, flags.engagement, isMe = graph.accounts::isMe,
                    // One card per server: each opens its own server's summary.
                    summaries = { week ->
                        (social as? RoutingSocialApi)?.weeklySummaries(week)?.map { (account, w) -> app.kinframe.family.ui.home.HomeSummary(w, account) }
                            ?: listOf(app.kinframe.family.ui.home.HomeSummary(social.weeklySummary(week)))
                    })
            }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect { e -> if (e is HomeNotice.Message) toaster.show(res.getString(e.message)) } }
            HomeScreen(s, HomeActions(
                vm, me,
                openPost = { nav.push(PostRoute(it.id)) },
                openPerson = { nav.push(PersonRoute(it)) },
                openFrame = { f -> if (flags.engagement) nav.push(FreshFrameRoute(f.id)) else { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(f.id)) } },
                openFeed = { f -> openFeed(f.id) },
                who = { p -> who = Triple(ReactionTarget.POST, p.id, null) },
                share = if (flags.chats) ({ p -> sharing = p.id }) else null,
                shareAgain = { g -> again = g },
                openSummary = { w -> nav.push(WeeklySummaryRoute(w.summary.weekOf, w.accountId)) },
                openMilestones = { nav.push(MilestonesRoute()) },
                openReminders = { nav.push(RemindersRoute()) },
                post = { nav.selectTab(Route.Send) },
                topActions = topActions,
                // "Don't show this memory again" (README §5.21), on the memory's own server: hidden memories are per
                // account, so the group's family names the account whose list it goes on.
                hideMemory = { g: OnThisDayGroup ->
                    val l = graph.leavingFor(g.feedId)
                    if (l != null) scope.launch {
                        runCatching { app.kinframe.family.ui.leaving.MemoriesViewModel.hideMemory(l, g.postIds) }
                            .onSuccess { toaster.show(res.getString(R.string.leaving_memory_hidden)); vm.refresh() }
                            .onFailure { toaster.show(res.getString(app.kinframe.family.util.Problems.message(it))) }
                    }
                },
                canHideMemory = { g -> graph.leavingFor(g.feedId) != null },
                // Several servers: a small server chip on each post (multi-server.md §2.2).
                serverChip = { p -> graph.accounts.chipFor(p.id) },
                meFor = { p -> graph.accounts.meIdFor(p.id) ?: primaryMe },
                stripServer = homeStripServers(graph, s),
                summaryServer = { w -> w.accountId?.let { graph.accounts.labelIfSeveral(it) } },
            ))
        }
        ChatsRoute -> {
            val vm = chatsViewModel(graph, social)
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refresh() }
            ChatNotices(vm, nav)
            ChatsScreen(s, me, { nav.push(ConversationRoute(it.id)) }, { nav.push(NewChatRoute(false)) }, { nav.push(NewChatRoute(true)) },
                vm::refresh, vm::toggleArchived, vm::archive, topActions,
                // Several servers: each chat's own user id ("You: …") and a server chip (multi-server.md §2.2).
                meFor = { c -> graph.accounts.meIdFor(c.id) ?: primaryMe }, serverChip = { c -> graph.accounts.chipFor(c.id) })
        }
        is NewChatRoute -> {
            val vm = chatsViewModel(graph, social)
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.loadContacts() }
            ChatNotices(vm, nav, replace = true)
            NewChatScreen(s, r.group, { nav.pop() }, vm::start, { vm.loadContacts() })
        }
        is PostRoute -> {
            val vm: PostViewModel = viewModel(key = "post-${r.postId}-$ak-${k(social)}") {
                PostViewModel(social, api, r.postId, meOf, graph.live.events, limits.maxCommentChars)
            }
            val s by vm.state.collectAsStateWithLifecycle()
            DisposableEffect(r.postId) { SocialNotifications.clearPost(context, r.postId); onDispose { } }
            LaunchedEffect(vm) {
                vm.events.collect { e ->
                    when (e) {
                        is PostNotice.Message -> toaster.show(res.getString(e.message))
                        PostNotice.Gone -> { toaster.show(res.getString(R.string.social_post_gone)); nav.pop() }
                    }
                }
            }
            PostScreen(s, PostActions(
                vm, me, onBack = { nav.pop() }, openPerson = { nav.push(PersonRoute(it)) },
                shareInChat = if (flags.chats) ({ p -> sharing = p.id }) else null,
                whoReacted = { kind, id, pid -> { social.reactions(kind, id, pid) } },
                safety = flags.chats,
                itemReactions = { itemId ->
                    runCatching { social.reactions(ReactionTarget.POST_ITEM, itemId, r.postId) }.getOrNull()?.let { l ->
                        val counts = (l.people.map { it.emoji } + l.frames.map { it.reaction }).groupingBy { it }.eachCount()
                            .map { app.kinframe.core.social.ReactionCount(it.key, it.value) }.sortedByDescending { it.count }
                        app.kinframe.core.social.ReactionSummary(l.people.size + l.frames.size, counts, l.people.take(3), l.frames.take(3), l.people.firstOrNull { it.person.userId == me }?.emoji)
                    }
                },
            ))
        }
        is ConversationRoute -> ConversationRouteScreen(graph, ready, nav, r, social, flags, acct)
        is ConversationInfoRoute -> {
            val vm: ConversationInfoViewModel = viewModel(key = "conv-info-${r.conversationId}-$ak-${k(social)}") {
                ConversationInfoViewModel(social, r.conversationId, meOf, graph.live.events) { uri ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Avatars.squareJpeg(graph.app, uri) }.getOrNull() }
                }
            }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { e ->
                    when (e) {
                        is InfoNotice.Message -> toaster.show(res.getString(e.message))
                        InfoNotice.Left -> nav.selectTab(ChatsRoute)
                    }
                }
            }
            val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setPhoto(uri) }
            ConversationInfoScreen(s, vm, me, { nav.pop() }, { nav.push(PersonRoute(it)) },
                { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onOpenFamily = if (acct.discovery?.feeds == true) openFeed else null)
        }
        is PersonRoute -> {
            val vm: PersonViewModel = viewModel(key = "person-${r.userId}-$ak-${k(social)}") { PersonViewModel(social, r.userId) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { e ->
                    when (e) {
                        is PersonNotice.Message -> toaster.show(res.getString(e.message))
                        is PersonNotice.OpenChat -> nav.push(ConversationRoute(e.conversationId))
                    }
                }
            }
            PersonScreen(s, vm, me, flags.chats) { nav.pop() }
        }
        is CirclesRoute, is CircleRoute -> {
            val feedId = (r as? CirclesRoute)?.feedId ?: (r as CircleRoute).feedId
            val vm: CirclesViewModel = viewModel(key = "circles-$feedId-$ak-${k(social)}") { CirclesViewModel(social, api, feedId, meOf, graph.live.events) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { e ->
                    when (e) {
                        is CircleNotice.Message -> toaster.show(if (e.arg != null) res.getString(e.message, e.arg) else res.getString(e.message))
                        is CircleNotice.Created -> { toaster.show(res.getString(R.string.social_circle_created, e.circle.name)); nav.push(CircleRoute(feedId, e.circle.id)) }
                        CircleNotice.Deleted -> if (nav.current is CircleRoute) nav.pop()
                    }
                }
            }
            if (r is CircleRoute) CircleScreen(s, vm, r.circleId, me, { nav.pop() }, onChat = if (flags.chats) ({ nav.push(ConversationRoute(it)) }) else null)
            else CirclesScreen(s, vm, { nav.pop() }) { nav.push(CircleRoute(feedId, it.id)) }
        }
        is BlockedPeopleRoute -> {
            val vm: BlocksViewModel = viewModel(key = "blocks-$ak-${k(social)}") { BlocksViewModel(own) }
            val s by vm.state.collectAsStateWithLifecycle()
            SafetyNotices(vm.events)
            BlockedPeopleScreen(s, vm, { nav.pop() }, server = graph.accounts.labelIfSeveral(acct.accountId))
        }
        is ReportsRoute -> {
            val vm: ReportsViewModel = viewModel(key = "reports-${r.feedId}-$ak-${k(social)}") { ReportsViewModel(own, r.feedId, graph.live.events) }
            val s by vm.state.collectAsStateWithLifecycle()
            SafetyNotices(vm.events)
            ReportsScreen(s, vm, operator = r.feedId == null, { nav.pop() }, { nav.push(PostRoute(it)) }, { nav.push(ConversationRoute(it)) },
                server = if (r.feedId == null) graph.accounts.labelIfSeveral(acct.accountId) else null)
        }
        is RemindersRoute -> {
            val vm: RemindersViewModel = viewModel(key = "reminders-$ak-${k(social)}") {
                // Read receipts are per server too: that account's profile, kept in step with what the server answers.
                RemindersViewModel(own, acct.me?.showReadReceipts ?: true, flags.engagement) { u -> graph.setProfile(acct.accountId, u) }
            }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect { e -> if (e is EngagementNotice.Message) toaster.show(res.getString(e.message)) } }
            RemindersScreen(s, vm, flags.chats, flags.engagement, { nav.pop() },
                onBlocked = if (flags.chats) ({ nav.push(BlockedPeopleRoute(r.accountId)) }) else null,
                onMilestones = if (flags.engagement) ({ nav.push(MilestonesRoute(r.accountId)) }) else null,
                onReports = if (flags.chats && acct.isOperator) ({ nav.push(ReportsRoute(null, r.accountId)) }) else null,
                server = graph.accounts.labelIfSeveral(acct.accountId))
        }
        is FeedEngagementRoute -> {
            val vm: FeedEngagementViewModel = viewModel(key = "engagement-${r.feedId}-$ak-${k(social)}") { FeedEngagementViewModel(social, api, r.feedId, graph.live.events) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect { e -> if (e is EngagementNotice.Message) toaster.show(res.getString(e.message)) } }
            FeedEngagementScreen(s, vm) { nav.pop() }
        }
        is WeeklySummaryRoute -> {
            val vm: DocumentViewModel<app.kinframe.core.social.WeeklySummary> = viewModel(key = "summary-${r.weekOf}-$ak-${k(social)}") { DocumentViewModel { own.weeklySummary(r.weekOf) } }
            val s by vm.state.collectAsStateWithLifecycle()
            WeeklySummaryScreen(s, { vm.load() }, { nav.pop() }, server = graph.accounts.labelIfSeveral(acct.accountId))
        }
        is RecapRoute -> {
            val vm: DocumentViewModel<app.kinframe.core.social.MonthlyRecap> = viewModel(key = "recap-${r.feedId}-${r.month}-$ak-${k(social)}") { DocumentViewModel { social.feedRecap(r.feedId, r.month) } }
            val s by vm.state.collectAsStateWithLifecycle()
            RecapScreen(s, { vm.load() }, { nav.pop() }) { nav.push(PostRoute(it)) }
        }
        is MilestonesRoute -> {
            val vm: MilestonesViewModel = viewModel(key = "milestones-$ak-${k(social)}") { MilestonesViewModel(own) }
            val s by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) { vm.events.collect { e -> if (e is EngagementNotice.Message) toaster.show(res.getString(e.message)) } }
            MilestonesScreen(s, vm, { nav.pop() }, server = graph.accounts.labelIfSeveral(acct.accountId))
        }
        is FreshFrameRoute -> {
            val vm: FreshFrameViewModel = viewModel(key = "fresh-${r.frameId}-$ak-${k(social)}") { FreshFrameViewModel(social, api, r.frameId, graph.live.events) }
            val s by vm.state.collectAsStateWithLifecycle()
            FreshFrameScreen(s, { nav.pop() },
                // Not on the frame (it asked through a family): the send preselects the families that reach it.
                onSend = { composer.preselectFrame(r.frameId, s.requests.firstOrNull { it.isOpen }?.id); nav.selectTab(Route.Send) },
                onShareAgain = { again = it },
                onOpenFrame = { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(r.frameId)) })
        }
    }
    ShareInChatHost(graph, social, sharing) { sharing = null }
    ShareAgainHost(graph, ready, social, again) { again = null }
    who?.let { (kind, id, pid) -> WhoReactedSheet({ social.reactions(kind, id, pid) }) { who = null } }
}

@Composable
private fun ChatNotices(vm: ChatsViewModel, nav: Navigator, replace: Boolean = false) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ChatsNotice.Message -> toaster.show(res.getString(e.message))
                is ChatsNotice.Open -> if (replace) nav.replace(ConversationRoute(e.conversationId)) else nav.push(ConversationRoute(e.conversationId))
            }
        }
    }
}

@Composable
private fun SafetyNotices(events: kotlinx.coroutines.flow.Flow<SafetyNotice>) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    LaunchedEffect(events) {
        events.collect { e -> if (e is SafetyNotice.Message) toaster.show(if (e.arg != null) res.getString(e.message, e.arg) else res.getString(e.message)) }
    }
}

@Composable
private fun ConversationRouteScreen(graph: AppGraph, ready: Phase.Ready, nav: Navigator, r: ConversationRoute, social: SocialApi, flags: SocialFlags, acct: app.kinframe.family.accounts.AccountContext) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // The conversation's own server: the person's user id there ("You", own messages) and its limits.
    val me = acct.meId
    val limits = acct.limits
    val media = remember(graph) {
        object : ChatMedia {
            override suspend fun queueMedia(conversationId: String, name: String, uris: List<android.net.Uri>, body: String?, mentions: List<app.kinframe.core.social.Mention>, replyToId: String?) =
                graph.social.queueChatMedia(conversationId, name, uris, body, mentions, replyToId)
            override suspend fun queueVoice(conversationId: String, name: String, file: java.io.File, durationMs: Long, replyToId: String?) =
                graph.social.queueVoice(conversationId, name, file, durationMs, replyToId)
        }
    }
    val vm: ConversationViewModel = viewModel(key = "conv-${r.conversationId}-${k(social)}") {
        ConversationViewModel(social, ready.api, r.conversationId, { graph.accounts.meIdFor(r.conversationId) }, graph.live.events, media, graph.outbox.sends,
            limits.maxMessageChars, flags.linkPreviews)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(r.conversationId) {
        graph.social.openConversation = r.conversationId
        SocialNotifications.clearConversation(context, r.conversationId)
        onDispose { if (graph.social.openConversation == r.conversationId) graph.social.openConversation = null }
    }
    // Back to the screen (app reopened; the socket was closed meanwhile) or a media message left the outbox: fetch
    // what arrived meanwhile (`after` = the newest message we have, README §5.19).
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        if (resumed) vm.catchUp()
        resumed = true
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { graph.sent.collect { vm.catchUp() } }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ConversationNotice.Message -> toaster.show(if (e.arg != null) res.getString(e.message, e.arg) else res.getString(e.message))
                ConversationNotice.Gone -> nav.selectTab(ChatsRoute)
            }
        }
    }
    val recorder = remember(flags.voice) { if (flags.voice) VoiceRecorder(graph.app, graph.social.voiceDir(), limits.maxVoiceSeconds * 1000L) else null }
    DisposableEffect(recorder) { onDispose { recorder?.cancel() } }
    ConversationScreen(s, ConversationActions(
        vm, me, onBack = { nav.pop() }, openInfo = { nav.push(ConversationInfoRoute(r.conversationId)) },
        openPost = { nav.push(PostRoute(it)) }, openPerson = { nav.push(PersonRoute(it)) },
        openRecap = { f, m -> nav.push(RecapRoute(f, m)) },
        invite = if (acct.discovery?.feeds == true) ({ feedId -> nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(feedId, FeedTab.People)) }) else null,
        openUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        openMedia = { item -> scope.launch { runCatching { openMedia(context, social, item) }.onFailure { toaster.show(res.getString(SocialProblems.message(it))) } } },
        voiceUrl = { id -> social.mediaUrl(id, "master") },
        whoReacted = { id -> { social.reactions(ReactionTarget.MESSAGE, id) } },
        copy = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))); toaster.show(res.getString(R.string.chat_copied)) } },
        voice = recorder,
        editHours = limits.messageEditHours,
        safety = flags.chats,
        showError = { toaster.show(res.getString(it)) },
    ))
}

/** Family social inside one family's screens, or null when the server doesn't have it. */
@Composable
fun feedSocialSlots(graph: AppGraph, acct: app.kinframe.family.accounts.AccountContext, ready: Phase.Ready, nav: Navigator, feed: Feed?, onFeedChanged: (Feed) -> Unit, setFlag: (String, Boolean) -> Unit, busy: Boolean): FeedSocialSlots? {
    // The family's own server: its flags and the person's user id there.
    val flags = acct.flags
    if (!flags.social || feed == null) return null
    val social = graph.social.api.collectAsStateWithLifecycle().value ?: return null
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val me = acct.meId
    val vm: FeedSocialViewModel = viewModel(key = "feed-social-${feed.id}-${k(social)}") {
        FeedSocialViewModel(social, ready.api, feed.id, { graph.accounts.meIdFor(feed.id) }, graph.live.events, feed.isAdmin, flags.chats)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is FeedSocialNotice.Message -> toaster.show(res.getString(e.message))
                is FeedSocialNotice.FeedChanged -> onFeedChanged(e.feed)
            }
        }
    }
    var who by remember { mutableStateOf<String?>(null) }
    who?.let { id -> WhoReactedSheet({ social.reactions(ReactionTarget.POST, id) }) { who = null } }
    val reports = if (flags.chats && feed.isAdmin) ({ nav.push(ReportsRoute(feed.id)) }) else null
    val engagement = if (flags.engagement) ({ nav.push(FeedEngagementRoute(feed.id)) }) else null
    return FeedSocialSlots(
        header = {
            FeedSocialHeader(feed, s.openReports,
                onChat = feed.conversationId?.takeIf { flags.chats }?.let { id -> { nav.push(ConversationRoute(id)) } },
                onCircles = { nav.push(CirclesRoute(feed.id)) }, onEngagement = engagement, onReports = reports)
        },
        postFooter = { post ->
            val t = vm.thread(post.id, post.thread) ?: post.thread
            if (t != null) FeedPostFooter(t, me, { emoji -> vm.react(post.id, t, emoji) }, { nav.push(PostRoute(post.id)) }, { who = post.id })
        },
        settings = { FeedSocialSettings(feed, busy, setFlag, reports, engagement) },
        you = { FeedMuteSection(feed, flags.chats, vm::setMute) },
    )
}

/** Frame settings → family social toggles (owner, admins, `change_settings`; README §5.20). */
@Composable
fun FrameSocialSettingsSlot(flags: SocialFlags, fs: app.kinframe.family.ui.frame.FrameSettingsViewModel) {
    if (!flags.social && !flags.engagement) return
    val st by fs.state.collectAsStateWithLifecycle()
    val social = (st.draft?.get("social") as? kotlinx.serialization.json.JsonObject)
    FrameSocialSettings(
        get = { path, default ->
            val key = path.substringAfter('.')
            (social?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
        },
        set = { path, v -> fs.set(path, v) },
        social = flags.social, engagement = flags.engagement,
    )
}

/** Settings: the family social entries (reminders, chats, blocked people, milestones). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialSettingsSection(ready: Phase.Ready, nav: Navigator) =
    SocialSettingsSection(SocialFlags.of(ready.server.discovery), ready.me.isOperator, null, nav)

/** The family social entries of one account ([accountId] null: the primary one), with that server's flags and roles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialSettingsSection(flags: SocialFlags, operator: Boolean, accountId: String?, nav: Navigator) {
    if (!flags.chats && !flags.engagement) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.engagement_settings_title))
        Text(stringResource(R.string.engagement_settings_lede), style = Kin.type.caption, color = Kin.colors.inkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.engagement_settings_open), { nav.push(RemindersRoute(accountId)) }, icon = KinIcons.Bell)
            if (flags.chats) SecondaryButton(stringResource(R.string.chat_blocked_title), { nav.push(BlockedPeopleRoute(accountId)) }, icon = KinIcons.Shield)
            if (flags.chats && operator) SecondaryButton(stringResource(R.string.chat_reports_operator_title), { nav.push(ReportsRoute(null, accountId)) }, icon = KinIcons.Warning)
        }
    }
}

/** "Share with" (circles) and "Answers this week's prompt" under the families in the Send tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendAudience(s: ComposerState, composer: ComposerViewModel) {
    val c = Kin.colors
    s.feeds.filter { it.id in s.selectedFeeds }.forEach { f ->
        val circles = s.circles[f.id].orEmpty()
        if (circles.isNotEmpty()) {
            Text(stringResource(R.string.social_audience_title, f.name), style = Kin.type.label, color = c.inkSoft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(stringResource(R.string.social_audience_whole), s.audience[f.id] == null, { composer.setAudience(f.id, null) })
                circles.forEach { ci -> ToggleChip(ci.name, s.audience[f.id] == ci.id, { composer.setAudience(f.id, ci.id) }) }
            }
        }
        s.prompts[f.id]?.let { p ->
            ToggleChip(stringResource(R.string.engagement_answers_prompt, p.title), s.answering == p.id, { composer.answer(if (s.answering == p.id) null else p.id) }) {
                Icon(KinIcons.Star, null, androidx.compose.ui.Modifier, tint = c.inkSoft)
            }
        }
    }
}

/** Per-server settings screens: their account is named by the route, not found by an id. */
fun isAccountScoped(r: SocialRoute): Boolean =
    r is RemindersRoute || r is BlockedPeopleRoute || r is MilestonesRoute || r is WeeklySummaryRoute || (r is ReportsRoute && r.feedId == null)

/** The account a per-server settings screen is for (null: the primary one, or an entity's screen). */
fun socialRouteAccount(r: SocialRoute): String? = when (r) {
    is RemindersRoute -> r.accountId
    is BlockedPeopleRoute -> r.accountId
    is MilestonesRoute -> r.accountId
    is WeeklySummaryRoute -> r.accountId
    is ReportsRoute -> r.accountId.takeIf { r.feedId == null }
    else -> null
}

/** The key of a social screen's entity: the routing index tells its account. Null for the merged screens. */
fun socialRouteKey(r: SocialRoute): String? = when (r) {
    is PostRoute -> r.postId
    is ConversationRoute -> r.conversationId
    is ConversationInfoRoute -> r.conversationId
    is PersonRoute -> r.userId
    is CirclesRoute -> r.feedId
    is CircleRoute -> r.feedId
    is ReportsRoute -> r.feedId
    is FeedEngagementRoute -> r.feedId
    is RecapRoute -> r.feedId
    is FreshFrameRoute -> r.frameId
    else -> null
}

/** Home's strip: the server label of frames and families whose name is used on two servers (multi-server.md §2.2). */
private fun homeStripServers(graph: AppGraph, s: app.kinframe.family.ui.home.HomeState): (String) -> String? {
    if (!graph.accounts.several) return { null }
    val entries = s.frames.map { it.id to it.name } + s.feeds.map { it.id to it.name }
    val repeated = app.kinframe.family.accounts.ServerChips.repeatedAcrossServers(entries, { it.first }, { it.second }) { graph.accounts.index.get(it) }
    return { id -> if (id in repeated) graph.accounts.chipFor(id) else null }
}

/** What a family social notification tap needs besides the navigator. */
class SocialTargets(
    /** The merged tabs' flags (any account has it). */
    val tabs: SocialFlags,
    /** The account an id of the push belongs to (the push's own account recorded its ids, AppGraph.routePush). */
    val account: (String?) -> app.kinframe.family.accounts.AccountContext,
    /** Whether the person can send to this frame themselves; false when they are not on it (it asked through a family). */
    val canSendTo: suspend (String) -> Boolean,
    /** The Send tab with the frame, or (not on it) the families that reach it, preselected (README §5.20). */
    val sendTo: (frameId: String, requestId: String?) -> Unit,
    /** An account by its local id ([OpenTarget.accountId], the push's account; null: the primary one). */
    val byAccount: (String?) -> app.kinframe.family.accounts.AccountContext = { account(null) },
)

/** Where a family social notification tap goes (README §6.3). Returns false when it isn't one. */
suspend fun openSocialTarget(t: OpenTarget, nav: Navigator, c: SocialTargets): Boolean {
    val ids = t.ids
    val home: Route = if (c.tabs.social) HomeRoute else Route.Frames
    when (t.target) {
        SocialNotifications.TARGET_POST -> {
            val post = t.postId ?: ids["post_id"] ?: return true
            if (!c.account(post).flags.social) return true
            nav.selectTab(home); nav.push(PostRoute(post, ids["comment_id"]))
        }
        SocialNotifications.TARGET_CONVERSATION -> {
            val conv = ids["conversation_id"] ?: return true
            if (!c.account(conv).flags.chats) return true
            nav.selectTab(if (c.tabs.chats) ChatsRoute else home); nav.push(ConversationRoute(conv))
        }
        SocialNotifications.TARGET_REPORTS -> {
            val feed = t.feedId ?: ids["feed_id"]
            // A family's reports are on the family's server; the operator's on the server that sent the push.
            val a = if (feed != null) c.account(feed) else c.byAccount(t.accountId)
            if (!a.flags.chats) return true
            if (feed == null && !a.isOperator) return true
            nav.selectTab(if (c.tabs.social) HomeRoute else Route.Settings); nav.push(ReportsRoute(feed, if (feed == null) a.accountId else null))
        }
        SocialNotifications.TARGET_WEEKLY_SUMMARY -> {
            val week = ids["week_of"] ?: return true
            // The summary of the server that sent the push (each server has its own).
            val a = c.byAccount(t.accountId)
            if (!a.flags.engagement) return true
            nav.selectTab(home); nav.push(WeeklySummaryRoute(week, a.accountId))
        }
        // "Oma's frame would like new photos" or a quiet-frame reminder: the frame, or, for someone who isn't on it
        // (it reached them through a family), the Send tab with the families that reach the frame.
        SocialNotifications.TARGET_FRAME_REQUEST -> {
            val frame = t.frameId ?: ids["frame_id"] ?: return true
            val request = ids["request_id"]
            when {
                !c.canSendTo(frame) -> c.sendTo(frame, request)
                c.account(frame).flags.engagement -> { nav.selectTab(home); nav.push(FreshFrameRoute(frame)) }
                else -> { nav.selectTab(Route.Frames); nav.push(Route.FrameDetail(frame)) }
            }
        }
        SocialNotifications.TARGET_HOME -> {
            val feed = t.feedId ?: ids["feed_id"]
            when {
                c.tabs.social -> nav.selectTab(HomeRoute)
                feed != null && c.account(feed).discovery?.feeds == true -> { nav.selectTab(Route.Feeds); nav.push(Route.FeedDetail(feed)) }
                else -> nav.selectTab(Route.Frames)
            }
        }
        else -> return false
    }
    return true
}

@Composable
fun BadgedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    if (count <= 0) { Icon(icon, null); return }
    androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge(containerColor = Kin.colors.accent) { Text(if (count > 99) "99+" else count.toString()) } }) {
        Icon(icon, null)
    }
}

@Composable
fun OverflowActions(onHistory: (() -> Unit)?, onSettings: (() -> Unit)?) {
    onHistory?.let { IconButton(it) { Icon(KinIcons.History, stringResource(R.string.nav_history), tint = Kin.colors.inkSoft) } }
    onSettings?.let { IconButton(it) { Icon(KinIcons.Settings, stringResource(R.string.nav_settings), tint = Kin.colors.inkSoft) } }
}
