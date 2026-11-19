package app.kinframe.family.ui.frames

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Frame
import app.kinframe.core.api.InvitePreview
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.RedeemResult
import app.kinframe.family.R
import app.kinframe.family.ui.Load
import app.kinframe.core.api.FeedCodes
import app.kinframe.family.util.EnteredCode
import app.kinframe.family.util.FrameCodes
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

data class AddFrameState(
    /** Digits only; the field shows them as `1234 5678`. */
    val digits: String = "",
    val submitting: Boolean = false,
    @StringRes val error: Int? = null,
    val result: RedeemResult? = null,
    val name: String = "",
    val location: String = "",
    val saving: Boolean = false,
    @StringRes val saveError: Int? = null,
    val named: Frame? = null,
    /** A feed invite code (letters, `KF7Q-M2XA`) instead of frame digits; empty for frame codes. */
    val feedCode: String = "",
    /** The feed invite to open (from a code lookup or a pasted `/fi/` link). */
    val feedInviteToken: String? = null,
    /** The server has feeds: the field also takes feed codes. */
    val feeds: Boolean = false,
) {
    val isFeed: Boolean get() = feedCode.isNotEmpty()
    /** What the code field shows. */
    val fieldText: String get() = if (isFeed) feedCode else digits
    val complete: Boolean get() = if (isFeed) FeedCodes.normalize(feedCode) != null else digits.length == FrameCodes.LENGTH
}

/**
 * Claim or join a frame with the code it shows (README §5.1). With [feeds] on, the same field takes feed invite codes
 * (letters) and links, which are looked up and opened as a feed invite (README §5.12).
 */
class AddFrameViewModel(private val api: KinApi, initialCode: String? = null, feeds: Boolean = false) : ViewModel() {
    private val _state = MutableStateFlow(initial(initialCode, feeds))
    val state: StateFlow<AddFrameState> = _state.asStateFlow()

    /** One idempotency key per code, so a retry after a dropped connection returns the first result. */
    private var key = UUID.randomUUID().toString()
    private var keyFor = ""

    init {
        if (state.value.complete) submit()
    }

    private fun initial(code: String?, feeds: Boolean): AddFrameState = when (val c = EnteredCode.parse(code.orEmpty(), feeds)) {
        is EnteredCode.Feed -> AddFrameState(feedCode = c.code, feeds = feeds)
        is EnteredCode.FeedLink -> AddFrameState(feedInviteToken = c.token, feeds = feeds)
        is EnteredCode.Frame -> AddFrameState(digits = c.digits, feeds = feeds)
    }

    /** Typing or pasting; a whole code (or join link) pasted at once is submitted right away. */
    fun onInput(text: String) {
        val before = state.value.fieldText
        when (val c = EnteredCode.parse(text, state.value.feeds)) {
            is EnteredCode.FeedLink -> { _state.update { it.copy(feedInviteToken = c.token, error = null) }; return }
            is EnteredCode.Feed -> _state.update { it.copy(feedCode = c.code, digits = "", error = null) }
            is EnteredCode.Frame -> _state.update { it.copy(digits = c.digits, feedCode = "", error = null) }
        }
        val now = state.value
        if (now.complete && now.fieldText.length - before.length > 1) submit()
    }

    /** The feed invite screen was opened; a back press returns to an empty field. */
    fun feedInviteOpened() = _state.update { it.copy(feedInviteToken = null, feedCode = "") }

    fun submit() {
        val s = state.value
        if (!s.complete || s.submitting) return
        if (s.isFeed) { lookupFeed(FeedCodes.normalize(s.feedCode)!!); return }
        if (keyFor != s.digits) {
            key = UUID.randomUUID().toString()
            keyFor = s.digits
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val r = api.redeem(s.digits, key)
                _state.update { it.copy(submitting = false, result = r, name = r.frame.name, location = r.frame.location.orEmpty()) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = Problems.message(e)) }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v.take(40)) }
    fun onLocation(v: String) = _state.update { it.copy(location = v.take(40)) }

    /** After claiming: name the frame so the family knows which one it is. */
    fun saveName(onDone: (Frame) -> Unit) {
        val s = state.value
        val frame = s.result?.frame ?: return
        if (s.name.isBlank()) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val f = api.updateFrame(frame.id, buildJsonObject {
                    put("name", JsonPrimitive(s.name.trim()))
                    put("location", JsonPrimitive(s.location.trim()))
                })
                _state.update { it.copy(saving = false, named = f) }
                onDone(f)
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, saveError = Problems.message(e)) }
            }
        }
    }

    private fun lookupFeed(code: String) {
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val p = api.lookupFeedInvite(code)
                _state.update { it.copy(submitting = false, feedInviteToken = p.token) }
            } catch (e: Exception) {
                val invalid = e is ApiException && (e.status == 404 || e.status == 410 || e.code == ProblemCode.INVITE_INVALID || e.code == ProblemCode.INVITE_EXPIRED)
                _state.update {
                    it.copy(submitting = false, error = when {
                        !invalid -> Problems.message(e)
                        e is ApiException && (e.status == 410 || e.code == ProblemCode.INVITE_EXPIRED) -> R.string.problem_invite_expired
                        else -> R.string.feed_code_invalid
                    })
                }
            }
        }
    }

    fun reset() = _state.update { AddFrameState(feeds = it.feeds) }
}

data class InviteState(
    val preview: Load<InvitePreview> = Load.Loading,
    val accepting: Boolean = false,
    val result: RedeemResult? = null,
    @StringRes val error: Int? = null,
    val invalid: Boolean = false,
)

/** Previews and accepts an invite link (README §5.2). */
class InviteViewModel(private val api: KinApi, private val token: String) : ViewModel() {
    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(preview = Load.Loading) }
        try {
            _state.update { it.copy(preview = Load.Ready(api.previewInvite(token))) }
        } catch (e: Exception) {
            val invalid = e is ApiException && (e.code == ProblemCode.INVITE_INVALID || e.code == ProblemCode.INVITE_EXPIRED || e.status == 404 || e.status == 410)
            _state.update { it.copy(preview = Load.Error(if (invalid) inviteMessage(e) else Problems.message(e)), invalid = invalid) }
        }
    }

    fun accept() {
        if (state.value.accepting) return
        _state.update { it.copy(accepting = true, error = null) }
        viewModelScope.launch {
            try {
                val r = api.acceptInvite(token)
                _state.update { it.copy(accepting = false, result = r) }
            } catch (e: Exception) {
                _state.update { it.copy(accepting = false, error = Problems.message(e)) }
            }
        }
    }

    private fun inviteMessage(e: Exception): Int =
        if ((e as? ApiException)?.code == ProblemCode.INVITE_EXPIRED || (e as? ApiException)?.status == 410) R.string.problem_invite_expired
        else R.string.problem_invite_invalid
}
