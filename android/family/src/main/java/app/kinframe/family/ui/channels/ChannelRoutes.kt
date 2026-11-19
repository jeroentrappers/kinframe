package app.kinframe.family.ui.channels

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinframe.core.api.ChannelTarget
import app.kinframe.family.AppGraph
import app.kinframe.family.R
import app.kinframe.family.ui.LocalToaster
import app.kinframe.family.ui.Navigator
import app.kinframe.family.ui.Phase
import app.kinframe.family.ui.Route
import app.kinframe.family.ui.Toaster
import kotlinx.coroutines.flow.SharedFlow

/** The scope of an address's or chat's target. */
fun ChannelTarget.scope(): ChannelScope? =
    if (isFeed) feedId?.let { ChannelScope.feed(it, feedName.orEmpty()) } else frameId?.let { ChannelScope.frame(it, frameName.orEmpty()) }

@Composable
private fun Notices(events: SharedFlow<ChannelNotice>, onGone: () -> Unit = {}) {
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    LaunchedEffect(events) { events.collect { handle(it, context, res, toaster, onGone) } }
}

private fun handle(e: ChannelNotice, context: Context, res: Resources, toaster: Toaster, onGone: () -> Unit) {
    when (e) {
        is ChannelNotice.Message -> toaster.show(res.getString(e.message, *e.args.toTypedArray()))
        is ChannelNotice.Error -> toaster.show(res.getString(e.message))
        ChannelNotice.Gone -> onGone()
        is ChannelNotice.OpenTelegram -> TelegramOpener.open(context, e.url)
        is ChannelNotice.ShareTelegram -> TelegramOpener.share(context, res.getString(R.string.tg_share_subject), res.getString(R.string.tg_share_text, e.url))
    }
}

/** The email-to-frame and Telegram screens (routes [Route.EmailInbox], [Route.EmailAddress], [Route.TelegramChats], [Route.TelegramChat]). */
@Composable
fun ChannelRoute(graph: AppGraph, ready: Phase.Ready, r: Route, nav: Navigator) {
    val api = ready.api
    val k = System.identityHashCode(api)
    // The frame's or family's own server: its flags and limits, and the person's name there.
    val key = when (r) {
        is Route.EmailInbox -> r.scope.id
        is Route.EmailAddress -> r.addressId
        is Route.TelegramChats -> r.scope.id
        is Route.TelegramChat -> r.chatId
        else -> null
    }
    val acct = app.kinframe.family.ui.rememberAccountContext(graph, key, ready)
    val d = acct.discovery ?: ready.server.discovery
    val limits = acct.limits
    val me = acct.me?.displayName ?: ready.me.displayName
    when (r) {
        is Route.EmailInbox -> {
            val vm: EmailInboxViewModel = viewModel(key = "email-${r.scope.kind}-${r.scope.id}-$k") { EmailInboxViewModel(api, r.scope, graph.live.events) }
            val st by vm.state.collectAsStateWithLifecycle()
            Notices(vm.events)
            EmailInboxScreen(st, vm, limits.maxEmailAddressesPerTarget, me, { nav.pop() }) { a -> nav.push(Route.EmailAddress(a.id)) }
        }
        is Route.EmailAddress -> {
            val vm: EmailAddressViewModel = viewModel(key = "email-address-${r.addressId}-$k") { EmailAddressViewModel(api, r.addressId, graph.live.events) }
            val st by vm.state.collectAsStateWithLifecycle()
            Notices(vm.events) { nav.pop() }
            EmailAddressScreen(st, vm, me) { nav.pop() }
        }
        is Route.TelegramChats -> {
            val vm: TelegramChatsViewModel = viewModel(key = "telegram-${r.scope.kind}-${r.scope.id}-$k") { TelegramChatsViewModel(api, r.scope, graph.live.events) }
            val st by vm.state.collectAsStateWithLifecycle()
            Notices(vm.events)
            // Back from Telegram: the chat may be connected already.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
            TelegramChatsScreen(st, vm, d.telegramPrivacyMode, limits.maxTelegramChatsPerTarget, { nav.pop() }) { c -> nav.push(Route.TelegramChat(c.id)) }
        }
        is Route.TelegramChat -> {
            val vm: TelegramChatViewModel = viewModel(key = "telegram-chat-${r.chatId}-$k") { TelegramChatViewModel(api, r.chatId, graph.live.events) }
            val st by vm.state.collectAsStateWithLifecycle()
            Notices(vm.events) { nav.pop() }
            TelegramChatScreen(st, vm, { nav.pop() }) {
                st.chatOrNull?.target?.scope()?.let { nav.replace(Route.TelegramChats(it)) }
            }
        }
        else -> Unit
    }
}

/** Settings → Telegram (discovery `telegram`): link or unlink the person's own Telegram account. */
@Composable
fun TelegramLinkSettings(graph: AppGraph, ready: Phase.Ready) {
    val api = ready.api
    val vm: TelegramLinkViewModel = viewModel(key = "telegram-link-${System.identityHashCode(api)}") { TelegramLinkViewModel(api, graph.live.events) }
    val st by vm.state.collectAsStateWithLifecycle()
    Notices(vm.events)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    TelegramLinkSection(st, vm)
}
