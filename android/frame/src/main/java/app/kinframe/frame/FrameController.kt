package app.kinframe.frame

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.Heartbeat
import app.kinframe.frame.data.SyncStateEntity
import app.kinframe.frame.schedule.DisplayController
import app.kinframe.frame.setup.SetupController
import app.kinframe.frame.slideshow.SlideshowController
import app.kinframe.frame.sync.FrameSession
import app.kinframe.frame.system.PowerActions
import app.kinframe.frame.update.UpdateManager
import app.kinframe.frame.weather.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

sealed interface AppScreen {
    data object Loading : AppScreen
    data object Setup : AppScreen
    data object Slideshow : AppScreen
    data object Recovery : AppScreen
    data object ShuttingDown : AppScreen
    data class SafeToUnplug(val rootRemounted: Boolean) : AppScreen
    data object Restarting : AppScreen
}

/**
 * Top-level state of the frame: which screen shows, the device session, and the glue between sync, slideshow,
 * display (sleep/brightness) and power.
 */
class FrameController(val g: FrameGraph) {
    val scope: CoroutineScope = g.scope
    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Loading)
    val screen: StateFlow<AppScreen> = _screen

    private val _session = MutableStateFlow<FrameSession?>(null)
    val session: StateFlow<FrameSession?> = _session

    val setup = SetupController(this)
    val display = DisplayController(this)
    val slideshow by lazy { SlideshowController(this) }
    val power = PowerActions(this)
    val update = UpdateManager(this)

    /** Frame name shown by the `identify` command (null = hidden). */
    val identify = MutableStateFlow<String?>(null)
    val settingsOpen = MutableStateFlow(false)

    val syncState: StateFlow<SyncStateEntity?> by lazy {
        g.db.syncState().observe().stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** Last `weather` change from the server, persisted in Room so it survives a reboot while offline. */
    val weather: StateFlow<Weather?> by lazy {
        syncState.map { it?.weatherJson }.distinctUntilChanged().map { Weather.parse(it) }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connection: StateFlow<FrameSession.Connection?> by lazy {
        _session.flatMapLatest { it?.connection ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    fun start() {
        scope.launch {
            g.sessionMarker.onStart()
            g.network.start()
            launch(Dispatchers.IO) { g.root.isAvailable() }
            if (g.sessionMarker.needsIntegrityCheck) withContext(Dispatchers.IO) { integrityCheck() }
            applyTimezone()
            if (update.onAppStart()) {
                // This version crash-loops right after an update: the previous one is being reinstalled.
                _screen.value = AppScreen.Restarting
                return@launch
            }
            display.start()
            when {
                g.crashGuard.inCrashLoop -> _screen.value = AppScreen.Recovery
                g.credentials.load() != null && g.prefs.claimed -> {
                    startSession()
                    _screen.value = AppScreen.Slideshow
                }
                else -> {
                    if (g.credentials.load() != null) startSession()
                    setup.resume()
                    _screen.value = AppScreen.Setup
                }
            }
            scope.launch { g.settings.settings.collect { applyTimezone() } }
            scope.launch {
                g.settings.raw.map { serverLanguageOf(it) }.distinctUntilChanged().collect { onServerLanguage(it) }
            }
            reportStateChanges()
            update.start()
        }
    }

    /** The activity recreates itself on each emission (a new language must reload every string). */
    val recreateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Switches the frame's language right away (setup wizard, frame settings) and, once the frame belongs to a
     * family, stores it in the frame settings (`locale.language`) so server-made texts (the frame's default name,
     * error messages) and the web app's frame settings match.
     */
    fun setLanguage(code: String) {
        if (!Locales.isSupported(code)) return
        val changed = Locales.stored(g.app) != code
        g.prefs.language = code
        _session.value?.outbox?.let { outbox ->
            g.prefs.serverLanguage = code
            scope.launch { outbox.settingsPatch(languagePatch(code)) }
        }
        if (changed) recreateRequests.tryEmit(Unit)
    }

    private fun languagePatch(code: String) = buildJsonObject { put("locale", buildJsonObject { put("language", code) }) }

    private fun serverLanguageOf(doc: JsonObject): String? =
        ((doc["locale"] as? JsonObject)?.get("language") as? JsonPrimitive)?.contentOrNull

    /**
     * Follows a language change made in the web app. The first value seen after the frame is claimed comes from
     * the server's defaults, so there the frame's own choice wins and is sent up instead.
     */
    private fun onServerLanguage(tag: String?) {
        val server = Locales.match(tag) ?: return
        val local = Locales.effective(g.app)
        val last = g.prefs.serverLanguage
        g.prefs.serverLanguage = server
        if (server == local) return
        if (last == null) {
            _session.value?.outbox?.let { outbox ->
                g.prefs.serverLanguage = local
                scope.launch { outbox.settingsPatch(languagePatch(local)) }
            }
        } else if (server != last) {
            g.prefs.language = server
            recreateRequests.tryEmit(Unit)
        }
    }

    /** Sends the `state` websocket message whenever screen, shown item, brightness or volume change. */
    private fun reportStateChanges() {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                display.mode, slideshow.current, display.brightness, g.settings.settings, _session,
            ) { _, cur, b, s, session -> Triple(cur?.id, b to s.video.volume, session) }
                .collect { (id, bv, session) -> session?.reportState(display.screenStateName(), id, bv.first, bv.second) }
        }
    }

    private fun applyTimezone() {
        val tz = runCatching { g.settings.explicitTimezone()?.let { ZoneId.of(it) } }.getOrNull()
        g.clock.zone = tz ?: ZoneId.systemDefault()
    }

    /** After a power cut: drop cache entries whose files are missing or truncated, check the database. */
    private suspend fun integrityCheck() {
        Log.i(TAG, "unclean shutdown detected: checking media cache")
        if (!g.db.quickCheckOk()) {
            Log.w(TAG, "database failed quick_check: starting over from the server")
            g.db.clearAllTables()
            return
        }
        for (p in g.db.placements().all()) {
            val f = p.localFile?.let { g.media.file(it) } ?: continue
            if (!f.exists() || (p.mediaBytes > 0 && f.length() != p.mediaBytes)) {
                f.delete()
                g.db.placements().clearLocalFile(p.id)
            }
        }
    }

    fun startSession() {
        if (_session.value != null) return
        val s = FrameSession(g.http, g.api, g.db, g.media, g.clock, g.network, scope, events)
        _session.value = s
        s.start()
        scope.launch(Dispatchers.IO) { runCatching { s.sync.garbageCollect() } }
    }

    fun stopSession() {
        _session.value?.stop()
        _session.value = null
    }

    private val events = object : FrameSession.Events {
        override fun onClaimed(ownerName: String?) {
            scope.launch { setup.onClaimed(ownerName) }
        }

        override fun onRevoked() {
            scope.launch { revokeAndReset() }
        }

        override suspend fun onCommand(type: String, args: JsonObject): String? = withContext(Dispatchers.Main) {
            handleCommand(type, args)
        }

        override suspend fun heartbeat(): Heartbeat = buildHeartbeat()

        override fun onPulled(result: app.kinframe.frame.sync.SyncEngine.PullResult) {
            if (result.newItems > 0) scope.launch { display.onNewPhotos() }
        }
    }

    /** Wipe everything tied to the credential and go back to the setup wizard (README §3.2). */
    suspend fun revokeAndReset() {
        Log.w(TAG, "device credential revoked: wiping and returning to setup")
        stopSession()
        slideshow.reset()
        withContext(Dispatchers.IO) {
            g.credentials.clear()
            g.api.forgetToken()
            g.db.clearAllTables()
            g.media.deleteAll()
        }
        g.prefs.wipeDevice()
        setup.restart()
        _screen.value = AppScreen.Setup
    }

    fun finishSetup() {
        g.prefs.claimed = true
        startSession()
        _screen.value = AppScreen.Slideshow
    }

    /** Remote commands (README §5.6). Returns null on success or an error code. */
    private suspend fun handleCommand(type: String, args: JsonObject): String? {
        Log.i(TAG, "command $type")
        return when (type) {
            "sleep" -> { display.sleepNow(); null }
            "wake" -> { display.wake(remote = true); null }
            "restart" -> { scope.launch { delay(500); power.restart() }; null }
            "next" -> { display.wake(remote = true); slideshow.next(manual = true, fade = true); null }
            "previous" -> { display.wake(remote = true); slideshow.previous(fade = true); null }
            "show_placement" -> {
                val id = (args["placement_id"] as? JsonPrimitive)?.contentOrNull ?: return "validation_failed"
                display.wake(remote = true)
                if (slideshow.show(id)) null else "not_found"
            }
            "sync_now" -> {
                _session.value?.requestPull(); _session.value?.sendHeartbeatNow()
                update.requestCheck(UpdateManager.Trigger.Remote)
                null
            }
            "identify" -> {
                identify.value = syncState.value?.frameName ?: g.prefs.ownerName ?: "kinframe"
                display.wake(remote = true)
                scope.launch { delay(8_000); identify.value = null }
                null
            }
            else -> "unsupported"
        }
    }

    suspend fun buildHeartbeat(): Heartbeat {
        g.network.refresh()
        val n = g.network.status.value
        val placements = runCatching { g.db.placements().countCached() to g.db.placements().countMissing() }.getOrNull()
        val (updateState, updateError) = update.heartbeatState()
        return Heartbeat(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidSdk = Build.VERSION.SDK_INT,
            uptimeS = SystemClock.elapsedRealtime() / 1000,
            wifiSsid = n.ssid,
            wifiRssiDbm = n.rssi,
            wifiLinkMbps = n.linkMbps,
            storageFreeBytes = g.media.freeBytes(),
            storageTotalBytes = g.media.totalBytes(),
            cacheBytes = g.media.usedBytes(),
            placementsCached = placements?.first,
            placementsMissing = placements?.second,
            screenState = display.screenStateName(),
            lastShutdownClean = g.sessionMarker.lastShutdownClean,
            crashCount24h = g.crashGuard.crashCount24h(),
            localTime = Instant.ofEpochMilli(g.clock.nowMs()).atZone(g.clock.zone).toOffsetDateTime().toString(),
            timezone = g.clock.zone.id,
            updateState = updateState,
            lastUpdateError = updateError,
        )
    }

    fun setScreen(s: AppScreen) { _screen.value = s }

    /** Recovery screen actions. */
    fun recoverTryAgain() {
        g.crashGuard.clearLoop()
        scope.launch {
            if (g.credentials.load() != null && g.prefs.claimed) {
                startSession(); _screen.value = AppScreen.Slideshow
            } else {
                setup.resume(); _screen.value = AppScreen.Setup
            }
        }
    }

    fun recoverClearCache() {
        scope.launch {
            withContext(Dispatchers.IO) {
                g.db.placements().clearAllLocalFiles()
                g.media.deleteAll()
            }
            g.crashGuard.clearSkipped()
            recoverTryAgain()
        }
    }

    /** Leaves the kiosk to the Android settings. The owner PIN gate (later task) plugs into [OwnerGate]. */
    fun openAndroidSettings(activity: android.app.Activity) {
        OwnerGate.authorize(activity, OwnerGate.Reason.ExitKiosk) {
            activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object { private const val TAG = "KinFrame" }
}

/**
 * Hook for the owner PIN (product spec §5 Security: "the user cannot leave the app without an owner PIN or adb").
 * v0.1 grants immediately; the PIN screen is a later task.
 */
object OwnerGate {
    enum class Reason { ExitKiosk, ChangeServer, FactoryReset }

    fun authorize(activity: android.app.Activity, reason: Reason, onGranted: () -> Unit) {
        Log.i("KinOwnerGate", "authorize $reason (no PIN configured)")
        onGranted()
    }
}
