package app.kinframe.frame

import android.app.Application
import android.util.Log
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.ServerUrl
import app.kinframe.core.auth.KeystoreCredentialStore
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.FramePrefs
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.data.SettingsRepository
import app.kinframe.frame.system.CrashGuard
import app.kinframe.frame.system.NetworkMonitor
import app.kinframe.frame.system.TimeSync
import app.kinframe.frame.system.RootShell
import app.kinframe.frame.system.SessionMarker
import app.kinframe.frame.system.TrustedClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency graph: one instance per process, created by [FrameApp]. */
class FrameGraph(val app: Application) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("KinFrame", "uncaught in app scope", e) },
    )
    val prefs = FramePrefs(app)
    val clock = TrustedClock()
    val root = RootShell()
    /** Sets the system clock from NTP (the Denver has no clock battery). */
    val time = TimeSync(root, app.getSharedPreferences("time", android.content.Context.MODE_PRIVATE))
    val credentials = KeystoreCredentialStore(app)
    val stateDir = File(app.filesDir, "state").apply { mkdirs() }
    val sessionMarker = SessionMarker(stateDir)
    val crashGuard = CrashGuard(stateDir)
    val media = MediaCache(app.filesDir)
    val network = NetworkMonitor(app)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // The server localizes error texts by Accept-Language: use the frame's language, not the system's.
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("Accept-Language") != null) req else req.newBuilder().header("Accept-Language", Locales.effective(app)).build())
        }
        .build()

    val discovery = DiscoveryClient(http)

    val api = DeviceApi(
        http = http,
        apiBase = (prefs.apiBase ?: "${BuildConfig.DEFAULT_SERVER}/v1").toHttpUrl(),
        credentials = credentials,
        appVersion = "kinframe-frame/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    )

    val db: FrameDatabase by lazy { FrameDatabase.open(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(db, scope) }
    val controller: FrameController by lazy { FrameController(this) }

    /** Host people should open in a browser (the server's web address, e.g. "kinframe.appmire.be"). */
    fun webHostDisplay(): String = (prefs.webUrl ?: prefs.serverUrl ?: BuildConfig.DEFAULT_SERVER)
        .let { runCatching { ServerUrl.display(it.toHttpUrl()) }.getOrDefault(it) }

    fun serverDisplayName(): String = prefs.serverName
        ?: prefs.serverUrl?.let { runCatching { ServerUrl.display(it.toHttpUrl()) }.getOrNull() }
        ?: ServerUrl.display(BuildConfig.DEFAULT_SERVER.toHttpUrl())
}
