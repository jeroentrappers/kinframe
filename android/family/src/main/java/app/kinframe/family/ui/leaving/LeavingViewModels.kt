package app.kinframe.family.ui.leaving

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.leaving.CommentsChoice
import app.kinframe.core.leaving.FormerPlaces
import app.kinframe.core.leaving.KeepAnswer
import app.kinframe.core.leaving.KeepRequest
import app.kinframe.core.leaving.KeepRequestAnswer
import app.kinframe.core.leaving.KeepRequestCreate
import app.kinframe.core.leaving.LeaveFeedRequest
import app.kinframe.core.leaving.LeaveFrameRequest
import app.kinframe.core.leaving.LeavePreview
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.leaving.MemoryPreferences
import app.kinframe.core.leaving.MemoryPreferencesUpdate
import app.kinframe.core.leaving.PhotosChoice
import app.kinframe.core.leaving.TakeAwayRequest
import app.kinframe.core.leaving.TakeAwayResult
import app.kinframe.core.leaving.TakenAwayGroup
import app.kinframe.core.api.Placement
import app.kinframe.core.social.PersonRef
import app.kinframe.family.R
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What the leaving screens tell the person (README §5.21). */
sealed interface LeavingNotice {
    data class Message(@StringRes val message: Int, val args: List<Any> = emptyList()) : LeavingNotice
    /** Left (or took photos away): [result] null when the server reported nothing (keep). */
    data class Done(val result: TakeAwayResult?) : LeavingNotice
}

private fun problem(e: Throwable): Int = when (Problems.code(e)) {
    ProblemCode.FORBIDDEN -> R.string.leaving_problem_forbidden
    "limit_reached" -> R.string.leaving_problem_already_asked
    "invalid_transition" -> R.string.leaving_problem_expired
    else -> Problems.message(e)
}

/** The leave sheet of a family or a frame (leaving.md story 1, 6): photos, comments, frames to leave too. */
data class LeaveSheetState(
    val preview: Load<LeavePreview> = Load.Loading,
    val photos: String = PhotosChoice.KEEP,
    val comments: String = CommentsChoice.KEEP,
    /** "Leave this frame too": checked frames. */
    val frames: Set<String> = emptySet(),
    val busy: Boolean = false,
)

class LeaveSheetViewModel(
    private val api: LeavingApi,
    /** Exactly one of them. */
    private val feedId: String?,
    private val frameId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(LeaveSheetState())
    val state: StateFlow<LeaveSheetState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LeavingNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<LeavingNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(preview = Load.Loading) }
        try {
            val p = api.leavePreview(feedId = feedId, frameId = frameId)
            // Checked by default: frames that get this family's photos and that the person doesn't own.
            _state.update { it.copy(preview = Load.Ready(p), frames = p.frames.filter { f -> f.suggested && f.canLeave }.map { f -> f.frameId }.toSet()) }
        } catch (e: Exception) {
            _state.update { it.copy(preview = Load.Error(problem(e))) }
        }
    }

    fun setPhotos(choice: String) = _state.update { it.copy(photos = choice) }
    fun setComments(choice: String) = _state.update { it.copy(comments = choice) }
    fun toggleFrame(id: String) = _state.update { s -> s.copy(frames = if (id in s.frames) s.frames - id else s.frames + id) }

    fun leave() {
        val s = state.value
        if (s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = if (feedId != null) api.leaveFeed(feedId, LeaveFeedRequest(s.photos, s.comments, s.frames.toList()))
                else api.leaveFrame(frameId!!, LeaveFrameRequest(s.photos))
                _events.emit(LeavingNotice.Done(r))
            } catch (e: Exception) {
                _events.emit(LeavingNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** "Take my photos away" from families and frames the person left or was removed from (leaving.md story 4). */
data class FormerPlacesState(val places: Load<FormerPlaces> = Load.Loading, val busy: Boolean = false)

class FormerPlacesViewModel(private val api: LeavingApi, private val newKey: () -> String = { UUID.randomUUID().toString() }) : ViewModel() {
    private val _state = MutableStateFlow(FormerPlacesState())
    val state: StateFlow<FormerPlacesState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LeavingNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<LeavingNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(places = Load.Ready(api.formerPlaces())) }
        } catch (e: Exception) {
            _state.update { it.copy(places = Load.Error(problem(e))) }
        }
    }

    fun takeAwayFromFeed(feedId: String, deleteComments: Boolean) = act {
        api.takeAwayFromFeed(feedId, TakeAwayRequest(if (deleteComments) CommentsChoice.DELETE else CommentsChoice.KEEP), newKey())
    }

    fun takeAwayFromFrame(frameId: String) = act { api.takeAwayFromFrame(frameId, newKey()) }

    private fun act(block: suspend () -> TakeAwayResult) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                _events.emit(LeavingNotice.Done(block()))
                load()
            } catch (e: Exception) {
                _events.emit(LeavingNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** The person's keep requests ("Lies asks to keep 12 photos on Oma's frame") and answering one (story 3). */
data class KeepRequestsState(
    val requests: Load<List<KeepRequest>> = Load.Loading,
    /** The request being answered, with its photos. */
    val open: Load<KeepRequest>? = null,
    /** Allowed photos in the answer sheet (default all). */
    val allowed: Set<String> = emptySet(),
    val busy: Boolean = false,
)

class KeepRequestsViewModel(private val api: LeavingApi) : ViewModel() {
    private val _state = MutableStateFlow(KeepRequestsState())
    val state: StateFlow<KeepRequestsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LeavingNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<LeavingNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(requests = Load.Ready(api.myKeepRequests().requests)) }
        } catch (e: Exception) {
            _state.update { it.copy(requests = Load.Error(problem(e))) }
        }
    }

    fun open(requestId: String) = viewModelScope.launch {
        _state.update { it.copy(open = Load.Loading) }
        try {
            val r = api.keepRequest(requestId)
            _state.update { it.copy(open = Load.Ready(r), allowed = r.items.map { i -> i.item.id }.toSet()) }
        } catch (e: Exception) {
            _state.update { it.copy(open = Load.Error(problem(e))) }
        }
    }

    fun close() = _state.update { it.copy(open = null) }

    fun toggle(itemId: String) = _state.update { s -> s.copy(allowed = if (itemId in s.allowed) s.allowed - itemId else s.allowed + itemId) }

    /** Allow all, the picked ones, or none; final. */
    fun answer(allow: String) {
        val r = (state.value.open as? Load.Ready)?.value ?: return
        val s = state.value
        if (s.busy) return
        val all = r.items.map { it.item.id }.toSet()
        val answer = when {
            allow == KeepAnswer.SOME && s.allowed.isEmpty() -> KeepRequestAnswer(KeepAnswer.NONE)
            allow == KeepAnswer.SOME && s.allowed == all -> KeepRequestAnswer(KeepAnswer.ALL)
            allow == KeepAnswer.SOME -> KeepRequestAnswer(KeepAnswer.SOME, s.allowed.toList())
            else -> KeepRequestAnswer(allow)
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val done = api.answerKeepRequest(r.id, answer)
                _events.emit(LeavingNotice.Message(when (answer.allow) {
                    KeepAnswer.NONE -> R.string.leaving_keep_declined
                    else -> R.string.leaving_keep_allowed
                }, listOf(done.allowedCount, done.askedByName)))
                _state.update { st -> st.copy(open = null, requests = Load.Ready((st.requests as? Load.Ready)?.value.orEmpty().filterNot { it.id == r.id })) }
            } catch (e: Exception) {
                _events.emit(LeavingNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** `keep_request.updated` on the websocket. */
    fun onUpdated(r: KeepRequest) = _state.update { s ->
        val list = (s.requests as? Load.Ready)?.value.orEmpty().filterNot { it.id == r.id } + listOfNotNull(r.takeIf { it.isOpen })
        s.copy(requests = Load.Ready(list.sortedByDescending { it.askedAt }))
    }
}

/**
 * "Taken away by Tom" in a frame's trash or a family's settings (leaving.md story 2, 3): the groups, and "Ask Tom to
 * keep them" with an optional pick of photos.
 */
data class TakenAwayState(
    val groups: Load<List<TakenAwayGroup>> = Load.Loading,
    /** The group being asked about, with its photos (frames only; families ask for all). */
    val asking: TakenAwayGroup? = null,
    val photos: Load<List<Placement>> = Load.Loading,
    val picked: Set<String> = emptySet(),
    val note: String = "",
    val busy: Boolean = false,
)

class TakenAwayViewModel(
    private val api: LeavingApi,
    private val frameId: String?,
    private val feedId: String?,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(TakenAwayState())
    val state: StateFlow<TakenAwayState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LeavingNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<LeavingNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val g = if (frameId != null) api.frameTakenAway(frameId) else api.feedTakenAway(feedId!!)
            _state.update { it.copy(groups = Load.Ready(g.groups)) }
        } catch (e: Exception) {
            _state.update { it.copy(groups = Load.Error(problem(e))) }
        }
    }

    fun ask(group: TakenAwayGroup) {
        _state.update { it.copy(asking = group, photos = Load.Loading, picked = emptySet(), note = "") }
        if (frameId == null) { _state.update { it.copy(photos = Load.Ready(emptyList())) }; return }
        viewModelScope.launch {
            try {
                val list = api.takenAwayPlacements(frameId, group.takingId).placements
                _state.update { it.copy(photos = Load.Ready(list), picked = list.map { p -> p.item.id }.toSet()) }
            } catch (e: Exception) {
                _state.update { it.copy(photos = Load.Error(problem(e))) }
            }
        }
    }

    fun cancelAsk() = _state.update { it.copy(asking = null) }
    fun togglePhoto(itemId: String) = _state.update { s -> s.copy(picked = if (itemId in s.picked) s.picked - itemId else s.picked + itemId) }
    fun onNote(v: String) = _state.update { it.copy(note = v.take(300)) }

    fun send() {
        val s = state.value
        val g = s.asking ?: return
        if (s.busy) return
        val all = (s.photos as? Load.Ready)?.value.orEmpty().map { it.item.id }.toSet()
        // Default all of the taking; a pick only when it is a real subset.
        val ids = s.picked.takeIf { it.isNotEmpty() && it != all }?.toList()
        if (frameId != null && all.isNotEmpty() && s.picked.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = api.createKeepRequest(KeepRequestCreate(g.takingId, frameId = frameId, feedId = feedId, itemIds = ids, note = s.note.trim().ifBlank { null }), newKey())
                _events.emit(LeavingNotice.Message(R.string.leaving_ask_sent, listOf(g.byName)))
                _state.update { st ->
                    st.copy(asking = null, groups = Load.Ready((st.groups as? Load.Ready)?.value.orEmpty().map {
                        if (it.takingId == g.takingId) it.copy(keepRequest = r, canAsk = false, cannotAskReason = "already_asked") else it
                    }))
                }
            } catch (e: Exception) {
                _events.emit(LeavingNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** Settings → Memories (leaving.md story 8): leave people out, hidden memories. Private: nobody is told. */
data class MemoriesState(
    val prefs: Load<MemoryPreferences> = Load.Loading,
    /** People to pick from ("Leave people out"): contacts on this server. */
    val people: List<PersonRef> = emptyList(),
    val busy: Boolean = false,
)

class MemoriesViewModel(
    private val api: LeavingApi,
    private val contacts: suspend () -> List<PersonRef>,
) : ViewModel() {
    private val _state = MutableStateFlow(MemoriesState())
    val state: StateFlow<MemoriesState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LeavingNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<LeavingNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.update { it.copy(prefs = Load.Ready(api.memories())) }
        } catch (e: Exception) {
            _state.update { it.copy(prefs = Load.Error(problem(e))) }
        }
        runCatching { contacts() }.onSuccess { p -> _state.update { it.copy(people = p.distinctBy { x -> x.userId }.sortedBy { x -> x.displayName.lowercase() }) } }
    }

    private val current: MemoryPreferences get() = (state.value.prefs as? Load.Ready)?.value ?: MemoryPreferences()

    fun hidePerson(p: PersonRef) = put(MemoryPreferencesUpdate(hiddenPeople = (current.hiddenPeople.map { it.userId } + p.userId).distinct()), R.string.leaving_memories_person_hidden, p.displayName)

    fun showPerson(p: PersonRef) = put(MemoryPreferencesUpdate(hiddenPeople = current.hiddenPeople.map { it.userId } - p.userId), R.string.leaving_memories_person_shown, p.displayName)

    /** Shows every hidden memory again. */
    fun showAllMemories() = put(MemoryPreferencesUpdate(hiddenMemories = emptyList()), R.string.leaving_memories_all_shown, null)

    private fun put(update: MemoryPreferencesUpdate, @StringRes done: Int, arg: String?) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(prefs = Load.Ready(api.updateMemories(update))) }
                _events.emit(LeavingNotice.Message(done, listOfNotNull(arg)))
            } catch (e: Exception) {
                _events.emit(LeavingNotice.Message(problem(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        /**
         * "Don't show this memory again" on a memory card: adds the post ids to `hidden_memories` (README §5.21).
         * Reads the list first because `PUT` replaces it.
         */
        suspend fun hideMemory(api: LeavingApi, postIds: List<String>) {
            val now = api.memories().hiddenMemories
            api.updateMemories(MemoryPreferencesUpdate(hiddenMemories = (now + postIds).distinct()))
        }
    }
}
