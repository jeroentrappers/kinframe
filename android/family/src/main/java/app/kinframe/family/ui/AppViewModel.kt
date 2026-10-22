package app.kinframe.family.ui

import android.app.Activity
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.User
import app.kinframe.family.AppGraph
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.util.DeepLink
import app.kinframe.family.util.DeepLinks
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the app is in its life: choosing a server, signing in, naming yourself, or in use. */
sealed interface Phase {
    data object NoServer : Phase
    data class SignedOut(val server: ServerChoice) : Phase
    data object Loading : Phase
    data class ProfileError(@StringRes val message: Int) : Phase
    data object NeedsName : Phase
    data class Ready(val api: KinApi, val me: User, val server: ServerChoice) : Phase
}

data class ProfileState(
    val loading: Boolean = false,
    @StringRes val error: Int? = null,
    val nameDraft: String = "",
    val savingName: Boolean = false,
    @StringRes val nameError: Int? = null,
)

/** Root state: phase, the signed-in profile, deep links that may need a server switch. */
class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val profile = MutableStateFlow(ProfileState())
    val profileState: StateFlow<ProfileState> = profile.asStateFlow()

    /** A link for another server, waiting for the person to agree to switch. */
    private val _switchPrompt = MutableStateFlow<DeepLink?>(null)
    val switchPrompt: StateFlow<DeepLink?> = _switchPrompt.asStateFlow()

    val phase: StateFlow<Phase> = combine(graph.servers.current, graph.api, graph.me, profile) { server, api, me, p ->
        when {
            server == null -> Phase.NoServer
            api == null -> Phase.SignedOut(server)
            me == null && p.error != null -> Phase.ProfileError(p.error)
            me == null -> Phase.Loading
            me.displayName == User.PLACEHOLDER_NAME || me.displayName.isBlank() -> Phase.NeedsName
            else -> Phase.Ready(api, me, server)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Phase.Loading)

    init {
        viewModelScope.launch {
            graph.api.collect { api -> if (api != null && graph.me.value == null) loadMe(api) }
        }
    }

    fun retryProfile() { graph.api.value?.let { loadMe(it) } }

    private fun loadMe(api: KinApi) = viewModelScope.launch {
        profile.update { it.copy(loading = true, error = null) }
        try {
            var me = api.me()
            if (me.displayName == User.PLACEHOLDER_NAME) {
                // New accounts start with a placeholder; the Logto ID token usually has the real name.
                graph.auth.idTokenName()?.take(40)?.let { n -> me = runCatching { api.updateMe(displayName = n) }.getOrDefault(me) }
            }
            graph.me.value = me
            profile.update { it.copy(loading = false, nameDraft = if (me.displayName == User.PLACEHOLDER_NAME) "" else me.displayName) }
        } catch (e: Exception) {
            profile.update { it.copy(loading = false, error = Problems.message(e)) }
        }
    }

    fun onNameDraft(v: String) = profile.update { it.copy(nameDraft = v.take(40), nameError = null) }

    fun saveName() {
        val api = graph.api.value ?: return
        val name = profile.value.nameDraft.trim()
        if (name.isEmpty()) return
        profile.update { it.copy(savingName = true, nameError = null) }
        viewModelScope.launch {
            try {
                graph.me.value = api.updateMe(displayName = name)
                profile.update { it.copy(savingName = false) }
            } catch (e: Exception) {
                profile.update { it.copy(savingName = false, nameError = Problems.message(e)) }
            }
        }
    }

    /** A frame-code or invite link arrived. Links for another server ask first (product spec 4.1). */
    fun onLink(link: DeepLink) {
        val current = graph.servers.current.value
        if (current == null || DeepLinks.sameServer(current.url, link.server)) {
            graph.pendingLink.value = link
        } else {
            _switchPrompt.value = link
        }
    }

    fun dismissSwitch() { _switchPrompt.value = null }

    fun confirmSwitch() {
        val link = _switchPrompt.value ?: return
        _switchPrompt.value = null
        viewModelScope.launch {
            val choice = runCatching { graph.servers.check(link.server) }.getOrNull() ?: return@launch
            graph.forgetServer()
            graph.servers.choose(choice)
            graph.pendingLink.value = link
        }
    }

    fun signOut(activity: Activity?) = viewModelScope.launch {
        graph.signOut(activity)
        profile.update { ProfileState() }
    }

    fun changeServer() = viewModelScope.launch {
        graph.forgetServer()
        profile.update { ProfileState() }
    }
}
