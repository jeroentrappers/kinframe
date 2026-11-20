package app.kinframe.family.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the app can be (docs/spec/simple-app.md §3). The tabs (Home, Frames, Chats, Family) are roots; everything else,
 * the composer included, is pushed on top of one.
 */
sealed interface Route {
    data object Frames : Route
    /** The Family tab: the profile of the person's one family, or the list of their families (docs/spec/simple-app.md §3.4). */
    data object Family : Route
    /** The composer (`+`): to one or more families (or circles of them), or (from a frame's screen) that frame only. */
    data object Send : Route
    /** Settings › Sent history: what the person sent, with delivery per frame. */
    data object History : Route
    /** The gear: This family ([feedId], from a family's profile) and App; App only from the list of families. */
    data class Settings(val feedId: String? = null) : Route
    /** One frame: [tab] Overview is the frame's screen (a list); the others are the pages its rows open. */
    data class FrameDetail(val frameId: String, val tab: FrameTab = FrameTab.Overview) : Route
    /**
     * Pair a frame with its code ([code] from a link), or ([join]) type a family's invite code. With [family], a frame
     * you manage that was paired is added to that family at once.
     */
    data class AddFrame(val code: String? = null, val family: String? = null, val join: Boolean = false) : Route
    data class Invite(val token: String) : Route
    data class SendDetail(val sendId: String, val highlightPlacement: String? = null) : Route
    /**
     * A page of a family's settings: [tab] Birthdays, Sources or Settings (name, cover, rules, leaving). Posts, People
     * and Frames show that family's profile.
     */
    data class FeedDetail(val feedId: String, val tab: FeedTab = FeedTab.Settings) : Route
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
    /** Settings → Accounts → one server: that server's own settings, with several accounts (multi-server.md §2.1). */
    data class AccountSettings(val accountId: String) : Route

    val isTab: Boolean get() = this == Frames || this == Family || this == HomeRoute || this == ChatsRoute
}

/**
 * The pages of one frame. [Overview] is the frame's screen: the card and one list of rows. [Settings] is "Frame
 * settings and help", which leads to [Feeds] (which family it shows), [People], [Device] (remote control, details,
 * health, transfer and leaving) and [Display] (slideshow and display settings, email and Telegram). [Extras] is
 * "Calendar, weather and add-ons".
 */
enum class FrameTab { Overview, Library, People, Feeds, Calendar, Settings, Device, Display, Extras }

enum class FeedTab { Posts, People, Frames, Birthdays, Sources, Settings }

/** A tiny back stack: enough for four tabs with detail screens, no navigation library needed. */
class Navigator(start: Route = HomeRoute) {
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

    /** The composer, on top of whatever is open. */
    fun compose() = push(Route.Send)

    /** A frame's screen, and on top of it one of its pages. */
    fun openFrame(frameId: String, tab: FrameTab = FrameTab.Overview) {
        selectTab(Route.Frames)
        push(Route.FrameDetail(frameId))
        when (tab) {
            FrameTab.Overview -> Unit
            // The pages under "Frame settings and help" open on top of it.
            FrameTab.People, FrameTab.Feeds, FrameTab.Device, FrameTab.Display -> { push(Route.FrameDetail(frameId, FrameTab.Settings)); push(Route.FrameDetail(frameId, tab)) }
            FrameTab.Calendar -> { push(Route.FrameDetail(frameId, FrameTab.Extras)); push(Route.FrameDetail(frameId, tab)) }
            else -> push(Route.FrameDetail(frameId, tab))
        }
    }

    /** The Family tab's segment (Photos, People, Frames) when it shows the one family's profile. */
    var familySegment by mutableStateOf(FeedTab.Posts)

    /** The person is in one family: the Family tab is its profile (else a list of families). */
    var singleFamily = true

    /**
     * A family's profile on a segment, or a page of its settings (Birthdays, Sources, Settings) on top of its
     * Settings.
     */
    fun openFamily(feedId: String, tab: FeedTab = FeedTab.Posts) {
        selectTab(Route.Family)
        val profile = tab == FeedTab.Posts || tab == FeedTab.People || tab == FeedTab.Frames
        if (singleFamily) {
            if (profile) familySegment = tab
        } else push(Route.FeedDetail(feedId, if (profile) tab else FeedTab.Posts))
        if (!profile) { push(Route.Settings(feedId)); push(Route.FeedDetail(feedId, tab)) }
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
