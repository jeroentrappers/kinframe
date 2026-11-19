package app.kinframe.family.ui.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedPreferencesUpdate
import app.kinframe.core.api.KinApi
import app.kinframe.core.social.FeedMute
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.ThreadSummary
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedSocialState(
    /** Newer thread summaries than the posts list has (reactions, comments, live events). */
    val threads: Map<String, ThreadSummary> = emptyMap(),
    val openReports: Int = 0,
)

sealed interface FeedSocialNotice {
    data class Message(@StringRes val message: Int) : FeedSocialNotice
    data class FeedChanged(val feed: Feed) : FeedSocialNotice
}

/** Family social inside one family's screen: reactions and threads on its posts, the caller's mute, reports. */
class FeedSocialViewModel(
    private val api: SocialApi,
    private val kin: KinApi,
    private val feedId: String,
    private val me: () -> String?,
    live: Flow<LiveEvent>,
    private val admin: Boolean,
    private val chats: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedSocialState())
    val state: StateFlow<FeedSocialState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<FeedSocialNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<FeedSocialNotice> = _events.asSharedFlow()

    private val known = mutableMapOf<String, ThreadSummary>()

    init {
        if (admin && chats) loadReports()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is SocialLive.PostUpdated -> if (e.feedId == feedId) e.thread?.let { put(e.postId, it) }
                    is SocialLive.CommentCreated -> thread(e.postId)?.let { put(e.postId, ThreadLogic.withComment(it, e.comment, e.comment.author?.userId == me())) }
                    is SocialLive.ReactionUpdated -> if (e.targetKind == ReactionTarget.POST) thread(e.targetId)?.let { put(e.targetId, it.copy(reactions = e.summary)) }
                    is SocialLive.ReportCreated -> if (e.report.feedId == feedId) loadReports()
                    else -> Unit
                }
            }
        }
    }

    /** The newest summary we know for a post: ours, else what the list showed ([seed]). */
    fun thread(postId: String, seed: ThreadSummary? = null): ThreadSummary? = state.value.threads[postId] ?: known[postId] ?: seed?.also { known[postId] = it }

    private fun put(postId: String, t: ThreadSummary) = _state.update { it.copy(threads = it.threads + (postId to t)) }

    fun react(postId: String, seed: ThreadSummary, emoji: String?) = viewModelScope.launch {
        val before = thread(postId, seed) ?: seed
        put(postId, before.copy(reactions = before.reactions.withMine(emoji)))
        try {
            put(postId, before.copy(reactions = api.setReaction(ReactionTarget.POST, postId, emoji)))
        } catch (e: Exception) {
            put(postId, before)
            _events.tryEmit(FeedSocialNotice.Message(SocialProblems.message(e)))
        }
    }

    fun setMute(mute: String) = viewModelScope.launch {
        try {
            val f = kin.updateFeedPreferences(feedId, FeedPreferencesUpdate(mute = mute))
            _events.tryEmit(FeedSocialNotice.FeedChanged(f))
            _events.tryEmit(FeedSocialNotice.Message(if (mute == FeedMute.NONE) R.string.social_family_unmuted else R.string.social_family_muted))
        } catch (e: Exception) {
            _events.tryEmit(FeedSocialNotice.Message(SocialProblems.message(e)))
        }
    }

    fun loadReports() = viewModelScope.launch {
        runCatching { api.feedReports(feedId) }.onSuccess { l -> _state.update { it.copy(openReports = l.count { r -> r.isOpen }) } }
    }
}
