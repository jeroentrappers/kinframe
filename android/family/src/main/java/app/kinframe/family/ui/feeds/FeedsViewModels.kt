package app.kinframe.family.ui.feeds

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedCreate
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInviteAccept
import app.kinframe.core.api.FeedInviteCreate
import app.kinframe.core.api.FeedInvitePreview
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.core.api.FeedInviteResult
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.FeedPostReceipts
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.api.SubscriptionUpdate
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Who may do what in a feed (docs/spec/feeds.md §4.1). The server enforces it; the app only hides what won't work. */
object FeedRules {
    /** Receipts and reactions: own posts, owner/admins, or everyone with *members see reactions*. */
    fun canSeeReceipts(feed: Feed, post: FeedPost, me: String?, social: Boolean = false): Boolean =
        post.poster.userId == me || feed.isAdmin || if (social) feed.membersSeeReceipts else feed.membersSeeReactions

    /** Unsend your own post; owner/admins remove anyone's. */
    fun canRemovePost(feed: Feed, post: FeedPost, me: String?): Boolean = post.poster.userId == me || feed.isAdmin

    /** Owner: anyone but the owner. Admins: posters only. Nobody edits themselves here. */
    fun canManageMember(feed: Feed, member: FeedMember, me: String?): Boolean = when {
        member.userId == me || member.role == FeedRole.OWNER -> false
        feed.isOwner -> true
        feed.role == FeedRole.ADMIN -> member.role == FeedRole.POSTER || member.role == FeedRole.MEMBER
        else -> false
    }

    /** A history option as the API wants it: `last_n` needs a count in 1…1000. */
    fun history(option: String, count: Int?): Pair<String, Int?> = when (option) {
        SubscriptionHistory.LAST_N -> option to (count ?: DEFAULT_LAST_N).coerceIn(1, SubscriptionHistory.MAX_COUNT)
        SubscriptionHistory.ALL -> option to null
        else -> SubscriptionHistory.NONE to null
    }

    const val DEFAULT_LAST_N = 50
}

sealed interface FeedNotice {
    data class Message(@StringRes val message: Int, val args: List<String> = emptyList()) : FeedNotice
    data class Error(@StringRes val message: Int) : FeedNotice
    data class ShareInvite(val invite: FeedInvite, val feedName: String) : FeedNotice
    /** The feed is gone for you (left, removed, deleted). */
    data object Gone : FeedNotice
}

private fun Exception.feedProblem(): Int = when ((this as? ApiException)?.code) {
    ProblemCode.LIMIT_REACHED -> R.string.problem_limit_reached
    ProblemCode.ALREADY_SUBSCRIBED -> R.string.problem_already_subscribed
    ProblemCode.INVALID_TRANSITION, ProblemCode.ALREADY_DECIDED -> R.string.problem_invalid_transition
    else -> Problems.message(this)
}

// ---------------------------------------------------------------------------------------------------------- list

data class FeedsState(
    val feeds: Load<List<Feed>> = Load.Loading,
    val creating: Boolean = false,
    @StringRes val createError: Int? = null,
)

/** The Feeds tab: your feeds, and "New feed". */
class FeedsViewModel(private val api: KinApi, live: Flow<LiveEvent>, private val newKey: () -> String = { UUID.randomUUID().toString() }) : ViewModel() {
    private val _state = MutableStateFlow(FeedsState())
    val state: StateFlow<FeedsState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.FeedMembershipUpdated, is LiveEvent.SubscriptionRequested, is LiveEvent.FeedJoinRequested -> refresh()
                    else -> Unit
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        try {
            _state.update { it.copy(feeds = Load.Ready(api.feeds().sortedBy { f -> f.name.lowercase() })) }
        } catch (e: Exception) {
            if (state.value.feeds !is Load.Ready) _state.update { it.copy(feeds = Load.Error(e.feedProblem())) }
        }
    }

    fun upsert(feed: Feed) = _state.update { s ->
        val list = (s.feeds as? Load.Ready)?.value.orEmpty().filterNot { it.id == feed.id } + feed
        s.copy(feeds = Load.Ready(list.sortedBy { it.name.lowercase() }))
    }

    fun remove(feedId: String) = _state.update { s ->
        (s.feeds as? Load.Ready)?.let { s.copy(feeds = Load.Ready(it.value.filterNot { f -> f.id == feedId })) } ?: s
    }

    /** "New feed": the caller becomes its owner. */
    fun create(name: String, onCreated: (Feed) -> Unit) {
        val n = name.trim().take(NAME_MAX)
        if (n.isEmpty() || state.value.creating) return
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val f = api.createFeed(FeedCreate(n), newKey())
                upsert(f)
                _state.update { it.copy(creating = false) }
                onCreated(f)
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, createError = e.feedProblem()) }
            }
        }
    }

    companion object {
        const val NAME_MAX = 40
    }
}

// ---------------------------------------------------------------------------------------------------------- one feed

data class FeedDetailState(
    val feed: Load<Feed> = Load.Loading,
    val posts: Load<List<FeedPost>> = Load.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val members: List<FeedMember> = emptyList(),
    val requests: List<JoinRequest> = emptyList(),
    val invites: List<FeedInvite> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** The post whose per-frame receipts are open, and those receipts. */
    val receiptsFor: String? = null,
    val receipts: Load<FeedPostReceipts>? = null,
    val busy: Boolean = false,
    val creatingInvite: Boolean = false,
) {
    val feedOrNull: Feed? get() = (feed as? Load.Ready)?.value
    val pendingSubscriptions: List<Subscription> get() = subscriptions.filter { it.status == SubscriptionStatus.PENDING }
    val liveSubscriptions: List<Subscription> get() = subscriptions.filter { it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.PAUSED }
    val endedSubscriptions: List<Subscription> get() = subscriptions.filter { it.status == SubscriptionStatus.REJECTED || it.status == SubscriptionStatus.REMOVED }
}

/** A feed: posts with receipts, members and join requests, invites, subscriptions and settings. */
class FeedDetailViewModel(
    private val api: KinApi,
    val feedId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(FeedDetailState())
    val state: StateFlow<FeedDetailState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<FeedNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<FeedNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.FeedPostUpdated -> if (e.feedId == feedId) updatePost(e.postId) { it.copy(receipts = e.summary) }
                    is LiveEvent.SubscriptionRequested -> if (e.feedId == feedId) loadSubscriptions()
                    is LiveEvent.SubscriptionUpdated -> if (e.feedId == feedId) loadSubscriptions()
                    is LiveEvent.FeedJoinRequested -> if (e.feedId == feedId) loadRequests()
                    is LiveEvent.FeedMembershipUpdated -> if (e.feedId == feedId) load()
                    is LiveEvent.Reaction -> if (e.feedId == feedId && e.postId != null) refreshPost(e.postId)
                    is LiveEvent.Reply -> if (e.feedId == feedId && e.postId != null) refreshPost(e.postId)
                    else -> Unit
                }
            }
        }
    }

    private fun updatePost(postId: String, f: (FeedPost) -> FeedPost) = _state.update { s ->
        val list = (s.posts as? Load.Ready)?.value ?: return@update s
        s.copy(posts = Load.Ready(list.map { if (it.id == postId) f(it) else it }))
    }

    private fun refreshPost(postId: String) = viewModelScope.launch {
        runCatching { api.feedPost(feedId, postId) }.onSuccess { p -> updatePost(postId) { p } }
        if (state.value.receiptsFor == postId) openReceipts(postId)
    }

    fun load() = viewModelScope.launch {
        val feed = try {
            api.feed(feedId).also { f -> _state.update { it.copy(feed = Load.Ready(f)) } }
        } catch (e: Exception) {
            if ((e as? ApiException)?.status == 404 && state.value.feed is Load.Ready) { _events.tryEmit(FeedNotice.Gone); return@launch }
            if (state.value.feed !is Load.Ready) _state.update { it.copy(feed = Load.Error(e.feedProblem())) }
            return@launch
        }
        launch { loadPosts() }
        launch { runCatching { api.feedMembers(feedId) }.onSuccess { m -> _state.update { it.copy(members = sortMembers(m)) } } }
        launch { loadSubscriptions() }
        if (feed.isAdmin) launch { loadRequests() }
        if (feed.canInvite) launch { runCatching { api.feedInvites(feedId) }.onSuccess { i -> _state.update { it.copy(invites = i) } } }
    }

    private fun sortMembers(m: List<FeedMember>) =
        m.sortedWith(compareBy<FeedMember>({ when (it.role) { FeedRole.OWNER -> 0; FeedRole.ADMIN -> 1; else -> 2 } }, { it.displayName.lowercase() }))

    private suspend fun loadPosts() {
        try {
            val page = api.feedPosts(feedId)
            _state.update { it.copy(posts = Load.Ready(page.posts), nextCursor = page.nextCursor) }
        } catch (e: Exception) {
            if (state.value.posts !is Load.Ready) _state.update { it.copy(posts = Load.Error(e.feedProblem())) }
        }
    }

    private suspend fun loadRequests() {
        runCatching { api.feedJoinRequests(feedId) }.onSuccess { r -> _state.update { it.copy(requests = r) } }
    }

    private suspend fun loadSubscriptions() {
        runCatching { api.feedSubscriptions(feedId) }.onSuccess { s -> _state.update { it.copy(subscriptions = s) } }
    }

    fun loadMore() {
        val s = state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.feedPosts(feedId, cursor = cursor)
                _state.update { st ->
                    val list = (st.posts as? Load.Ready)?.value.orEmpty()
                    st.copy(posts = Load.Ready(list + page.posts.filterNot { n -> list.any { it.id == n.id } }), nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (_: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Per-frame receipts of one post (feeds.md §7). */
    fun openReceipts(postId: String) {
        _state.update { it.copy(receiptsFor = postId, receipts = if (it.receiptsFor == postId) it.receipts ?: Load.Loading else Load.Loading) }
        viewModelScope.launch {
            try {
                val r = api.feedPostReceipts(feedId, postId)
                if (state.value.receiptsFor == postId) _state.update { it.copy(receipts = Load.Ready(r)) }
            } catch (e: Exception) {
                if (state.value.receiptsFor == postId) _state.update { it.copy(receipts = Load.Error(e.feedProblem())) }
            }
        }
    }

    fun closeReceipts() = _state.update { it.copy(receiptsFor = null, receipts = null) }

    private fun act(@StringRes done: Int?, args: List<String> = emptyList(), block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
                done?.let { _events.tryEmit(FeedNotice.Message(it, args)) }
            } catch (e: Exception) {
                _events.tryEmit(FeedNotice.Error(e.feedProblem()))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---- posts

    /** Unsend (own) or remove (admin) a whole post: it leaves every subscribed frame. */
    fun removePost(post: FeedPost) = act(R.string.feed_post_removed) {
        api.removeFeedPost(feedId, post.id)
        _state.update { s -> s.copy(posts = (s.posts as? Load.Ready)?.let { Load.Ready(it.value.filterNot { p -> p.id == post.id }) } ?: s.posts) }
    }

    /** Remove one photo of a post; the post goes when its last item does. */
    fun removePostItem(post: FeedPost, itemId: String) = act(R.string.feed_item_removed) {
        api.removeFeedPostItem(feedId, post.id, itemId)
        _state.update { s ->
            val list = (s.posts as? Load.Ready)?.value ?: return@update s
            s.copy(posts = Load.Ready(list.mapNotNull { p ->
                if (p.id != post.id) p else p.copy(items = p.items.filterNot { it.id == itemId }).takeIf { it.items.isNotEmpty() }
            }))
        }
    }

    // ---- settings

    private fun patch(field: String, value: JsonElement, @StringRes done: Int = R.string.feed_saved) = act(done) {
        val f = api.updateFeed(feedId, buildJsonObject { put(field, value) })
        _state.update { it.copy(feed = Load.Ready(f)) }
    }

    fun rename(name: String) {
        val n = name.trim().take(FeedsViewModel.NAME_MAX)
        if (n.isNotEmpty()) patch("name", JsonPrimitive(n))
    }

    /** One of the feed's own items, or null to remove the cover. */
    fun setCover(itemId: String?) = patch("cover_item_id", itemId?.let { JsonPrimitive(it) } ?: JsonNull, R.string.feed_cover_saved)

    /** `auto_approve_subscriptions`, `invite_requires_approval`, `members_see_reactions` or `posters_can_invite`. */
    fun setFlag(field: String, value: Boolean) = patch(field, JsonPrimitive(value))

    /** The caller's own "Birthday notifications" for this feed (birthdays.md §2): morning notice and greetings. */
    fun setBirthdayNotifications(on: Boolean) = act(if (on) R.string.birthday_notifications_on else R.string.birthday_notifications_off) {
        val f = api.updateFeedPreferences(feedId, app.kinframe.core.api.FeedPreferencesUpdate(birthdayNotifications = on))
        _state.update { it.copy(feed = Load.Ready(f)) }
    }

    // ---- members

    fun setRole(m: FeedMember, role: String) = act(R.string.feed_member_saved, listOf(m.displayName)) {
        val updated = api.updateFeedMember(feedId, m.userId, role)
        _state.update { s -> s.copy(members = sortMembers(s.members.map { if (it.userId == m.userId) updated else it })) }
    }

    fun removeMember(m: FeedMember, removePosts: Boolean) = act(R.string.feed_member_removed, listOf(m.displayName)) {
        api.removeFeedMember(feedId, m.userId, removePosts)
        _state.update { s -> s.copy(members = s.members.filterNot { it.userId == m.userId }) }
        if (removePosts) loadPosts()
    }

    fun leave(removeMyPosts: Boolean) = act(null) {
        api.leaveFeed(feedId, removeMyPosts)
        _events.tryEmit(FeedNotice.Gone)
    }

    /** Owner only: [m] becomes owner, you become admin. */
    fun transfer(m: FeedMember) = act(R.string.feed_transferred, listOf(m.displayName)) {
        api.transferFeed(feedId, m.userId)
        _state.update { it.copy(feed = Load.Ready(api.feed(feedId)), members = sortMembers(api.feedMembers(feedId))) }
    }

    fun delete() = act(null) {
        api.deleteFeed(feedId)
        _events.tryEmit(FeedNotice.Gone)
    }

    fun decide(r: JoinRequest, approve: Boolean) = act(if (approve) R.string.feed_request_approved else R.string.feed_request_denied, listOf(r.user.displayName.orEmpty())) {
        api.decideFeedJoinRequest(feedId, r.id, approve)
        _state.update { s -> s.copy(requests = s.requests.filterNot { it.id == r.id }) }
        if (approve) runCatching { api.feedMembers(feedId) }.onSuccess { m -> _state.update { it.copy(members = sortMembers(m)) } }
    }

    // ---- invites

    fun createInvite(purpose: String, single: Boolean, hours: Int, note: String) {
        if (state.value.creatingInvite) return
        _state.update { it.copy(creatingInvite = true) }
        viewModelScope.launch {
            try {
                val inv = api.createFeedInvite(feedId, FeedInviteCreate(purpose, if (single) 1 else null, hours, note.trim().ifEmpty { null }), newKey())
                _state.update { it.copy(invites = listOf(inv) + it.invites) }
                _events.tryEmit(FeedNotice.ShareInvite(inv, state.value.feedOrNull?.name.orEmpty()))
            } catch (e: Exception) {
                _events.tryEmit(FeedNotice.Error(e.feedProblem()))
            } finally {
                _state.update { it.copy(creatingInvite = false) }
            }
        }
    }

    fun revokeInvite(inv: FeedInvite) = act(R.string.people_revoked) {
        api.revokeFeedInvite(feedId, inv.id)
        _state.update { s -> s.copy(invites = s.invites.filterNot { it.id == inv.id }) }
    }

    // ---- subscriptions (feed side)

    private fun replaceSub(sub: Subscription) = _state.update { s -> s.copy(subscriptions = s.subscriptions.filterNot { it.id == sub.id } + sub) }

    fun approve(sub: Subscription) = act(R.string.feed_sub_approved, listOf(sub.frame.name)) { replaceSub(api.approveSubscription(sub.id)) }

    fun reject(sub: Subscription) = act(R.string.feed_sub_rejected, listOf(sub.frame.name)) { replaceSub(api.rejectSubscription(sub.id)) }

    /** A feed admin ends a subscription (the frame's managers are told). */
    fun removeSubscription(sub: Subscription) = act(R.string.feed_sub_removed, listOf(sub.frame.name)) {
        api.removeSubscription(sub.id)
        _state.update { s -> s.copy(subscriptions = s.subscriptions.filterNot { it.id == sub.id }) }
        loadSubscriptions()
    }

    /** "Show on a frame": subscribe a frame you manage (pending unless you are a feed admin or auto-approve is on). */
    fun subscribe(frameId: String, option: String, count: Int?, birthdays: Boolean = true) {
        val (history, n) = FeedRules.history(option, count)
        act(null) {
            val sub = api.requestSubscription(feedId, SubscriptionRequest(frameId, history, n, birthdays), newKey())
            replaceSub(sub)
            _events.tryEmit(FeedNotice.Message(if (sub.status == SubscriptionStatus.PENDING) R.string.feed_sub_requested else R.string.feed_sub_active, listOf(sub.frame.name)))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- a frame's feeds

data class FrameFeedsState(
    val subscriptions: Load<List<Subscription>> = Load.Loading,
    /** Feeds you are in that this frame doesn't subscribe to yet (for "Subscribe to a feed"). */
    val myFeeds: List<Feed> = emptyList(),
    /** `slideshow.feed_id`: the feed the slideshow plays, or null for everything. */
    val playingFeed: String? = null,
    val settingsVersion: Int? = null,
    val busy: Boolean = false,
) {
    val live: List<Subscription> get() = (subscriptions as? Load.Ready)?.value.orEmpty().filter { it.isLive }
    val ended: List<Subscription> get() = (subscriptions as? Load.Ready)?.value.orEmpty().filter { !it.isLive }
    val subscribable: List<Feed> get() = myFeeds.filter { f -> live.none { it.feed.id == f.id } }
}

/** A frame's Feeds tab: its subscriptions (pause, resume, history, unsubscribe), subscribing, and feed mode. */
class FrameFeedsViewModel(
    private val api: KinApi,
    val frameId: String,
    /** Owner, admin or `change_settings`: the frame side of subscriptions and feed mode. */
    private val manage: Boolean,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(FrameFeedsState())
    val state: StateFlow<FrameFeedsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<FeedNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<FeedNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e -> if (e is LiveEvent.SubscriptionUpdated && e.frameId == frameId) loadSubscriptions() }
        }
    }

    fun load() = viewModelScope.launch {
        launch { loadSubscriptions() }
        if (manage) {
            launch { runCatching { api.feeds() }.onSuccess { f -> _state.update { it.copy(myFeeds = f.sortedBy { x -> x.name.lowercase() }) } } }
            launch {
                runCatching { api.settings(frameId) }.onSuccess { d -> _state.update { it.copy(playingFeed = d.typed.slideshow.feedId, settingsVersion = d.version) } }
            }
        }
    }

    private suspend fun loadSubscriptions() {
        try {
            val subs = api.frameSubscriptions(frameId)
            _state.update { it.copy(subscriptions = Load.Ready(subs)) }
        } catch (e: Exception) {
            if (state.value.subscriptions !is Load.Ready) _state.update { it.copy(subscriptions = Load.Error(e.feedProblem())) }
        }
    }

    private fun replace(sub: Subscription) = _state.update { s ->
        val list = (s.subscriptions as? Load.Ready)?.value.orEmpty()
        s.copy(subscriptions = Load.Ready(list.filterNot { it.id == sub.id } + sub))
    }

    private fun act(@StringRes done: Int?, args: List<String>, block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
                done?.let { _events.tryEmit(FeedNotice.Message(it, args)) }
            } catch (e: Exception) {
                _events.tryEmit(FeedNotice.Error(e.feedProblem()))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun subscribe(feed: Feed, option: String, count: Int?, birthdays: Boolean = true) {
        val (history, n) = FeedRules.history(option, count)
        act(null, emptyList()) {
            val sub = api.requestSubscription(feed.id, SubscriptionRequest(frameId, history, n, birthdays), newKey())
            replace(sub)
            _events.tryEmit(FeedNotice.Message(if (sub.status == SubscriptionStatus.PENDING) R.string.feed_sub_requested_frame else R.string.feed_sub_active_frame, listOf(feed.name)))
        }
    }

    /** Pause hides the feed's photos on the frame until resumed (feeds.md §5.1). */
    fun pause(sub: Subscription) = act(R.string.feed_sub_paused, listOf(sub.feed.name)) { replace(api.pauseSubscription(sub.id)) }

    fun resume(sub: Subscription) = act(R.string.feed_sub_resumed, listOf(sub.feed.name)) { replace(api.resumeSubscription(sub.id)) }

    fun setHistory(sub: Subscription, option: String, count: Int?) {
        val (history, n) = FeedRules.history(option, count)
        act(R.string.feed_sub_history_saved, listOf(sub.feed.name)) { replace(api.updateSubscription(sub.id, SubscriptionUpdate(history, n))) }
    }

    /** "Show birthdays" for this subscription (birthdays.md §4.2): the feed's people on the frame or not. */
    fun setBirthdays(sub: Subscription, on: Boolean) =
        act(if (on) R.string.frame_feeds_birthdays_on else R.string.frame_feeds_birthdays_off, listOf(sub.feed.name)) {
            replace(api.updateSubscription(sub.id, SubscriptionUpdate(birthdays = on)))
        }

    /** Unsubscribe, or withdraw a pending request. Feed-only photos leave the frame. */
    fun unsubscribe(sub: Subscription) = act(
        if (sub.status == SubscriptionStatus.PENDING) R.string.feed_sub_withdrawn else R.string.feed_sub_unsubscribed, listOf(sub.feed.name),
    ) {
        api.removeSubscription(sub.id)
        _state.update { s ->
            s.copy(
                subscriptions = Load.Ready((s.subscriptions as? Load.Ready)?.value.orEmpty().filterNot { it.id == sub.id }),
                playingFeed = s.playingFeed.takeIf { it != sub.feed.id },
            )
        }
        loadSubscriptions()
    }

    /** Feed mode: play only [feedId] (clearing album mode), or everything with null. */
    fun playOnly(feedId: String?) = act(if (feedId != null) R.string.feed_mode_on else R.string.feed_mode_off, emptyList()) {
        val version = state.value.settingsVersion ?: api.settings(frameId).version
        val doc = api.patchSettings(frameId, version, feedModePatch(feedId))
        _state.update { it.copy(playingFeed = doc.typed.slideshow.feedId, settingsVersion = doc.version) }
    }

    companion object {
        /** Setting the feed clears album mode (they are mutually exclusive); clearing only clears the feed. */
        fun feedModePatch(feedId: String?): JsonObject = buildJsonObject {
            put("slideshow", buildJsonObject {
                put("feed_id", feedId?.let { JsonPrimitive(it) } ?: JsonNull)
                if (feedId != null) put("album_id", JsonNull)
            })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- invites

data class FeedInviteState(
    val preview: Load<FeedInvitePreview> = Load.Loading,
    val invalid: Boolean = false,
    val accepting: Boolean = false,
    val result: FeedInviteResult? = null,
    @StringRes val error: Int? = null,
    /** `subscribe` invites: the frames to subscribe and the history option. */
    val selected: Set<String> = emptySet(),
    val history: String = SubscriptionHistory.NONE,
    val historyCount: Int = FeedRules.DEFAULT_LAST_N,
    /** Offer "Show birthdays" (the server has birthdays) and its value (default on). */
    val birthdaysAvailable: Boolean = false,
    val birthdays: Boolean = true,
) {
    val isSubscribe: Boolean get() = (preview as? Load.Ready)?.value?.purpose == FeedInvitePurpose.SUBSCRIBE
    val canAccept: Boolean get() = !accepting && (!isSubscribe || selected.isNotEmpty())
}

/** Opens a feed invite link or code: join as a poster, or subscribe frames you manage (README §5.12). */
class FeedInviteViewModel(
    private val api: KinApi,
    private val token: String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    birthdays: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedInviteState(birthdaysAvailable = birthdays))
    val state: StateFlow<FeedInviteState> = _state.asStateFlow()
    private val key = newKey()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(preview = Load.Loading) }
        try {
            val p = api.previewFeedInvite(token)
            // Preselect the only frame that can still subscribe.
            val open = p.frames.filter { !app.kinframe.core.api.SubscriptionStatus.isLive(it.subscriptionStatus) }
            _state.update { it.copy(preview = Load.Ready(p), selected = if (open.size == 1) setOf(open.single().frameId) else emptySet()) }
        } catch (e: Exception) {
            val ae = e as? ApiException
            val invalid = ae != null && (ae.status == 404 || ae.status == 410 || ae.code == ProblemCode.INVITE_INVALID || ae.code == ProblemCode.INVITE_EXPIRED)
            val msg = if (ae?.status == 410 || ae?.code == ProblemCode.INVITE_EXPIRED) R.string.problem_invite_expired
            else if (invalid) R.string.problem_invite_invalid else e.feedProblem()
            _state.update { it.copy(preview = Load.Error(msg), invalid = invalid) }
        }
    }

    fun toggleFrame(frameId: String) = _state.update { s ->
        s.copy(selected = if (frameId in s.selected) s.selected - frameId else s.selected + frameId, error = null)
    }

    fun setHistory(option: String, count: Int?) = _state.update { s ->
        val (h, n) = FeedRules.history(option, count)
        s.copy(history = h, historyCount = n ?: s.historyCount)
    }

    fun setBirthdays(on: Boolean) = _state.update { it.copy(birthdays = on) }

    fun accept() {
        val s = state.value
        if (!s.canAccept) return
        _state.update { it.copy(accepting = true, error = null) }
        viewModelScope.launch {
            try {
                val body = if (s.isSubscribe) {
                    FeedInviteAccept(
                        s.selected.toList(), s.history, if (s.history == SubscriptionHistory.LAST_N) s.historyCount else null,
                        // Default on: only an explicit "off" is sent (servers without birthdays never see the field).
                        birthdays = false.takeIf { s.birthdaysAvailable && !s.birthdays },
                    )
                } else null
                val r = api.acceptFeedInvite(token, body, key)
                _state.update { it.copy(accepting = false, result = r) }
            } catch (e: Exception) {
                _state.update { it.copy(accepting = false, error = e.feedProblem()) }
            }
        }
    }
}
