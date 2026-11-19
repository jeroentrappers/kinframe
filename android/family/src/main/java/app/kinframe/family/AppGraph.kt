package app.kinframe.family

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.kinframe.core.api.AppVersionInterceptor
import app.kinframe.core.api.DevAuthClient
import app.kinframe.core.api.FamilyAppHeader
import app.kinframe.family.update.AppUpdates
import app.kinframe.family.update.PrefsDismissStore
import kotlinx.coroutines.flow.map
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.HttpKinApi
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
import java.util.Locale
import java.util.concurrent.TimeUnit

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
)

/**
 * The app's object graph (hand-wired; the app is small enough). One per process, created by [FamilyApp].
 */
class AppGraph(
    val app: Application,
    val secure: SecureStore = EncryptedSecureStore(app),
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

    val servers = ServerRepository(prefs, DiscoveryClient(http))

    /** Force update and "new version available" (discovery `app_versions.android`). */
    val updates = AppUpdates(
        build = BuildConfig.VERSION_CODE,
        policy = servers.current.map { it?.discovery?.appVersions?.android }
            .stateIn(scope, SharingStarted.Eagerly, servers.current.value?.discovery?.appVersions?.android),
        dismissals = PrefsDismissStore(prefs),
        refreshDiscovery = { servers.refresh() },
        scope = scope,
    )
    val auth = AuthRepository(app, secure, DevAuthClient(http), { servers.current.value })

    private val userAgent = "kinframe-family/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
    private var apiCache: Pair<String, HttpKinApi>? = null

    /** The API client while signed in to a server, else null. */
    val api: StateFlow<KinApi?> = combine(servers.current, auth.state) { s, a ->
        if (s == null || a !is AuthState.SignedIn) null
        else {
            val key = s.apiBase.toString()
            apiCache?.takeIf { it.first == key }?.second ?: HttpKinApi(http, s.apiBase, auth, userAgent, appHeader) { Locale.getDefault().toLanguageTag() }
                .also { apiCache = key to it }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val outbox = OutboxStore(File(app.filesDir, "outbox.json"))
    val live = LiveEvents(http, scope, auth)
    val push = PushRegistrar(app, prefs, scope) { api.value }
    /** Shows a frame's library on a TV connected to the phone (Cast screen / Miracast). */
    val tv = TvPresenter(app, scope) { api.value }

    /** The signed-in person (from `GET /v1/me`). */
    val me = MutableStateFlow<User?>(null)

    /** Media shared into the app from other apps, waiting for the Send screen. */
    val incomingShare = MutableStateFlow<List<Uri>>(emptyList())

    /** A frame-code or invite link waiting to be handled (possibly until sign-in). */
    val pendingLink = MutableStateFlow<DeepLink?>(null)

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
        scope.launch { api.collect { updateLive(); if (it != null) push.ensureRegistered() } }
        // Sends left in the outbox (app killed mid-upload, phone rebooted) are picked up again.
        outbox.sends.value.filter { it.state != OutboxState.FAILED }.forEach { SendWorker.enqueue(app, it.id) }
    }

    private fun updateLive() {
        val a = api.value
        if (foreground && a != null) live.start(a) else live.stop()
    }

    fun sendRunner(): SendRunner? {
        val s = servers.current.value ?: return null
        val a = api.value ?: return null
        val tus = TusUploader(http, s.apiBase.newBuilder().addPathSegment("uploads").build(), auth)
        val preparer = MediaPreparer(app)
        return SendRunner(outbox, a, tus, sourceFor = { ItemSource(app, it) }, hash = { item ->
            runCatching { MediaFiles.sha256(ItemSource(app, item).open(0)) }.getOrNull()
        }, prepare = { sendId, item, onProgress -> preparer.prepare(item, SendRunner.outboxDir(app, sendId), onProgress) },
            sendChat = social.chatSender)
    }

    /**
     * Uploads the photo of an event sent to a frame: prepared on the phone like any photo (never the full original), then
     * a tus upload. Returns the upload id for `photo_upload_id`.
     */
    suspend fun uploadEventPhoto(uri: Uri, onProgress: (Float) -> Unit): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val s = servers.current.value ?: throw java.io.IOException("no server")
        val info = MediaFiles.inspect(app, uri) ?: throw java.io.IOException("cannot read $uri")
        val dir = java.io.File(app.cacheDir, "event-photo/${java.util.UUID.randomUUID()}")
        try {
            val item = OutboxItem(
                id = "event", uri = uri.toString(), mime = info.mime, name = info.name, size = info.size,
            )
            val prepared = MediaPreparer(app).prepare(item, dir) { onProgress(it * 0.3f) }
            val tus = TusUploader(http, s.apiBase.newBuilder().addPathSegment("uploads").build(), auth)
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

    /** Signs out everywhere: server push endpoint, tokens, cached profile, websocket. */
    suspend fun signOut(activity: android.app.Activity?) {
        push.unregisterFromServerNow()
        live.stop()
        auth.signOut(activity)
        me.value = null
    }

    /** Switching servers signs out first (product spec 4.1). */
    suspend fun forgetServer() {
        push.unregisterFromServerNow()
        live.stop()
        auth.forgetServer()
        me.value = null
        servers.clear()
    }
}
