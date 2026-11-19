package app.kinframe.family

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.kinframe.core.api.AppVersionInterceptor
import app.kinframe.core.api.FamilyAppHeader
import app.kinframe.family.update.AppUpdates
import app.kinframe.family.update.PrefsDismissStore
import kotlinx.coroutines.flow.map
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.TusUploader
import app.kinframe.core.api.TusResult
import app.kinframe.core.api.PreparedProfile
import app.kinframe.family.send.OutboxItem
import app.kinframe.core.api.User
import app.kinframe.family.auth.AuthRepository
import app.kinframe.family.auth.AuthState
import app.kinframe.family.data.EncryptedSecureStore
import app.kinframe.family.data.LiveEvents
import app.kinframe.family.data.SecureStore
import app.kinframe.family.data.ServerRepository
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushRegistrar
import app.kinframe.family.send.ItemSource
import app.kinframe.family.send.prep.MediaPreparer
import app.kinframe.family.send.MediaFiles
import app.kinframe.family.send.OutboxState
import app.kinframe.family.send.OutboxStore
import app.kinframe.family.send.SendRunner
import app.kinframe.family.send.SendWorker
import app.kinframe.family.social.SocialGraph
import app.kinframe.family.util.DeepLink
import app.kinframe.family.ui.sources.GoogleImportFlow
import app.kinframe.family.ui.tv.TvPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.servers.RemoteApi
import app.kinframe.core.servers.RoutingKinApi
import app.kinframe.core.servers.RoutingLeavingApi
import app.kinframe.core.servers.RoutingSocialApi
import app.kinframe.core.social.SocialApi
import app.kinframe.family.accounts.AddServerRequest
import app.kinframe.family.accounts.Account
import app.kinframe.family.accounts.AccountDeps
import app.kinframe.family.accounts.AccountManager
import app.kinframe.family.accounts.AccountStore
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.notify.PushAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest

/** A request from outside the app: a notification tap. */
data class OpenTarget(
    val target: String, val sendId: String? = null, val frameId: String? = null, val placementId: String? = null,
    val feedId: String? = null, val postId: String? = null,
    /** Photo sources (README §6.3): the source that needs attention, the Google import that finished. */
    val sourceId: String? = null, val importId: String? = null,
    /** Email to frame and Telegram: the address with pending senders, the chat that needs attention. */
    val addressId: String? = null, val chatId: String? = null,
    /** Family social: the push's other ids (`conversation_id`, `comment_id`, `week_of`, …; README §6.3). */
    val ids: Map<String, String> = emptyMap(),
    /** The local account the push was routed to (screens that name no id of their server, like the weekly summary). */
    val accountId: String? = null,
)


/**
 * The app's object graph (hand-wired; the app is small enough). One per process, created by [FamilyApp].
 *
 * Several servers (docs/spec/multi-server.md): [accounts] holds every account; [servers], [auth] and [me] are the
 * primary account's, and [api] and [social] are routing clients that merge lists across accounts and send every call
 * about one frame, family, send, post or chat to its own server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(
    val app: Application,
    secureFor: (String) -> SecureStore = { id -> EncryptedSecureStore(app, AccountStore.secureName(id)) },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** `X-Kinframe-App` on every request (README §5.15). */
    val appHeader = FamilyAppHeader.value(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AppVersionInterceptor(appHeader) { updates.onUpdateRequired() })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val prefs = app.getSharedPreferences("kinframe", Context.MODE_PRIVATE)

    private val userAgent = "kinframe-family/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"

    /** Every account of the app (multi-server.md §2.1). */
    val accounts = AccountManager(
        AccountDeps(app, http, scope, userAgent, appHeader, { id -> app.getSharedPreferences(AccountStore.prefsName(id), Context.MODE_PRIVATE) }, secureFor),
        prefs,
    )

    /** The primary account's server (onboarding, settings, discovery flags). */
    val servers: ServerRepository get() = accounts.primary.value.servers
    /** The primary account's sign-in. */
    val auth: AuthRepository get() = accounts.primary.value.auth

    /** The primary account's server, following a change of primary. */
    val server: StateFlow<ServerChoice?> = accounts.primary.flatMapLatest { it.servers.current }
        .stateIn(scope, SharingStarted.Eagerly, accounts.primary.value.servers.current.value)

    /** The primary account's sign-in state, following a change of primary. */
    val authState: StateFlow<AuthState> = accounts.primary.flatMapLatest { it.auth.state }
        .stateIn(scope, SharingStarted.Eagerly, accounts.primary.value.auth.state.value)
    val authExpired: StateFlow<Boolean> = accounts.primary.flatMapLatest { it.auth.expired }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Force update and "new version available" (discovery `app_versions.android`). */
    val updates = AppUpdates(
        build = BuildConfig.VERSION_CODE,
        policy = server.map { it?.discovery?.appVersions?.android }
            .stateIn(scope, SharingStarted.Eagerly, server.value?.discovery?.appVersions?.android),
        dismissals = PrefsDismissStore(prefs),
        refreshDiscovery = { accounts.accounts.value.map { it.servers.refresh() }.any { it } || servers.refresh() },
        scope = scope,
    )

    private val primaryClients: StateFlow<Account.Clients?> = accounts.primary.flatMapLatest { it.clients }
        .stateIn(scope, SharingStarted.Eagerly, accounts.primary.value.clients.value)

    private var apiCache: Triple<Account.Clients, Int, RoutingKinApi>? = null

    /**
     * The API client while the primary account is signed in, else null. A new instance when the primary account or
     * the set of signed-in accounts changes, so merged lists load again.
     */
    val api: StateFlow<KinApi?> = combine(primaryClients, accounts.version) { c, v ->
        if (c == null) null
        else apiCache?.takeIf { it.first === c && it.second == v }?.third
            ?: RoutingKinApi(accounts.routes, c.kin).also { apiCache = Triple(c, v, it) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Leaving (README §5.21) on the server [key] (a frame, family, request… id) belongs to; null where it is off. */
    fun leavingFor(key: String?): LeavingApi? = accounts.forKey(key).route()?.leaving

    /** Leaving across accounts (keep requests and former places merged; the rest on its own server). */
    val leaving = RoutingLeavingApi(accounts.routes)

    /** Families on other servers (README §5.22) on [key]'s server; null where it is off. */
    fun remoteFor(key: String?): RemoteApi? = accounts.forKey(key).route()?.remote

    /** Discovery of [key]'s server. */
    fun discoveryFor(key: String?): Discovery? = accounts.forKey(key).server?.discovery

    val outbox = OutboxStore(File(app.filesDir, "outbox.json"))

    /** Every account's websocket events in one stream (ids of two servers don't collide). */
    val live = LiveEvents(http, scope, object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean): String = throw SessionExpiredException()
    })

    val push = PushRegistrar(app, scope) { accounts.accounts.value.map { PushAccount(it.id, it.prefs, it.clients.value?.kin) } }
    /** Shows a frame's library on a TV connected to the phone (Cast screen / Miracast). */
    val tv = TvPresenter(app, scope) { api.value }

    /** The primary account's profile (from `GET /v1/me`). */
    val me = MutableStateFlow<User?>(null)

    /** Media shared into the app from other apps, waiting for the Send screen. */
    val incomingShare = MutableStateFlow<List<Uri>>(emptyList())

    /** A frame-code or invite link waiting to be handled (possibly until sign-in). */
    val pendingLink = MutableStateFlow<DeepLink?>(null)

    /** Settings → Accounts → "Add a server", or a link of a server the app is not signed in to (multi-server.md §2.1). */
    val addServer = MutableStateFlow<AddServerRequest?>(null)

    /** A notification tap to route. */
    val openTarget = MutableStateFlow<OpenTarget?>(null)

    /** Text shared into the app (an Immich or iCloud album link), waiting for "Sync this album to…". */
    val incomingSourceLink = MutableStateFlow<String?>(null)

    /** "Add from Google Photos": one import at a time, across screens and the trip to Google and back. */
    val googleImports = GoogleImportFlow({ api.value }, scope, live.events)

    private val _sent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Ids of sends that just left the outbox (history reloads). */
    val sent: SharedFlow<String> = _sent.asSharedFlow()

    private val _pushes = MutableSharedFlow<PushMessage>(extraBufferCapacity = 8)
    val pushes: SharedFlow<PushMessage> = _pushes.asSharedFlow()

    /** Family social: timeline, threads, chats, encouragement (docs/spec/family-social.md). */
    val social = SocialGraph(this)

    private var foreground = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                updateLive()
                // Re-reads discovery (throttled): limits, features and the app version policy.
                updates.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                updateLive()
            }
        })
        // Each account's websocket feeds the one stream.
        scope.launch {
            accounts.accounts.collectLatest { list -> coroutineScope { list.forEach { a -> launch { a.live.events.collect { live.emit(it) } } } } }
        }
        scope.launch {
            accounts.version.collect {
                updateLive()
                if (accounts.accounts.value.any { a -> a.signedIn }) push.ensureRegistered()
                loadProfiles()
            }
        }
        // A new primary account: its profile loads again.
        scope.launch {
            var last = accounts.primary.value.id
            accounts.primary.collect { if (it.id != last) { last = it.id; me.value = it.me.value } }
        }
        scope.launch { me.collect { u -> if (u != null) accounts.primary.value.me.value = u } }
        // Sends left in the outbox (app killed mid-upload, phone rebooted) are picked up again.
        outbox.sends.value.filter { it.state != OutboxState.FAILED }.forEach { SendWorker.enqueue(app, it.id) }
    }

    /**
     * The person's profile on every other signed-in account (`GET /v1/me` there): screens of that account's frames,
     * families, posts and chats need its user id ("You", own comments and messages) and roles. The primary account's
     * profile is loaded by the app's root view model.
     */
    private fun loadProfiles() {
        val primary = accounts.primary.value.id
        accounts.accounts.value.filter { it.id != primary && it.me.value == null }.forEach { a ->
            val kin = a.clients.value?.kin ?: return@forEach
            scope.launch { runCatching { kin.me() }.onSuccess { u -> if (a.me.value == null) a.me.value = u } }
        }
    }

    /** [accountId]'s profile as its server just answered it (read receipts…); the primary one's is also [me]. */
    fun setProfile(accountId: String, user: User) {
        val a = accounts.byId(accountId) ?: accounts.primary.value
        a.me.value = user
        if (a.id == accounts.primary.value.id) me.value = user
    }

    /** The social client pinned to [accountId] (that server's reminders, blocked people, milestones, reports). */
    fun socialFor(accountId: String): SocialApi? =
        (social.api.value as? RoutingSocialApi)?.forAccount(accountId) ?: social.api.value

    /** The API client pinned to [accountId] (a new family there, a frame code typed for that server). */
    fun kinFor(accountId: String): KinApi? = (api.value as? RoutingKinApi)?.forAccount(accountId) ?: api.value

    private fun updateLive() {
        accounts.accounts.value.forEach { a ->
            val c = a.clients.value
            if (foreground && c != null) a.live.start(c.kin) else a.live.stop()
        }
    }

    /**
     * A push arrived: its account is the payload's `server`, else the endpoint's account ([instanceAccount]), else the
     * account its ids are already known for, else the primary one (README §6.3); see [AccountManager.accountForPush].
     */
    fun routePush(msg: PushMessage, instanceAccount: String?): Account = accounts.accountForPush(msg.server, msg.ids, instanceAccount)

    /**
     * A push from UnifiedPush ([instanceAccount]: the endpoint's account) or FCM (null): routed to its account, which
     * the notification then carries, so its buttons and its tap act on that server; then shown and passed on.
     */
    fun receivePush(context: Context, msg: PushMessage, instanceAccount: String?) {
        val routed = msg.copy(account = routePush(msg, instanceAccount).id)
        Notifications.showPush(context, routed)
        onPush(routed)
    }

    /** The send runner for [server]'s account (an outbox send's `server`), else the primary account's. */
    fun sendRunner(server: String? = null): SendRunner? {
        val account = accounts.forServer(server) ?: accounts.primary.value
        val s = account.server ?: return null
        val c = account.clients.value ?: return null
        val tus = TusUploader(http, s.apiBase.newBuilder().addPathSegment("uploads").build(), account.auth)
        val preparer = MediaPreparer(app)
        return SendRunner(outbox, c.kin, tus, sourceFor = { ItemSource(app, it) }, hash = { item ->
            runCatching { MediaFiles.sha256(ItemSource(app, item).open(0)) }.getOrNull()
        }, prepare = { filesKey, item, onProgress -> preparer.prepare(item, SendRunner.outboxDir(app, filesKey), onProgress) },
            sendChat = social.chatSender)
    }

    /**
     * Uploads the photo of an event sent to a frame: prepared on the phone like any photo (never the full original), then
     * a tus upload to [frameId]'s server. Returns the upload id for `photo_upload_id`.
     */
    suspend fun uploadEventPhoto(uri: Uri, frameId: String? = null, onProgress: (Float) -> Unit): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val account = accounts.forKey(frameId)
        val s = account.server ?: throw java.io.IOException("no server")
        val info = MediaFiles.inspect(app, uri) ?: throw java.io.IOException("cannot read $uri")
        val dir = java.io.File(app.cacheDir, "event-photo/${java.util.UUID.randomUUID()}")
        try {
            val item = OutboxItem(
                id = "event", uri = uri.toString(), mime = info.mime, name = info.name, size = info.size,
            )
            val prepared = MediaPreparer(app).prepare(item, dir) { onProgress(it * 0.3f) }
            val tus = TusUploader(http, s.apiBase.newBuilder().addPathSegment("uploads").build(), account.auth)
            val meta = buildMap {
                put("filename", prepared.uploadName); put("filetype", prepared.uploadMime)
                if (prepared.isPrepared) prepared.prepared?.let { put(PreparedProfile.KEY, it) }
            }
            when (val r = tus.upload(ItemSource(app, prepared), meta, onProgress = { sent, total -> if (total > 0) onProgress(0.3f + 0.7f * sent / total) })) {
                is TusResult.Uploaded -> r.uploadId
                is TusResult.Existing -> r.itemId
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    fun onSent(sendId: String) {
        _sent.tryEmit(sendId)
    }

    fun onPush(msg: PushMessage) {
        _pushes.tryEmit(msg)
    }

    private fun pushAccount(a: Account) = PushAccount(a.id, a.prefs, a.clients.value?.kin)

    /**
     * Signs out of [account] (default: the primary one): its push endpoint, tokens, cached profile, websocket. With
     * other accounts left it leaves the list and the next one becomes primary; the only account stays, signed out.
     */
    suspend fun signOut(activity: android.app.Activity?, account: Account = accounts.primary.value) {
        val others = accounts.accounts.value.any { it.id != account.id }
        push.unregisterFromServerNow(pushAccount(account), alsoInstance = others)
        account.live.stop()
        account.auth.signOut(activity)
        if (others) {
            accounts.remove(account)
            me.value = null
        } else {
            account.me.value = null
            me.value = null
        }
    }

    /** Switching the only server signs out first (product spec 4.1). */
    suspend fun forgetServer() {
        val a = accounts.primary.value
        push.unregisterFromServerNow(pushAccount(a))
        a.live.stop()
        a.auth.forgetServer()
        me.value = null
        a.me.value = null
        a.servers.clear()
    }
}
