package app.kinframe.family.accounts

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.DevAuthClient
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.User
import app.kinframe.core.leaving.HttpLeavingApi
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.servers.AccountIndex
import app.kinframe.core.servers.HttpRemoteApi
import app.kinframe.core.servers.Merging
import app.kinframe.core.servers.RemoteApi
import app.kinframe.core.servers.Routes
import app.kinframe.core.servers.ServerRoute
import app.kinframe.core.social.HttpSocialApi
import app.kinframe.core.social.SocialApi
import app.kinframe.family.auth.AuthRepository
import app.kinframe.family.auth.AuthState
import app.kinframe.family.data.LiveEvents
import app.kinframe.family.data.SecureStore
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.UUID

/** What every account's clients share: the HTTP client, headers, the scope. */
class AccountDeps(
    val app: Application,
    val http: OkHttpClient,
    val scope: CoroutineScope,
    val userAgent: String,
    val appHeader: String,
    val prefsFor: (String) -> SharedPreferences,
    val secureFor: (String) -> SecureStore,
)

/**
 * One account (docs/spec/multi-server.md §2.1): a server, its discovery, its own sign-in, API clients, websocket,
 * push endpoint and profile. Signing out of one leaves the others.
 */
class Account(val id: String, deps: AccountDeps) {
    val prefs: SharedPreferences = deps.prefsFor(id)
    val secure: SecureStore = deps.secureFor(id)
    val servers = ServerRepository(prefs, DiscoveryClient(deps.http))
    val auth = AuthRepository(deps.app, secure, DevAuthClient(deps.http), { servers.current.value })
    val live = LiveEvents(deps.http, deps.scope, auth)
    /** The profile on this server (`GET /v1/me`). */
    val me = MutableStateFlow<User?>(null)

    private val lang = { Locale.getDefault().toLanguageTag() }

    /** All four clients for the current server while signed in, else null. */
    val clients: StateFlow<Clients?> = combine(servers.current, auth.state) { s, a ->
        if (s == null || a !is AuthState.SignedIn) null
        else cached?.takeIf { it.base == s.apiBase.toString() }?.copy(discovery = s.discovery) ?: Clients(
            base = s.apiBase.toString(), discovery = s.discovery,
            kin = HttpKinApi(deps.http, s.apiBase, auth, deps.userAgent, deps.appHeader, lang),
            social = HttpSocialApi(deps.http, s.apiBase, auth, deps.userAgent, deps.appHeader, lang),
            leaving = HttpLeavingApi(deps.http, s.apiBase, auth, deps.userAgent, deps.appHeader, lang),
            remote = HttpRemoteApi(deps.http, s.apiBase, auth, deps.userAgent, deps.appHeader, lang),
        ).also { cached = it }
    }.stateIn(deps.scope, SharingStarted.Eagerly, null)
    @Volatile private var cached: Clients? = null

    data class Clients(
        val base: String, val discovery: Discovery, val kin: KinApi, val social: SocialApi, val leaving: LeavingApi, val remote: RemoteApi,
    )

    val server: ServerChoice? get() = servers.current.value
    val signedIn: Boolean get() = clients.value != null

    /** The server chip: the operator's label, else the host. */
    val label: String get() = server?.let { it.discovery.serverName?.takeIf { n -> n.isNotBlank() } ?: it.url.host }.orEmpty()
    val host: String get() = server?.url?.host.orEmpty()

    /** Whether a push `server` field or a link's server is this account's server. */
    fun isServer(url: String?): Boolean {
        val s = server ?: return false
        return Merging.sameServer(s.url.toString(), url) || Merging.sameServer(s.discovery.baseUrl, url)
    }

    fun route(): ServerRoute? {
        val c = clients.value ?: return null
        return object : ServerRoute {
            override val id = this@Account.id
            override val kin = c.kin
            override val social = c.social.takeIf { c.discovery.social || c.discovery.chats || c.discovery.engagement }
            override val leaving = c.leaving.takeIf { c.discovery.leaving }
            override val remote = c.remote.takeIf { c.discovery.remoteFamilies }
            override val discovery = c.discovery
        }
    }

    companion object {
        /** [app.kinframe.family.notify.PushRegistrar]'s per-account keys. */
        val PUSH_KEYS = listOf("push_endpoint", "push_kind", "push_endpoint_id")
    }

    /** Forgets this account's server, tokens and push endpoint on the phone (the server side is done by the caller). */
    fun wipe() {
        auth.forgetServer()
        servers.clear()
        me.value = null
        // The first account's file is also the app's own preferences (the account list lives there).
        if (id != AccountStore.MAIN) prefs.edit(commit = true) { clear() }
        else prefs.edit(commit = true) { PUSH_KEYS.forEach { remove(it) } }
        secure.clear()
    }
}

/** Adding a server that is already in the list (same `server_id`, or same address). */
class AlreadySignedInException(val account: Account) : Exception("already signed in to ${account.host}")

/**
 * Every account of the app, the primary one first. Always has a primary [Account]: on a fresh install it is the
 * empty one onboarding fills (it joins the list once a server is chosen).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountManager(private val deps: AccountDeps, rootPrefs: SharedPreferences) {
    val store = AccountStore(rootPrefs)
    val index = AccountIndex()
    private val runtimes = mutableMapOf<String, Account>()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    /** Accounts with a server, primary first. */
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _primary: MutableStateFlow<Account>
    val primary: StateFlow<Account>

    /** The routing clients' view: signed-in accounts, primary first. */
    val routes = Routes({ _accounts.value.mapNotNull { it.route() } }, index)

    /** Bumps whenever an account signs in or out, or the list changes (merged lists reload). */
    val version: StateFlow<Int>

    private var saveJob: Job? = null

    init {
        store.loadIndex(index)
        index.onChange = {
            saveJob?.cancel()
            saveJob = deps.scope.launch { delay(2_000); store.saveIndex(index) }
        }
        val first = store.primaryId() ?: AccountStore.MAIN
        _primary = MutableStateFlow(runtime(first))
        primary = _primary.asStateFlow()
        reload()
        // The empty account onboarding fills joins the list when a server is chosen.
        deps.scope.launch {
            primary.flatMapLatest { a -> a.servers.current.map { a to it } }.collect { (a, s) ->
                if (s != null && a.id !in store.ids()) { store.add(a.id); reload() }
            }
        }
        version = _accounts.flatMapLatest { list ->
            combine(list.map { it.clients }.ifEmpty { listOf(MutableStateFlow(null)) }) { cs -> cs.count { it != null } * 1000 + list.size }
        }.stateIn(deps.scope, SharingStarted.Eagerly, 0)
    }

    private fun runtime(id: String): Account = runtimes.getOrPut(id) { Account(id, deps) }

    private fun reload() {
        _accounts.value = store.ordered().map(::runtime).filter { it.server != null }
        val p = store.primaryId()?.let(::runtime)
        if (p != null && p !== _primary.value) _primary.value = p
    }

    fun byId(id: String?): Account? = id?.let { _accounts.value.firstOrNull { a -> a.id == it } }

    /** The account an id (frame, family, post, conversation…) came from, else the primary one. */
    fun forKey(key: String?): Account = byId(index.get(key)) ?: primary.value

    /** The account whose server sent a push or owns a link (by base URL); null when the app isn't signed in there. */
    fun forServer(url: String?): Account? = url?.let { u -> _accounts.value.firstOrNull { it.isServer(u) } }

    /** More than one server: lists show a server chip. */
    val several: Boolean get() = _accounts.value.count { it.signedIn } > 1

    /** The chip for an id's account when there are several accounts, else null. */
    fun chipFor(key: String?): String? = if (!several) null else byId(index.get(key))?.label

    /** A new account for Settings → Accounts → "Add a server"; [commit] or [discard] it. */
    fun newDraft(): Account = runtime(UUID.randomUUID().toString())

    /**
     * Adds a signed-in draft to the list. A server the app is already signed in to (same `server_id`, or same
     * address) is refused with [AlreadySignedInException]; the draft is then discarded.
     */
    fun commit(draft: Account) {
        val d = draft.server ?: throw IllegalStateException("no server")
        val existing = _accounts.value.firstOrNull { a ->
            a.id != draft.id && a.server?.let { s ->
                (d.discovery.serverId != null && s.discovery.serverId == d.discovery.serverId) || Merging.sameServer(s.url, d.url)
            } == true
        }
        if (existing != null) { discard(draft); throw AlreadySignedInException(existing) }
        store.add(draft.id)
        reload()
    }

    fun discard(draft: Account) {
        if (draft.id in store.ids()) return
        draft.wipe()
        runtimes.remove(draft.id)
    }

    fun setPrimary(id: String) {
        store.setPrimary(id)
        reload()
    }

    /** Removes an account from the list (after signing out of it); the next one becomes primary. */
    fun remove(account: Account) {
        account.live.stop()
        index.forget(account.id)
        store.remove(account.id)
        account.wipe()
        if (account.id != AccountStore.MAIN) runtimes.remove(account.id)
        reload()
    }
}
