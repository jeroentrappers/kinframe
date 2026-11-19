package app.kinframe.family.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinframe.core.api.User
import app.kinframe.core.design.Kin
import app.kinframe.family.AppGraph
import app.kinframe.family.R
import app.kinframe.family.auth.AuthState
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.findActivity
import app.kinframe.family.ui.onboarding.OnboardingDeps
import app.kinframe.family.ui.onboarding.OnboardingViewModel
import app.kinframe.family.ui.onboarding.ServerScreen
import app.kinframe.family.ui.onboarding.SignInReason
import app.kinframe.family.ui.onboarding.SignInScreen
import app.kinframe.family.util.DeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Add a server": from Settings → Accounts ([url] null), or from a link of a server the app isn't signed in to. */
data class AddServerRequest(val url: String? = null, val link: DeepLink? = null)

object Accounts {
    /** The id a link's screen calls the API with (invite token or frame code), so it goes to the link's account. */
    fun linkKey(link: DeepLink): String = when (link) {
        is DeepLink.FrameCode -> link.code
        is DeepLink.Invite -> link.token
        is DeepLink.FeedInvite -> link.token
    }
}

sealed interface AddServerOutcome {
    data class Added(val account: Account) : AddServerOutcome
    /** The server was already in the list (same `server_id` or address). */
    data class Already(val account: Account) : AddServerOutcome
}

/**
 * Adds an account: a new [Account] draft goes through the onboarding steps (address, sign-in) and joins the list
 * once signed in. A placeholder name is replaced by the sign-in's name or the primary account's name.
 */
class AddServerViewModel(private val graph: AppGraph, val request: AddServerRequest) : ViewModel() {
    val draft: Account = graph.accounts.newDraft()
    private val _outcome = MutableStateFlow<AddServerOutcome?>(null)
    val outcome: StateFlow<AddServerOutcome?> = _outcome.asStateFlow()

    val onboarding = OnboardingViewModel(OnboardingDeps(
        check = { draft.servers.check(it) },
        choose = { draft.servers.choose(it) },
        signInLogto = { draft.auth.signInWithLogto(it) },
        signInDev = { e, n -> draft.auth.signInDev(e, n) },
    ))

    init {
        request.url?.let { u ->
            onboarding.openCustom(true)
            onboarding.onCustomInput(u)
            onboarding.checkCustom()
        }
        viewModelScope.launch {
            draft.auth.state.first { it is AuthState.SignedIn }
            finish()
        }
    }

    private suspend fun finish() {
        try {
            graph.accounts.commit(draft)
        } catch (e: AlreadySignedInException) {
            _outcome.value = AddServerOutcome.Already(e.account)
            return
        }
        val api = draft.clients.first { it != null }!!.kin
        runCatching {
            var me = api.me()
            if (me.displayName == User.PLACEHOLDER_NAME || me.displayName.isBlank()) {
                val name = draft.auth.idTokenName() ?: graph.me.value?.displayName
                if (!name.isNullOrBlank()) me = api.updateMe(displayName = name.take(40))
            }
            draft.me.value = me
        }
        request.link?.let { link ->
            graph.accounts.index.put(Accounts.linkKey(link), draft.id)
            graph.pendingLink.value = link
        }
        _outcome.value = AddServerOutcome.Added(draft)
    }

    /** Leaving the flow without signing in forgets the draft. */
    fun cancel() {
        if (_outcome.value !is AddServerOutcome.Added) graph.accounts.discard(draft)
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}

/** The add-server flow on top of everything (address, then sign-in). */
@Composable
fun AddServerFlow(graph: AppGraph, request: AddServerRequest, onDone: (AddServerOutcome?) -> Unit) {
    val vm: AddServerViewModel = viewModel(key = "add-server-${request.hashCode()}") { AddServerViewModel(graph, request) }
    val state by vm.onboarding.state.collectAsStateWithLifecycle()
    val server by vm.draft.servers.current.collectAsStateWithLifecycle()
    val expired by vm.draft.auth.expired.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(outcome) { outcome?.let(onDone) }
    val close = { vm.cancel(); onDone(null) }
    BackHandler { if (server != null && request.url == null) vm.draft.servers.clear() else close() }
    Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        val s = server
        if (s == null) {
            Column(Modifier.fillMaxSize()) {
                ServerScreen(state.copy(customOpen = true), vm.onboarding::onCustomInput, vm.onboarding::checkCustom, vm.onboarding::useCustom, close)
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                KinTopBar(stringResource(R.string.servers_add_title), onBack = close)
                request.link?.let {
                    Text(stringResource(R.string.servers_link_other_server, s.url.host), style = Kin.type.body, color = Kin.colors.inkSoft,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                Box(Modifier.weight(1f)) {
                    SignInScreen(
                        s, state, expired,
                        if (request.link != null) SignInReason.Invite else SignInReason.None,
                        onSignIn = { context.findActivity()?.let(vm.onboarding::signIn) },
                        onDevEmail = vm.onboarding::onDevEmail, onDevName = vm.onboarding::onDevName, onDevSignIn = vm.onboarding::signInDev,
                        onChangeServer = { vm.draft.servers.clear() },
                    )
                }
            }
        }
    }
}
