package app.kinframe.family.ui.frames

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionFeed
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FramesState(
    val frames: Load<List<Frame>> = Load.Loading,
    val refreshing: Boolean = false,
    /** The families each frame shows (active subscriptions), by frame id. */
    val shows: Map<String, List<SubscriptionFeed>> = emptyMap(),
    /** A frame being added to a family. */
    val adding: String? = null,
    /** Frames that show the person's families but that they aren't on (docs/spec/simple-app.md §3.3). */
    val familyFrames: List<FamilyFrame> = emptyList(),
) {
    val online: Int get() = (frames as? Load.Ready)?.value?.count { it.online } ?: 0
}

/**
 * What a frame's card shows (docs/spec/simple-app.md §3.3, API README §5.5 "Frame cards"), from the server's
 * `now_showing` and `reactions_today`. Both are absent for people who can't see the frame's library; the card then
 * keeps its drawing and has no reactions line.
 */
object FrameCards {
    /** The card's photo: the thumbnail, or with [large] the display rendition; null without `now_showing`. */
    fun photo(frame: Frame, large: Boolean = false): String? = frame.nowShowing?.let { n ->
        if (large) n.displayUrl ?: n.thumbUrl else n.thumbUrl ?: n.displayUrl
    }

    /** "Online · showing now": the frame is online and reported this slide on screen (`source: screen`). */
    fun showingNow(frame: Frame): Boolean = frame.online && frame.nowShowing?.onScreen == true

    /** How many photos the frame reacted to today ("Oma loved 3 photos today"); null hides the line. */
    fun lovedToday(frame: Frame): Int? = frame.reactionsToday?.takeIf { it.count > 0 }?.itemCount?.takeIf { it > 0 }
}

/** A frame that shows one or more of the person's families, which they aren't on themselves. */
data class FamilyFrame(val frame: SubscriptionFrame, val shows: List<SubscriptionFeed>)

/** The Frames tab (docs/spec/simple-app.md §3.3): the person's own frames, then frames that show their families. */
object FramesOrder {
    /** Frames reached through the families' active subscriptions, without the person's own, each once. */
    fun familyFrames(own: List<Frame>, subscriptions: List<Subscription>): List<FamilyFrame> =
        subscriptions.filter { s -> own.none { it.id == s.frame.id } }.groupBy { it.frame.id }
            .map { (_, subs) -> FamilyFrame(subs.first().frame, subs.map { it.feed }.distinctBy { it.id }) }
            .sortedBy { it.frame.name.lowercase() }
}

/** Adding a frame to a family from the Frames tab. */
sealed interface FrameAdded {
    data class Active(val frame: String, val family: String) : FrameAdded
    data object Pending : FrameAdded
    data class Failed(@androidx.annotation.StringRes val message: Int) : FrameAdded
}

/** The Frames tab: everything the person is a member of, with live online state. */
class FramesViewModel(private val api: KinApi, live: Flow<LiveEvent>) : ViewModel() {
    private val _state = MutableStateFlow(FramesState())
    val state: StateFlow<FramesState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.FrameStatus -> _state.update { s ->
                        val list = (s.frames as? Load.Ready)?.value ?: return@update s
                        s.copy(frames = Load.Ready(list.map { if (it.id == e.frameId) it.copy(online = e.online, lastSeenAt = e.lastSeenAt ?: it.lastSeenAt) else it }))
                    }
                    is LiveEvent.MembershipUpdated -> refresh()
                    is LiveEvent.SubscriptionUpdated -> viewModelScope.launch { loadShows(listOf(e.frameId)) }
                    else -> Unit
                }
            }
        }
    }

    private fun load() = viewModelScope.launch {
        try {
            val frames = api.frames().sortedWith(order)
            _state.update { it.copy(frames = Load.Ready(frames)) }
            loadExtras(frames)
        } catch (e: Exception) {
            if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, frames = if (it.frames is Load.Error) Load.Loading else it.frames) }
            try {
                val frames = api.frames().sortedWith(order)
                _state.update { it.copy(frames = Load.Ready(frames)) }
                loadExtras(frames)
            } catch (e: Exception) {
                if (state.value.frames !is Load.Ready) _state.update { it.copy(frames = Load.Error(Problems.message(e))) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Which families each frame shows (the photo and today's reactions come with the frame list). */
    private suspend fun loadExtras(frames: List<Frame>) = loadShows(frames.map { it.id })

    private suspend fun loadShows(frameIds: List<String>) {
        val shows = frameIds.mapNotNull { id ->
            runCatching { api.frameSubscriptions(id, SubscriptionStatus.ACTIVE) }.getOrNull()?.let { subs -> id to subs.map { it.feed } }
        }.toMap()
        _state.update { it.copy(shows = it.shows + shows) }
    }

    /** Frames showing the person's [feedIds] that they aren't on. */
    fun loadFamilyFrames(feedIds: List<String>) = viewModelScope.launch {
        val subs = feedIds.flatMap { id -> runCatching { api.feedSubscriptions(id, SubscriptionStatus.ACTIVE) }.getOrDefault(emptyList()) }
        val own = (state.value.frames as? Load.Ready)?.value.orEmpty()
        _state.update { it.copy(familyFrames = FramesOrder.familyFrames(own, subs)) }
    }

    /** "Add a family": the frame shows the family with its last 50 posts (pending without the right). */
    fun addToFamily(frameId: String, feedId: String, key: String = java.util.UUID.randomUUID().toString(), onDone: (FrameAdded) -> Unit) {
        if (state.value.adding != null) return
        _state.update { it.copy(adding = frameId) }
        viewModelScope.launch {
            try {
                val sub = app.kinframe.family.ui.family.FamilyFrames.add(api, feedId, frameId, key)
                if (sub.status == SubscriptionStatus.ACTIVE) _state.update { s -> s.copy(shows = s.shows + (frameId to (s.shows[frameId].orEmpty().filterNot { it.id == feedId } + sub.feed))) }
                onDone(if (app.kinframe.family.ui.family.FamilyFrames.pending(sub)) FrameAdded.Pending else FrameAdded.Active(sub.frame.name, sub.feed.name))
            } catch (e: Exception) {
                onDone(FrameAdded.Failed(Problems.message(e)))
            } finally {
                _state.update { it.copy(adding = null) }
            }
        }
    }

    /** A new or renamed frame from another screen. */
    fun upsert(frame: Frame) = _state.update { s ->
        val list = (s.frames as? Load.Ready)?.value.orEmpty()
        s.copy(frames = Load.Ready((list.filterNot { it.id == frame.id } + frame).sortedWith(order)))
    }

    fun remove(frameId: String) = _state.update { s ->
        val list = (s.frames as? Load.Ready)?.value ?: return@update s
        s.copy(frames = Load.Ready(list.filterNot { it.id == frameId }))
    }

    private companion object {
        val order = compareBy<Frame>({ it.name.lowercase() }, { it.id })
    }
}
