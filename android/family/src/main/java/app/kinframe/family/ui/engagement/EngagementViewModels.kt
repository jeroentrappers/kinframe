package app.kinframe.family.ui.engagement

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.social.FeedEngagement
import app.kinframe.core.social.FeedEngagementUpdate
import app.kinframe.core.social.FeedPrompt
import app.kinframe.core.social.FeedPromptSet
import app.kinframe.core.social.FeedStreak
import app.kinframe.core.social.Milestone
import app.kinframe.core.social.MilestoneUpdate
import app.kinframe.core.social.MonthlyRecap
import app.kinframe.core.social.OnThisDay
import app.kinframe.core.social.PhotoRequest
import app.kinframe.core.social.PromptLibraryEntry
import app.kinframe.core.social.PromptStatus
import app.kinframe.core.social.QuietHours
import app.kinframe.core.social.ReminderPreferences
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.WeeklySummary
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.social.SocialProblems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EngagementNotice {
    data class Message(@StringRes val message: Int, val arg: String? = null) : EngagementNotice
}

data class RemindersState(
    val prefs: Load<ReminderPreferences> = Load.Loading,
    val showReadReceipts: Boolean = true,
    val saving: Boolean = false,
)

/** Settings → Reminders and notifications (README §5.20) and the chats' read receipts (§5.19). */
class RemindersViewModel(
    private val api: SocialApi,
    showReadReceipts: Boolean,
    private val engagement: Boolean,
    private val onUser: (app.kinframe.core.api.User) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(RemindersState(showReadReceipts = showReadReceipts, prefs = if (engagement) Load.Loading else Load.Error(R.string.social_problem_not_ready)))
    val state: StateFlow<RemindersState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngagementNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<EngagementNotice> = _events.asSharedFlow()

    init { if (engagement) load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(prefs = Load.Ready(api.reminderPreferences())) }
        } catch (e: Exception) {
            _state.update { it.copy(prefs = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun update(u: ReminderPreferencesUpdate) = viewModelScope.launch {
        val before = (state.value.prefs as? Load.Ready)?.value ?: return@launch
        _state.update { it.copy(prefs = Load.Ready(optimistic(before, u)), saving = true) }
        try {
            _state.update { it.copy(prefs = Load.Ready(api.updateReminderPreferences(u)), saving = false) }
        } catch (e: Exception) {
            _state.update { it.copy(prefs = Load.Ready(before), saving = false) }
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(e)))
        }
    }

    private fun optimistic(p: ReminderPreferences, u: ReminderPreferencesUpdate) = p.copy(
        level = u.level ?: p.level, frameRequests = u.frameRequests ?: p.frameRequests, weeklySummary = u.weeklySummary ?: p.weeklySummary,
        birthdayReminders = u.birthdayReminders ?: p.birthdayReminders, quietHours = u.quietHours ?: p.quietHours,
        emailDigest = u.emailDigest ?: p.emailDigest, autoReduced = if (u.autoReducedSeen == true) false else p.autoReduced,
        snoozedUntil = if (u.snoozeDays == 0) null else p.snoozedUntil,
    )

    fun snooze(days: Int) {
        update(ReminderPreferencesUpdate(snoozeDays = days))
        _events.tryEmit(EngagementNotice.Message(if (days == 0) R.string.engagement_snooze_ended else R.string.engagement_snoozed))
    }

    fun setQuietHours(start: String, end: String) = update(ReminderPreferencesUpdate(quietHours = QuietHours(start, end)))

    fun setReadReceipts(on: Boolean) = viewModelScope.launch {
        _state.update { it.copy(showReadReceipts = on) }
        try {
            onUser(api.setShowReadReceipts(on))
        } catch (e: Exception) {
            _state.update { it.copy(showReadReceipts = !on) }
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(e)))
        }
    }
}

data class FeedEngagementState(
    val feed: Feed? = null,
    val settings: FeedEngagement? = null,
    val streak: Load<FeedStreak> = Load.Loading,
    val prompts: List<FeedPrompt> = emptyList(),
    val library: List<PromptLibraryEntry> = emptyList(),
    val busy: Boolean = false,
) {
    val current: FeedPrompt? get() = prompts.firstOrNull { it.status == PromptStatus.CURRENT }
    val planned: List<FeedPrompt> get() = prompts.filter { it.status == PromptStatus.PLANNED || it.status == PromptStatus.SKIPPED && it.weekOf > (current?.weekOf ?: "") }
}

/** A family's streak and weekly prompts; admins also change the goal, prompts and the other family settings. */
class FeedEngagementViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val feedId: String,
    live: Flow<LiveEvent> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(FeedEngagementState())
    val state: StateFlow<FeedEngagementState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngagementNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<EngagementNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is SocialLive.StreakUpdated -> if (e.feedId == feedId) loadStreak()
                    is SocialLive.PromptUpdated -> if (e.feedId == feedId) _state.update { s ->
                        s.copy(prompts = (s.prompts.filterNot { it.id == e.prompt.id || it.weekOf == e.prompt.weekOf } + e.prompt).sortedBy { it.weekOf })
                    }
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        launch { runCatching { kin.feed(feedId) }.onSuccess { f -> _state.update { it.copy(feed = f) } } }
        launch { runCatching { api.feedEngagement(feedId) }.onSuccess { e -> _state.update { it.copy(settings = e) } } }
        launch { loadStreak() }
        launch { runCatching { api.feedPrompts(feedId) }.onSuccess { p -> _state.update { it.copy(prompts = p.sortedBy { x -> x.weekOf }) } } }
    }

    private suspend fun loadStreak() {
        try {
            _state.update { it.copy(streak = Load.Ready(api.feedStreak(feedId))) }
        } catch (e: Exception) {
            if (state.value.streak !is Load.Ready) _state.update { it.copy(streak = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun loadLibrary() = viewModelScope.launch {
        if (state.value.library.isNotEmpty()) return@launch
        runCatching { api.promptLibrary() }.onSuccess { l -> _state.update { it.copy(library = l) } }
    }

    fun update(u: FeedEngagementUpdate) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val e = api.updateFeedEngagement(feedId, u)
            _state.update { it.copy(settings = e, busy = false) }
            loadStreak()
            if (u.promptsEnabled != null) runCatching { api.feedPrompts(feedId) }.onSuccess { p -> _state.update { it.copy(prompts = p.sortedBy { x -> x.weekOf }) } }
        } catch (ex: Exception) {
            _state.update { it.copy(busy = false) }
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(ex)))
        }
    }

    /** One of a library key, an own title (1–80) or skip, for the week starting [weekOf]. */
    fun setPrompt(weekOf: String, set: FeedPromptSet) = viewModelScope.launch {
        try {
            val p = api.setFeedPrompt(feedId, weekOf, set)
            _state.update { s -> s.copy(prompts = (s.prompts.filterNot { it.weekOf == weekOf } + p).sortedBy { it.weekOf }) }
            _events.tryEmit(EngagementNotice.Message(if (set.skip == true) R.string.engagement_prompt_skipped else R.string.engagement_prompt_set))
        } catch (e: Exception) {
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(e)))
        }
    }

    fun resetPrompt(weekOf: String) = viewModelScope.launch {
        try {
            api.resetFeedPrompt(feedId, weekOf)
            runCatching { api.feedPrompts(feedId) }.onSuccess { p -> _state.update { it.copy(prompts = p.sortedBy { x -> x.weekOf }) } }
            _events.tryEmit(EngagementNotice.Message(R.string.engagement_prompt_reset))
        } catch (e: Exception) {
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(e)))
        }
    }
}

/** One screen's worth of a read-only engagement document: a weekly summary or a monthly recap. */
class DocumentViewModel<T>(private val fetch: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = try { Load.Ready(fetch()) } catch (e: Exception) { Load.Error(SocialProblems.message(e)) }
    }
}

typealias WeeklySummaryViewModel = DocumentViewModel<WeeklySummary>
typealias RecapViewModel = DocumentViewModel<MonthlyRecap>

/** Personal milestones: private unless shown on the profile. */
class MilestonesViewModel(private val api: SocialApi) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Milestone>>>(Load.Loading)
    val state: StateFlow<Load<List<Milestone>>> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngagementNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<EngagementNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = try { Load.Ready(api.milestones().sortedByDescending { it.achievedAt }) } catch (e: Exception) { Load.Error(SocialProblems.message(e)) }
    }

    fun setShown(m: Milestone, shown: Boolean) = viewModelScope.launch {
        _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.map { x -> if (x.key == m.key) x.copy(shown = shown) else x }) } ?: s }
        try {
            api.updateMilestone(m.key, MilestoneUpdate(shown = shown, seen = true))
        } catch (e: Exception) {
            load()
            _events.tryEmit(EngagementNotice.Message(SocialProblems.message(e)))
        }
    }
}

data class FreshFrameState(
    val frame: Load<Frame> = Load.Loading,
    val requests: List<PhotoRequest> = emptyList(),
    val onThisDay: OnThisDay? = null,
    val prompts: List<FeedPrompt> = emptyList(),
)

/** "Send something to Oma's frame": freshness, the frame's photo requests, On this day and this week's prompts. */
class FreshFrameViewModel(private val api: SocialApi, private val kin: KinApi, private val frameId: String, live: Flow<LiveEvent> = emptyFlow()) : ViewModel() {
    private val _state = MutableStateFlow(FreshFrameState())
    val state: StateFlow<FreshFrameState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e -> if (e is SocialLive.PhotoRequestChanged && e.request.frameId == frameId) load() }
        }
    }

    fun load() = viewModelScope.launch {
        launch { runCatching { api.framePhotoRequests(frameId) }.onSuccess { r -> _state.update { it.copy(requests = r.sortedByDescending { x -> x.createdAt }) } } }
        launch { runCatching { api.onThisDay() }.onSuccess { d -> _state.update { it.copy(onThisDay = d.takeIf { x -> x.groups.isNotEmpty() }) } } }
        launch {
            val feeds = runCatching { kin.frameSubscriptions(frameId, app.kinframe.core.api.SubscriptionStatus.ACTIVE) }.getOrDefault(emptyList()).map { it.feed.id }
            val mine = runCatching { kin.feeds() }.getOrDefault(emptyList()).filter { it.id in feeds && it.canPost }
            val prompts = mine.mapNotNull { f -> runCatching { api.feedPrompts(f.id) }.getOrNull()?.firstOrNull { it.status == PromptStatus.CURRENT && it.title.isNotBlank() } }
            _state.update { it.copy(prompts = prompts) }
        }
        try {
            _state.update { it.copy(frame = Load.Ready(kin.frame(frameId))) }
        } catch (e: Exception) {
            _state.update { it.copy(frame = Load.Error(SocialProblems.message(e))) }
        }
    }
}
