package app.kinframe.family.ui.feeds

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BirthdayDates
import app.kinframe.core.api.BirthdayPerson
import app.kinframe.core.api.BirthdayPersonCreate
import app.kinframe.core.api.BirthdayPersonUpdate
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedBirthdayGreeting
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Who may do what with a feed's birthdays (docs/spec/birthdays.md §4.1). The server enforces it; the app hides what won't work. */
object BirthdayRules {
    /** Owner, admins and (with `posters_can_add_birthdays`) posters add directly; everyone else suggests. */
    fun addsDirectly(feed: Feed): Boolean = feed.canAddBirthdays

    /** The next occurrence on or after [today] (the server's `next_birthday`, else worked out here; 29 Feb → 28 Feb). */
    fun next(p: BirthdayPerson, today: LocalDate): LocalDate =
        p.nextBirthday?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.takeIf { !it.isBefore(today) }
            ?: BirthdayDates.next(p.month, p.day, today)

    fun daysUntil(p: BirthdayPerson, today: LocalDate): Int = ChronoUnit.DAYS.between(today, next(p, today)).toInt()

    /** Sorted by the next birthday, then by name. */
    fun sorted(list: List<BirthdayPerson>, today: LocalDate): List<BirthdayPerson> =
        list.sortedWith(compareBy<BirthdayPerson>({ next(it, today) }, { it.displayName.lowercase() }, { it.id }))

    /**
     * The age on the next birthday, when the caller sees the year. The server sends `turns`; without it (older servers)
     * it follows from the year.
     */
    fun turns(p: BirthdayPerson, today: LocalDate): Int? =
        p.turns ?: p.birthYear?.let { y -> next(p, today).year - y }?.takeIf { it > 0 }

    /**
     * The year is visible to the caller but kept from the rest of the feed and the frames (`show_age` off): the app says
     * so next to it (§9: owner/admins, whoever added it and the linked member see it).
     */
    fun yearIsPrivate(p: BirthdayPerson): Boolean = p.birthYear != null && !p.showAge

    /** What the frames will show: the age only with a year and `show_age`. */
    fun framesShowAge(p: BirthdayPerson): Boolean = p.birthYear != null && p.showAge

    /**
     * The linked member editing their own entry without other rights: only year, "show age", photo and unlinking
     * (§4.1). Owner and admins, the suggester of a pending entry and posters who added it may change everything.
     */
    fun ownEntryOnly(p: BirthdayPerson, feed: Feed, me: String?): Boolean =
        me != null && p.userId == me && !feed.isAdmin && !p.isPending && !(feed.postersCanAddBirthdays && addedByMe(p, me))

    /** The caller created the entry (as a direct add or a suggestion). */
    fun addedByMe(p: BirthdayPerson, me: String?): Boolean = me != null && p.createdById == me

    fun canEdit(p: BirthdayPerson): Boolean = p.canEdit

    fun canRemove(p: BirthdayPerson, feed: Feed, me: String?): Boolean =
        feed.isAdmin || (me != null && p.userId == me) || (addedByMe(p, me) && (p.isPending || feed.postersCanAddBirthdays))

    /** Rejected suggestions are shown to the suggester (and admins) for 30 days as declined. */
    fun visibleRejected(list: List<BirthdayPerson>): List<BirthdayPerson> = list.filter { it.isRejected }
}

/** The add / edit form. [id] null: a new person. */
data class BirthdayDraft(
    val id: String? = null,
    val name: String = "",
    val day: Int? = null,
    val month: Int? = null,
    val year: Int? = null,
    val showAge: Boolean = false,
    val userId: String? = null,
    /** A new photo picked on the phone (cropped to a square before upload). */
    val photo: Uri? = null,
    val removePhoto: Boolean = false,
) {
    /** The first problem with the form, or null when it can be saved. */
    @StringRes
    fun problem(today: LocalDate): Int? {
        val d = day
        val m = month
        return when {
            name.isBlank() -> R.string.birthday_error_name
            d == null || m == null || !BirthdayDates.isValid(d, m) -> R.string.birthday_error_date
            year != null && !BirthdayDates.isValidYear(year, today) -> R.string.birthday_error_year
            year != null && !BirthdayDates.isValid(d, m, year) -> R.string.birthday_error_leap
            else -> null
        }
    }

    companion object {
        fun of(p: BirthdayPerson) = BirthdayDraft(p.id, p.displayName, p.day, p.month, p.birthYear, p.showAge, p.userId)
    }
}

data class BirthdaysState(
    val people: Load<List<BirthdayPerson>> = Load.Loading,
    /** Today's greetings, newest first. */
    val greetings: List<FeedBirthdayGreeting> = emptyList(),
    val saving: Boolean = false,
    val busy: Boolean = false,
    /** A save answered `duplicate_birthday`: the entry that already exists (its id from the problem's `detail`). */
    val duplicateOf: BirthdayPerson? = null,
) {
    private val all: List<BirthdayPerson> get() = (people as? Load.Ready)?.value.orEmpty()
    val approved: List<BirthdayPerson> get() = all.filter { it.isApproved }
    val pending: List<BirthdayPerson> get() = all.filter { it.isPending }
    val rejected: List<BirthdayPerson> get() = all.filter { it.isRejected }

    fun greetingsFor(p: BirthdayPerson): List<FeedBirthdayGreeting> = greetings.filter { it.birthdayId == p.id }
}

sealed interface BirthdayNotice {
    data class Message(@StringRes val message: Int, val args: List<String> = emptyList()) : BirthdayNotice
    data class Error(@StringRes val message: Int) : BirthdayNotice
    /** Removed: offer Undo (re-created from [person]). */
    data class Removed(val person: BirthdayPerson) : BirthdayNotice
}

/**
 * A feed's Birthdays (birthdays.md §3): the list sorted by the next birthday, add and edit (or suggest), photos,
 * approving and rejecting suggestions, today's greetings from frames. Live updates arrive over the websocket.
 */
class FeedBirthdaysViewModel(
    private val api: KinApi,
    val feedId: String,
    live: Flow<LiveEvent>,
    private val readPhoto: suspend (Uri) -> ByteArray,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(BirthdaysState())
    val state: StateFlow<BirthdaysState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<BirthdayNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<BirthdayNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.BirthdaySuggested -> if (e.feedId == feedId) upsert(e.birthday)
                    is LiveEvent.BirthdayDecided -> if (e.feedId == feedId) upsert(e.birthday)
                    is LiveEvent.BirthdayUpdated -> if (e.feedId == feedId) upsert(e.birthday)
                    is LiveEvent.BirthdayRemoved -> if (e.feedId == feedId) drop(e.birthdayId)
                    is LiveEvent.BirthdayGreeting -> if (e.feedId == feedId) addGreeting(e.greeting)
                    is LiveEvent.FeedMembershipUpdated -> if (e.feedId == feedId) load()
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            val list = api.feedBirthdays(feedId)
            _state.update { it.copy(people = Load.Ready(BirthdayRules.sorted(list, today()))) }
        } catch (e: Exception) {
            if (state.value.people !is Load.Ready) _state.update { it.copy(people = Load.Error(Problems.message(e))) }
        }
        loadGreetings()
    }

    private suspend fun loadGreetings() {
        val d = today().toString()
        runCatching { api.feedBirthdayGreetings(feedId, date = d) }
            .onSuccess { page -> _state.update { it.copy(greetings = page.greetings.filter { g -> g.date == d }) } }
    }

    private fun upsert(p: BirthdayPerson) = _state.update { s ->
        val list = (s.people as? Load.Ready)?.value.orEmpty().filterNot { it.id == p.id } + p
        s.copy(people = Load.Ready(BirthdayRules.sorted(list, today())))
    }

    private fun drop(id: String) = _state.update { s ->
        (s.people as? Load.Ready)?.let { s.copy(people = Load.Ready(it.value.filterNot { p -> p.id == id })) } ?: s
    }

    private fun addGreeting(g: FeedBirthdayGreeting) {
        if (g.date != today().toString()) return
        _state.update { s -> s.copy(greetings = listOf(g) + s.greetings.filterNot { it.id == g.id }) }
        // The person's greeting count changed too.
        viewModelScope.launch { runCatching { api.feedBirthday(feedId, g.birthdayId) }.onSuccess { upsert(it) } }
    }

    private fun Exception.birthdayProblem(): Int = when ((this as? ApiException)?.code) {
        ProblemCode.LIMIT_REACHED -> R.string.birthday_limit_reached
        ProblemCode.ALREADY_DECIDED -> R.string.problem_invalid_transition
        else -> Problems.message(this)
    }

    /**
     * Saves the form. A new person is added at once by owner/admins (and posters when the feed allows it), otherwise it
     * becomes a suggestion; the server decides and the message says which. [onDone] runs after a successful save.
     */
    fun save(draft: BirthdayDraft, original: BirthdayPerson? = null, onDone: () -> Unit = {}) {
        if (draft.problem(today()) != null || state.value.saving) return
        _state.update { it.copy(saving = true, duplicateOf = null) }
        viewModelScope.launch {
            try {
                var saved = if (draft.id == null) {
                    api.createFeedBirthday(
                        feedId,
                        BirthdayPersonCreate(draft.name.trim().take(BirthdayDates.MAX_NAME), draft.day!!, draft.month!!, draft.year, draft.showAge && draft.year != null, draft.userId),
                        newKey(),
                    )
                } else {
                    val update = diff(original, draft)
                    if (update.isEmpty) original!! else api.updateFeedBirthday(feedId, draft.id, update)
                }
                draft.photo?.let { uri -> saved = api.putFeedBirthdayPhoto(feedId, saved.id, readPhoto(uri), "image/jpeg") }
                if (draft.photo == null && draft.removePhoto && saved.hasOwnPhoto) saved = api.deleteFeedBirthdayPhoto(feedId, saved.id)
                upsert(saved)
                _state.update { it.copy(saving = false) }
                val msg = when {
                    draft.id != null -> R.string.birthday_saved
                    saved.isPending -> R.string.birthday_suggested
                    else -> R.string.birthday_added
                }
                _events.tryEmit(BirthdayNotice.Message(msg, listOf(saved.displayName)))
                onDone()
            } catch (e: Exception) {
                val dup = (e as? ApiException)?.takeIf { it.code == ProblemCode.DUPLICATE_BIRTHDAY }
                if (dup != null) {
                    val existing = dup.problem?.detail?.let { id -> (state.value.people as? Load.Ready)?.value?.firstOrNull { it.id == id } }
                    _state.update { it.copy(saving = false, duplicateOf = existing) }
                    _events.tryEmit(BirthdayNotice.Message(R.string.birthday_duplicate, listOf(existing?.displayName ?: draft.name.trim())))
                } else {
                    _state.update { it.copy(saving = false) }
                    _events.tryEmit(BirthdayNotice.Error(e.birthdayProblem()))
                }
            }
        }
    }

    fun clearDuplicate() = _state.update { it.copy(duplicateOf = null) }

    private fun diff(o: BirthdayPerson?, d: BirthdayDraft): BirthdayPersonUpdate {
        if (o == null) return BirthdayPersonUpdate(d.name.trim(), d.day, d.month, d.year, clearYear = d.year == null, showAge = d.showAge, userId = d.userId, unlink = d.userId == null)
        val name = d.name.trim().take(BirthdayDates.MAX_NAME)
        val showAge = d.showAge && d.year != null
        return BirthdayPersonUpdate(
            displayName = name.takeIf { it != o.displayName },
            day = d.day?.takeIf { it != o.day || d.month != o.month },
            month = d.month?.takeIf { it != o.month || d.day != o.day },
            birthYear = d.year?.takeIf { it != o.birthYear },
            clearYear = d.year == null && o.birthYear != null,
            showAge = showAge.takeIf { it != o.showAge },
            userId = d.userId?.takeIf { it != o.userId },
            unlink = d.userId == null && o.userId != null,
        )
    }

    private fun act(@StringRes done: Int?, args: List<String> = emptyList(), block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
                done?.let { _events.tryEmit(BirthdayNotice.Message(it, args)) }
            } catch (e: Exception) {
                val dup = (e as? ApiException)?.code == ProblemCode.DUPLICATE_BIRTHDAY
                _events.tryEmit(if (dup) BirthdayNotice.Message(R.string.birthday_duplicate_approve, args) else BirthdayNotice.Error(e.birthdayProblem()))
                if ((e as? ApiException)?.status == 404) load()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun approve(p: BirthdayPerson) = act(R.string.birthday_approved, listOf(p.displayName)) { upsert(api.approveFeedBirthday(feedId, p.id)) }

    fun reject(p: BirthdayPerson) = act(R.string.birthday_rejected, listOf(p.displayName)) { upsert(api.rejectFeedBirthday(feedId, p.id)) }

    /** Removes at once (there is no trash) and offers Undo, which adds the person again (§5.1). */
    fun remove(p: BirthdayPerson) = act(null) {
        api.removeFeedBirthday(feedId, p.id)
        drop(p.id)
        _events.tryEmit(BirthdayNotice.Removed(p))
    }

    fun undoRemove(p: BirthdayPerson) = act(R.string.birthday_restored, listOf(p.displayName)) {
        val back = api.createFeedBirthday(feedId, BirthdayPersonCreate(p.displayName, p.day, p.month, p.birthYear, p.showAge, p.userId), newKey())
        upsert(back)
    }
}
