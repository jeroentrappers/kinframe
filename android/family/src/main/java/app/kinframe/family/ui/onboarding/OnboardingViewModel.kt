package app.kinframe.family.ui.onboarding

import android.app.Activity
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ServerUrl
import app.kinframe.family.R
import app.kinframe.family.auth.LogtoAuth
import app.kinframe.family.data.ServerCheckException
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.util.Problems
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    /** The build's default server, checked in the background so its name shows on the welcome screen. */
    val defaultServer: ServerChoice? = null,
    val defaultChecking: Boolean = true,
    val customOpen: Boolean = false,
    val customInput: String = "",
    val customChecking: Boolean = false,
    val customFound: ServerChoice? = null,
    @StringRes val customError: Int? = null,
    val signingIn: Boolean = false,
    @StringRes val signInError: Int? = null,
    val devEmail: String = "",
    val devName: String = "",
)

/** Dependencies as functions so tests can drive the flow without Android. */
class OnboardingDeps(
    val check: suspend (String) -> ServerChoice,
    val choose: (ServerChoice) -> Unit,
    val signInLogto: suspend (Activity) -> Unit,
    val signInDev: suspend (String, String) -> Unit,
)

class OnboardingViewModel(private val deps: OnboardingDeps, defaultUrl: String = ServerUrl.DEFAULT) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val d = runCatching { deps.check(defaultUrl) }.getOrNull()
            _state.update { it.copy(defaultServer = d, defaultChecking = false) }
        }
    }

    fun useDefault() {
        val d = state.value.defaultServer
        if (d != null) deps.choose(d) else {
            // The default server could not be reached yet: try again, and choose it when it answers.
            _state.update { it.copy(defaultChecking = true) }
            viewModelScope.launch {
                val r = runCatching { deps.check(ServerUrl.DEFAULT) }
                _state.update { it.copy(defaultServer = r.getOrNull(), defaultChecking = false, customError = if (r.isFailure) R.string.server_unreachable else null) }
                r.getOrNull()?.let(deps.choose)
            }
        }
    }

    fun openCustom(open: Boolean) = _state.update { it.copy(customOpen = open, customError = null) }

    fun onCustomInput(text: String) = _state.update { it.copy(customInput = text, customFound = null, customError = null) }

    fun checkCustom() {
        val input = state.value.customInput
        if (input.isBlank()) return
        checkJob?.cancel()
        _state.update { it.copy(customChecking = true, customError = null, customFound = null) }
        checkJob = viewModelScope.launch {
            try {
                val found = deps.check(input)
                _state.update { it.copy(customChecking = false, customFound = found) }
            } catch (e: ServerCheckException.Invalid) {
                _state.update { it.copy(customChecking = false, customError = R.string.server_invalid) }
            } catch (e: Exception) {
                _state.update { it.copy(customChecking = false, customError = R.string.server_unreachable) }
            }
        }
    }

    fun useCustom() {
        state.value.customFound?.let { deps.choose(it); _state.update { s -> s.copy(customOpen = false) } }
    }

    fun signIn(activity: Activity) {
        if (state.value.signingIn) return
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            try {
                deps.signInLogto(activity)
                _state.update { it.copy(signingIn = false) }
            } catch (e: LogtoAuth.SignInException) {
                _state.update { it.copy(signingIn = false, signInError = if (e.cancelled) null else R.string.signin_failed) }
            } catch (e: Exception) {
                _state.update { it.copy(signingIn = false, signInError = R.string.signin_failed) }
            }
        }
    }

    fun onDevEmail(v: String) = _state.update { it.copy(devEmail = v) }
    fun onDevName(v: String) = _state.update { it.copy(devName = v) }

    fun signInDev() {
        val s = state.value
        if (s.signingIn || !s.devEmail.contains('@')) return
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            try {
                deps.signInDev(s.devEmail, s.devName)
                _state.update { it.copy(signingIn = false) }
            } catch (e: Exception) {
                _state.update { it.copy(signingIn = false, signInError = Problems.message(e)) }
            }
        }
    }
}
