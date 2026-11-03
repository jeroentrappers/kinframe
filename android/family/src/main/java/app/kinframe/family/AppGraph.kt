package app.kinframe.family

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.kinframe.core.api.DevAuthClient
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.TusUploader
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
import app.kinframe.family.util.DeepLink
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
data class OpenTarget(val target: String, val sendId: String? = null, val frameId: String? = null, val placementId: String? = null)

/**
 * The app's object graph (hand-wired; the app is small enough). One per process, created by [FamilyApp].
 */
class AppGraph(
    val app: Application,
    val secure: SecureStore = EncryptedSecureStore(app),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val prefs = app.getSharedPreferences("kinframe", Context.MODE_PRIVATE)

    val servers = ServerRepository(prefs, DiscoveryClient(http))
    val auth = AuthRepository(app, secure, DevAuthClient(http), { servers.current.value })

    private val userAgent = "kinframe-family/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
    private var apiCache: Pair<String, HttpKinApi>? = null

    /** The API client while signed in to a server, else null. */
    val api: StateFlow<KinApi?> = combine(servers.current, auth.state) { s, a ->
        if (s == null || a !is AuthState.SignedIn) null
        else {
            val key = s.apiBase.toString()
            apiCache?.takeIf { it.first == key }?.second ?: HttpKinApi(http, s.apiBase, auth, userAgent) { Locale.getDefault().toLanguageTag() }
                .also { apiCache = key to it }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val outbox = OutboxStore(File(app.filesDir, "outbox.json"))
    val live = LiveEvents(http, scope, auth)
    val push = PushRegistrar(app, prefs, scope) { api.value }

    /** The signed-in person (from `GET /v1/me`). */
    val me = MutableStateFlow<User?>(null)

    /** Media shared into the app from other apps, waiting for the Send screen. */
    val incomingShare = MutableStateFlow<List<Uri>>(emptyList())

    /** A frame-code or invite link waiting to be handled (possibly until sign-in). */
    val pendingLink = MutableStateFlow<DeepLink?>(null)

    /** A notification tap to route. */
    val openTarget = MutableStateFlow<OpenTarget?>(null)

    private val _sent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Ids of sends that just left the outbox (history reloads). */
    val sent: SharedFlow<String> = _sent.asSharedFlow()

    private val _pushes = MutableSharedFlow<PushMessage>(extraBufferCapacity = 8)
    val pushes: SharedFlow<PushMessage> = _pushes.asSharedFlow()

    private var foreground = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                updateLive()
                scope.launch { servers.refresh() }
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
        }, prepare = { sendId, item, onProgress -> preparer.prepare(item, SendRunner.outboxDir(app, sendId), onProgress) })
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
