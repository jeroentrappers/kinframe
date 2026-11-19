package app.kinframe.family.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the app can be. The tabs are roots; everything else is pushed on top of one. */
sealed interface Route {
    data object Frames : Route
    data object Send : Route
    data object History : Route
    data object Settings : Route
    data class FrameDetail(val frameId: String, val tab: FrameTab = FrameTab.Overview) : Route
    data class AddFrame(val code: String? = null) : Route
    data class Invite(val token: String) : Route
    data class SendDetail(val sendId: String, val highlightPlacement: String? = null) : Route
    /** The Feeds tab; only when the server has feeds (discovery `feeds`). */
    data object Feeds : Route
    data class FeedDetail(val feedId: String, val tab: FeedTab = FeedTab.Posts, val postId: String? = null) : Route
    data class FeedInvite(val token: String) : Route
    /** Settings → My photo sources (docs/spec/photo-sources.md). */
    data object PhotoSources : Route
    /** "Sync from…" into an album or a feed; [link] when an Immich or iCloud link was shared into the app. */
    data class AddPhotoSource(val target: app.kinframe.family.ui.sources.SourceTarget, val link: String? = null) : Route
    /** "Email photos to this frame/feed" (docs/spec/email-inbox.md): the target's inbox addresses. */
    data class EmailInbox(val scope: app.kinframe.family.ui.channels.ChannelScope) : Route
    /** One inbox address: senders, held mail, recent mail, settings. */
    data class EmailAddress(val addressId: String) : Route
    /** A frame's or feed's Telegram chats (docs/spec/telegram.md). */
    data class TelegramChats(val scope: app.kinframe.family.ui.channels.ChannelScope) : Route
    data class TelegramChat(val chatId: String) : Route

    val isTab: Boolean get() = this == Frames || this == Send || this == History || this == Feeds || this == Settings || this is SocialTab
}

enum class FrameTab { Overview, Library, People, Feeds, Calendar, Settings }

enum class FeedTab { Posts, People, Birthdays, Frames, Sources, Settings }

/** A tiny back stack: enough for four tabs with detail screens, no navigation library needed. */
class Navigator(start: Route = Route.Frames) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    val tab: Route get() = stack.first()

    fun push(route: Route) {
        if (stack.last() != route) stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Switch tab (resetting it to its root). */
    fun selectTab(tab: Route) {
        stack.clear()
        stack.add(tab)
    }

    /** Replace the top screen (e.g. "add frame" → its result). */
    fun replace(route: Route) {
        if (stack.size <= 1) selectTab(route) else stack[stack.lastIndex] = route
    }
}

/** Snackbars with an optional action (undo instead of "are you sure", product spec principle 4). */
class Toaster(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(message, action, withDismissAction = action == null, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("no toaster") }
