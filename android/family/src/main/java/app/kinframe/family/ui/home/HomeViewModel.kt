package app.kinframe.family.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.social.FeedPrompt
import app.kinframe.core.social.Milestone
import app.kinframe.core.social.MilestoneUpdate
import app.kinframe.core.social.OnThisDay
import app.kinframe.core.social.PromptStatus
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.ReminderPreferences
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.TimelinePost
import app.kinframe.core.social.WeeklySummary
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.social.HomeEntry
import app.kinframe.family.ui.social.SocialProblems
import app.kinframe.family.ui.social.ThreadLogic
import app.kinframe.family.ui.social.TimelineLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class HomeState(
    val posts: Load<List<TimelinePost>> = Load.Loading,
    val nextBefore: String? = null,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    /** The strip: frames and families with their freshness rings and photo requests. */
    val frames: List<Frame> = emptyList(),
    val feeds: List<Feed> = emptyList(),
    val onThisDay: OnThisDay? = null,
    /** New personal milestones whose card wasn't dismissed. */
    val milestones: List<Milestone> = emptyList(),
    /** This week's prompts of families that have prompts on. */
    val prompts: List<FeedPrompt> = emptyList(),
    /** Last week's "seen and loved" summaries: one per server that has one (multi-server.md §2.2). */
    val summaries: List<HomeSummary> = emptyList(),
    val reminders: ReminderPreferences? = null,
    // ---- the current family (docs/spec/simple-app.md §3.1)
    /** The family's people, for the row under the top bar. */
    val members: List<FeedMember> = emptyList(),
    /** The frames that show the family (active subscriptions). */
    val familyFrames: List<SubscriptionFrame> = emptyList(),
    /** A frame's last placed photo (frames the person may see the library of). */
    val frameThumbs: Map<String, String> = emptyMap(),
) {
    /** A post sent to several families shows once (docs/spec/simple-app.md §3.1). */
    val entries: List<HomeEntry> get() = TimelineLogic.entries(app.kinframe.family.ui.family.Families.once((posts as? Load.Ready)?.value.orEmpty()))

    /** Frames that asked for new photos and haven't been answered. */
    val openRequests: List<Frame> get() = frames.filter { it.photoRequest?.isOpen == true }
}

/** One server's weekly summary card on Home; [accountId] opens that server's summary (null: the only account). */
data class HomeSummary(val summary: WeeklySummary, val accountId: String? = null)

sealed interface HomeNotice {
    data class Message(@StringRes val message: Int) : HomeNotice
}

/**
 * Home (docs/spec/family-social.md §4): every family's posts and the caller's frame posts, newest first; above them
 * the frames and families with freshness rings, open photo requests, On this day, prompts and milestones.
 */
class HomeViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val engagement: Boolean,
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Whether a user id is the person (any of their accounts: Home merges every server's timeline). */
    private val isMe: (String?) -> Boolean = { it != null && it == me() },
    /**
     * Every server's summary of a week, by account (merged Home); by default the one client's, without an account.
     * Servers without one that week are left out.
     */
    private val summaries: suspend (String) -> List<HomeSummary> = { week -> listOf(HomeSummary(api.weeklySummary(week))) },
    /** Home's filter chip: that family's timeline only; null is every family together. */
    val feedId: String? = null,
    /** The family of the people and frames row (the filtered one; with All the home family). */
    private val rowFeedId: String? = feedId,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<HomeNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeNotice> = _events.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch { live.collect(::onLive) }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(refreshing = it.posts is Load.Ready) }
        launch { loadStrip() }
        if (engagement) launch { loadEngagement() }
        try {
            val page = api.timeline(feedId = feedId)
            _state.update { it.copy(posts = Load.Ready(page.posts), nextBefore = page.nextBefore, refreshing = false) }
        } catch (e: Exception) {
            _state.update { s -> if (s.posts is Load.Ready) s.copy(refreshing = false) else s.copy(posts = Load.Error(SocialProblems.message(e)), refreshing = false) }
        }
    }

    private suspend fun loadStrip() {
        val frames = runCatching { kin.frames() }.getOrNull()
        frames?.let { f -> _state.update { it.copy(frames = f.sortedBy { x -> x.name.lowercase() }) } }
        runCatching { kin.feeds() }.onSuccess { f -> _state.update { it.copy(feeds = f.sortedBy { x -> x.name.lowercase() }) } }
        val id = rowFeedId ?: return
        runCatching { kin.feedMembers(id) }.onSuccess { m -> _state.update { it.copy(members = HomeRow.people(m, me())) } }
        val shown = runCatching { kin.feedSubscriptions(id, SubscriptionStatus.ACTIVE) }.getOrNull()?.map { it.frame } ?: return
        _state.update { it.copy(familyFrames = shown.sortedBy { f -> f.name.lowercase() }) }
        val thumbs = shown.mapNotNull { sf ->
            val f = frames?.firstOrNull { it.id == sf.id }?.takeIf { it.can { p -> p.seeLibrary } } ?: return@mapNotNull null
            runCatching { kin.placements(f.id, limit = 1).placements.firstOrNull()?.item?.thumbUrl }.getOrNull()?.let { f.id to it }
        }.toMap()
        _state.update { it.copy(frameThumbs = thumbs) }
    }

    private suspend fun loadEngagement() {
        runCatching { api.onThisDay() }.onSuccess { d -> _state.update { it.copy(onThisDay = d.takeIf { x -> x.groups.isNotEmpty() }) } }
        runCatching { api.milestones() }.onSuccess { m -> _state.update { it.copy(milestones = m.filter { x -> !x.seen }) } }
        runCatching { api.reminderPreferences() }.onSuccess { r -> _state.update { it.copy(reminders = r) } }
        // Last week's "seen and loved" summary, when there is one (404 otherwise).
        runCatching { summaries(lastWeek()) }.onSuccess { w -> _state.update { it.copy(summaries = w.filter { x -> x.summary.seenCount > 0 }) } }
        val feeds = runCatching { kin.feeds() }.getOrDefault(emptyList()).filter { feedId == null || it.id == feedId }
        val prompts = feeds.take(MAX_PROMPT_FEEDS).mapNotNull { f ->
            runCatching { api.feedPrompts(f.id) }.getOrNull()?.firstOrNull { it.status == PromptStatus.CURRENT && it.title.isNotBlank() }
        }
        _state.update { it.copy(prompts = prompts) }
    }

    /** The Monday of the week that just ended. */
    fun lastWeek(): String = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).toString()

    fun loadMore() {
        val s = state.value
        val cursor = s.nextBefore ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.timeline(before = cursor, feedId = feedId)
                _state.update { st ->
                    val have = (st.posts as? Load.Ready)?.value.orEmpty()
                    st.copy(posts = Load.Ready(have + page.posts.filter { p -> have.none { it.id == p.id } }), nextBefore = page.nextBefore, loadingMore = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false) }
                _events.tryEmit(HomeNotice.Message(SocialProblems.message(e)))
            }
        }
    }

    private fun posts(f: (List<TimelinePost>) -> List<TimelinePost>) = _state.update { s ->
        (s.posts as? Load.Ready)?.let { s.copy(posts = Load.Ready(f(it.value))) } ?: s
    }

    /** React to a post from the timeline; null removes the caller's reaction. */
    fun react(post: TimelinePost, emoji: String?) = viewModelScope.launch {
        posts { TimelineLogic.upsertThread(it, post.id) { t -> t.copy(reactions = t.reactions.withMine(emoji)) } }
        try {
            val summary = api.setReaction(ReactionTarget.POST, post.id, emoji)
            posts { TimelineLogic.upsertThread(it, post.id) { t -> t.copy(reactions = summary) } }
        } catch (e: Exception) {
            posts { TimelineLogic.upsertThread(it, post.id) { t -> t.copy(reactions = post.thread.reactions) } }
            _events.tryEmit(HomeNotice.Message(SocialProblems.message(e)))
        }
    }

    fun dismissMilestone(key: String) = viewModelScope.launch {
        _state.update { s -> s.copy(milestones = s.milestones.filterNot { it.key == key }) }
        runCatching { api.updateMilestone(key, MilestoneUpdate(seen = true)) }
    }

    /** "We sent you fewer reminders": shown once (README §5.20). */
    fun ackAutoReduced() = viewModelScope.launch {
        _state.update { s -> s.copy(reminders = s.reminders?.copy(autoReduced = false)) }
        runCatching { api.updateReminderPreferences(ReminderPreferencesUpdate(autoReducedSeen = true)) }
    }

    fun dismissSummary(card: HomeSummary) = _state.update { s -> s.copy(summaries = s.summaries - card) }

    private fun onLive(e: LiveEvent) {
        when (e) {
            is SocialLive.PostUpdated -> e.thread?.let { t -> posts { TimelineLogic.upsertThread(it, e.postId) { t } } }
            is SocialLive.PostRemoved -> posts { l -> l.filterNot { it.id == e.postId } }
            is SocialLive.CommentCreated -> posts { TimelineLogic.upsertThread(it, e.postId) { t -> ThreadLogic.withComment(t, e.comment, isMe(e.comment.author?.userId)) } }
            is SocialLive.ReactionUpdated -> if (e.targetKind == ReactionTarget.POST) posts { TimelineLogic.upsertThread(it, e.targetId) { t -> t.copy(reactions = e.summary) } }
            is SocialLive.ThreadRead -> posts { TimelineLogic.upsertThread(it, e.postId) { t -> t.copy(readUpTo = e.readUpTo, unreadCount = 0, unreadMentions = 0) } }
            // The frame's own members and its families' members hear of a request too (README §5.20): a frame not in the
            // strip (reached through a family) only refreshes it.
            is SocialLive.PhotoRequestChanged -> if (state.value.frames.any { it.id == e.request.frameId }) {
                _state.update { s -> s.copy(frames = s.frames.map { if (it.id == e.request.frameId) it.copy(photoRequest = e.request) else it }) }
            } else viewModelScope.launch { loadStrip() }
            is SocialLive.StreakUpdated -> _state.update { s -> s.copy(feeds = s.feeds.map { if (it.id == e.feedId) it.copy(streak = e.streak) else it }) }
            is SocialLive.PromptUpdated -> _state.update { s ->
                val others = s.prompts.filterNot { it.feedId == e.feedId }
                s.copy(prompts = if (e.prompt.status == PromptStatus.CURRENT && e.prompt.title.isNotBlank()) others + e.prompt else others)
            }
            is SocialLive.MilestoneAchieved -> _state.update { s -> s.copy(milestones = s.milestones.filterNot { it.key == e.milestone.key } + e.milestone) }
            is LiveEvent.SubscriptionUpdated -> if (e.feedId == feedId) viewModelScope.launch { loadStrip() }
            is LiveEvent.FeedMembershipUpdated, is SocialLive.CircleUpdated, is SocialLive.CircleRemoved -> refresh()
            else -> Unit
        }
    }

    companion object {
        const val MAX_PROMPT_FEEDS = 6
    }
}

/** The row of the family on Home (docs/spec/simple-app.md §3.1). */
object HomeRow {
    /** The people, the person first; nobody twice. */
    fun people(members: List<FeedMember>, me: String?): List<FeedMember> =
        members.distinctBy { it.userId }.sortedWith(compareBy<FeedMember>({ it.userId != me }, { it.displayName.lowercase() }))

    /** Only the person is in the family: Home invites the others. */
    fun alone(members: List<FeedMember>, me: String?): Boolean = members.isNotEmpty() && members.all { it.userId == me }

    /** Posts with something new for the person: the Activity list. */
    fun activity(posts: List<TimelinePost>): List<TimelinePost> = posts.filter { it.thread.unreadMentions > 0 || it.thread.unreadCount > 0 }
}
