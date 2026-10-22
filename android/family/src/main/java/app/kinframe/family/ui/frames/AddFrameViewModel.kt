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
) {
    val complete: Boolean get() = digits.length == FrameCodes.LENGTH
}

/** Claim or join a frame with the code it shows (README §5.1). */
class AddFrameViewModel(private val api: KinApi, initialCode: String? = null) : ViewModel() {
    private val _state = MutableStateFlow(AddFrameState(digits = initialCode?.let(FrameCodes::digits).orEmpty()))
    val state: StateFlow<AddFrameState> = _state.asStateFlow()

    /** One idempotency key per code, so a retry after a dropped connection returns the first result. */
    private var key = UUID.randomUUID().toString()
    private var keyFor = ""

    init {
        if (state.value.complete) submit()
    }

    /** Typing or pasting; a whole code (or join link) pasted at once is submitted right away. */
    fun onInput(text: String) {
        val before = state.value.digits
        val d = FrameCodes.digits(text)
        _state.update { it.copy(digits = d, error = null) }
        if (d.length == FrameCodes.LENGTH && d.length - before.length > 1) submit()
    }

    fun submit() {
        val s = state.value
        if (!s.complete || s.submitting) return
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

    fun reset() = _state.update { AddFrameState() }
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
