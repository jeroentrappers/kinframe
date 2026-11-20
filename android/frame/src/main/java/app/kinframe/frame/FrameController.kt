package app.kinframe.frame

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.Heartbeat
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PinHashDto
import app.kinframe.frame.data.SyncStateEntity
import app.kinframe.frame.join.JoinRequests
import app.kinframe.frame.kiosk.OwnerGate
import app.kinframe.frame.kiosk.OwnerPin
import app.kinframe.frame.kiosk.PinAttempts
import app.kinframe.frame.storage.StorageManager
import app.kinframe.frame.system.CaptivePortal
import app.kinframe.frame.ui.FrameStatus
import app.kinframe.frame.ui.FrameStatuses
import app.kinframe.frame.schedule.DisplayController
import app.kinframe.frame.setup.SetupController
import app.kinframe.frame.slideshow.SlideshowController
import app.kinframe.frame.remotehelp.RemoteHelp
import app.kinframe.frame.remotehelp.RemoteHelpManager
import app.kinframe.frame.sync.FrameSession
import app.kinframe.frame.system.PowerActions
import app.kinframe.frame.update.AppUpdates
import app.kinframe.frame.update.UpdateManager
import app.kinframe.frame.update.UpdateTrigger
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** Whether kinframe is the home app, and the chooser to make it so (a tablet used as a frame). */
    val homeApp = app.kinframe.frame.setup.HomeApp(g.app)
    val display = DisplayController(this)
    val slideshow by lazy { SlideshowController(this) }
    val power = PowerActions(this)
    /** Self-update (github) or Google Play in-app updates (play): the flavor's [UpdateManager]. */
    val update: AppUpdates = UpdateManager(this)
    /** Remote help for the operator (github, rooted frames) or nothing (play): the flavor's [RemoteHelpManager]. */
    val remoteHelp: RemoteHelp = RemoteHelpManager(this)
    val gate = OwnerGate(
        pinHash = { g.settings.settings.value.kiosk.ownerPinHash },
        attempts = PinAttempts(g.prefs.pinAttempts) { g.clock.nowMs() },
    )

    /** Frees space by evicting least-recently-shown renditions (product spec 4.6). */
    val storage: StorageManager by lazy {
        StorageManager(g.db, g.media, protectedIds = { withContext(Dispatchers.Main) { slideshow.protectedIds() } }, now = { g.clock.nowMs() })
    }

    /** Captive-portal detection (product spec 4.8) and whether its sign-in page is open. */
    val portal = CaptivePortal(g.http, { CaptivePortal.probeFor(g.prefs.serverUrl ?: BuildConfig.DEFAULT_SERVER) }, g.network)
    val portalOpen = MutableStateFlow(false)

    /** What the slideshow's status card should say, if anything. */
    val frameStatus: StateFlow<FrameStatus?> by lazy {
        kotlinx.coroutines.flow.combine(connection, portal.loginUrl, storage.state, g.network.status, g.time.clockWrong) { conn, login, st, net, clockWrong ->
            FrameStatuses.of(conn, net.connected, login != null, st.level, clockWrong)
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** SD card and USB stick: export, import, automatic backup, restore (product spec 4.10). */
    val removable by lazy { app.kinframe.frame.removable.RemovableStorage(this) }

    /** The photos a converted Frameo frame received, brought into kinframe (docs/spec/frameo-import.md). */
    val frameo by lazy { app.kinframe.frame.frameo.FrameoImport(this) }

    val joins = JoinRequests(this) { Locales.wrap(g.app).getString(R.string.join_someone) }

    /** Calendars, event invitations, countdowns and the timed calendar view (product spec 4.9). */
    val calendar by lazy { app.kinframe.frame.calendar.CalendarController(this) }

    /** Birthdays of the feeds this frame subscribes to: the day's card, countdowns, greetings (docs/spec/birthdays.md). */
    val birthdays by lazy { app.kinframe.frame.birthdays.BirthdayController(this) }

    /** Family social on the frame: "Ask for new photos", "New photos from Ben", comment and prompt cards (family-social.md §8). */
    val social by lazy { app.kinframe.frame.social.SocialController(this) }

    /** Live widgets from devices on the home network (docs/plugins.md, step 1: built-in widgets). */
    val plugins by lazy { app.kinframe.frame.plugins.FramePlugins(this) }

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

    /** Where [start] is; with the slideshow's progress it makes [startup]. */
    private val boot = MutableStateFlow(StartupStep.Starting)
    private val awaitingFirstSync = MutableStateFlow(true)
    private val splashGaveUp = MutableStateFlow(false)

    private val _splashUp = MutableStateFlow(true)
    /** The start-up splash is (still) on screen: true from launch until it has faded out for good. */
    val splashUp: StateFlow<Boolean> = _splashUp

    /** Called by the UI once the start-up splash has faded out; it never comes back in this process. */
    fun onSplashGone() { _splashUp.value = false }

    /** What the start-up splash says, and when it may go ([StartupStep.Ready]). Driven by the real steps. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val startup: StateFlow<StartupStep> by lazy {
        val ss = slideshow
        kotlinx.coroutines.flow.combine(
            listOf(
                _screen, boot, ss.loaded, ss.stage.map { it.incoming != null }.distinctUntilChanged(), ss.empty,
                awaitingFirstSync, g.network.status.map { it.connected }.distinctUntilChanged(), g.time.clockWrong, splashGaveUp,
            ),
        ) { v ->
            Startup.step(
                StartupSignals(
                    screen = v[0] as AppScreen, boot = v[1] as StartupStep, slideshowLoaded = v[2] as Boolean, hasSlide = v[3] as Boolean,
                    empty = v[4] as app.kinframe.frame.slideshow.EmptyState?, awaitingFirstSync = v[5] as Boolean,
                    networkConnected = v[6] as Boolean, clockWrong = v[7] as Boolean, gaveUp = v[8] as Boolean,
                ),
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Startup.step(StartupSignals(_screen.value, boot.value)))
    }

    private val _powerCut = MutableStateFlow<PowerCutNotice?>(null)
    /** The calm "switched off without shutting down" card, once after a power cut (null when there is none). */
    val powerCut: StateFlow<PowerCutNotice?> = _powerCut
    fun dismissPowerCut() { _powerCut.value = null }

    fun start() {
        scope.launch {
            g.sessionMarker.onStart()
            g.network.start()
            g.time.start(scope) { g.network.onAvailable(it) }
            launch(Dispatchers.IO) { g.root.isAvailable() }
            if (g.sessionMarker.needsIntegrityCheck) {
                boot.value = StartupStep.CheckingLibrary
                val dropped = withContext(Dispatchers.IO) { integrityCheck() }
                _powerCut.value = PowerCutNotice.forStart(true, dropped)
            }
            applyTimezone()
            boot.value = StartupStep.CheckingUpdate
            if (update.onAppStart()) {
                // This version crash-loops right after an update: the previous one is being reinstalled.
                _screen.value = AppScreen.Restarting
                return@launch
            }
            display.start()
            portal.start(scope, suspicious = { connection.value == FrameSession.Connection.ServerUnreachable })
            when {
                g.crashGuard.inCrashLoop -> _screen.value = AppScreen.Recovery
                g.credentials.load() != null && g.prefs.claimed -> {
                    startSession()
                    boot.value = StartupStep.LoadingPhotos
                    _screen.value = AppScreen.Slideshow
                    watchSplash()
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
            scope.launch {
                connection.map { it == FrameSession.Connection.Online }.distinctUntilChanged().collect { online ->
                    if (online) { joins.refresh(); calendar.refreshInvitations() }
                }
            }
            calendar.start()
            birthdays.start()
            social.start()
            reportStateChanges()
            app.kinframe.frame.sync.NowShowingReporter(this@FrameController).start()
            update.start()
            remoteHelp.start()
            removable.start()
            frameo.start()
            scope.launch {
                delay(30_000)
                while (true) {
                    runCatching { storage.ensureSpace() }.onFailure { Log.w(TAG, "storage check", it) }
                    delay(15 * 60_000L)
                }
            }
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
            ?: runCatching { g.prefs.timezone?.let { ZoneId.of(it) } }.getOrNull()
        g.clock.zone = tz ?: ZoneId.systemDefault()
    }

    /**
     * Bounds the splash on the slideshow: an empty library waits for this start's first sync at most
     * [Startup.FIRST_SYNC_GRACE_MS], and the splash goes after [Startup.SLIDESHOW_MAX_MS] whatever happens.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun watchSplash() {
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(Startup.FIRST_SYNC_GRACE_MS) {
                _session.flatMapLatest { it?.lastSyncAt ?: flowOf(null) }.first { it != null }
            }
            awaitingFirstSync.value = false
        }
        scope.launch {
            delay(Startup.SLIDESHOW_MAX_MS)
            if (_splashUp.value) Log.w(TAG, "start-up splash still up after ${Startup.SLIDESHOW_MAX_MS} ms (${startup.value}): giving up")
            splashGaveUp.value = true
        }
    }

    /**
     * After a power cut: drop cache entries whose files are missing or truncated, check the database. Returns how
     * many files were dropped, or -1 when the database was started over.
     */
    private suspend fun integrityCheck(): Int {
        Log.i(TAG, "unclean shutdown detected: checking media cache")
        if (!g.db.quickCheckOk()) {
            Log.w(TAG, "database failed quick_check: starting over from the server")
            g.db.clearAllTables()
            return -1
        }
        var dropped = 0
        for (p in g.db.placements().all()) {
            val f = p.localFile?.let { g.media.file(it) } ?: continue
            if (!f.exists() || (p.mediaBytes > 0 && f.length() != p.mediaBytes)) {
                f.delete()
                g.db.placements().clearLocalFile(p.id)
                dropped++
            }
        }
        return dropped
    }

    fun startSession() {
        if (_session.value != null) return
        val s = FrameSession(g.http, g.api, g.db, g.media, g.clock, g.network, scope, events, makeRoom = { storage.ensureSpace(it) })
        storage.onQueueChanged = { s.downloader.kick() }
        s.downloader.onStored = { scope.launch { storage.ensureSpace() } }
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
            // A decision made in the app shows up as a membership change: drop the card here too.
            if (joins.pending.value.isNotEmpty()) joins.refresh()
            // Withdrawn or decided elsewhere: drop the invitation card too.
            if (calendar.invitations.value.isNotEmpty()) calendar.refreshInvitations()
        }

        override fun onJoinRequest(request: JsonObject) {
            scope.launch { joins.onEvent(request) }
        }

        override fun onRemoteHelp(payload: JsonObject?) = remoteHelp.onChange(payload)

        override fun onEventInvitation(invitation: JsonObject) {
            scope.launch { calendar.onInvitationEvent(invitation) }
        }
    }

    /** Wipe everything tied to the credential and go back to the setup wizard (README §3.2). */
    suspend fun revokeAndReset() {
        Log.w(TAG, "device credential revoked: wiping and returning to setup")
        stopSession()
        slideshow.reset()
        joins.clear()
        calendar.clear()
        social.clear()
        frameo.reset()
        runCatching { remoteHelp.onUnpaired() }.onFailure { Log.w(TAG, "remote help clean-up", it) }
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
        // A freshly set-up frame may have a kinframe export on a stick waiting to be restored.
        scope.launch { removable.refreshRestoreOffer() }
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
                calendar.syncNow()
                update.requestCheck(UpdateTrigger.Remote)
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
        val evicted = runCatching { g.db.placements().countEvicted() }.getOrNull()
        val (updateState, updateError) = update.heartbeatState()
        val remoteHelpFeatures = remoteHelp.features()
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
            placementsEvicted = evicted,
            storageState = storage.state.value.level.name.lowercase(),
            screenState = display.screenStateName(),
            lastShutdownClean = g.sessionMarker.lastShutdownClean,
            crashCount24h = g.crashGuard.crashCount24h(),
            localTime = Instant.ofEpochMilli(g.clock.nowMs()).atZone(g.clock.zone).toOffsetDateTime().toString(),
            timezone = g.clock.zone.id,
            updateState = updateState,
            lastUpdateError = updateError,
            features = Distribution.heartbeatFeatures() + remoteHelpFeatures,
            remoteHelp = remoteHelp.report(),
            serial = if (remoteHelpFeatures.isNotEmpty()) remoteHelp.serial() else null,
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

    /** Leaves the kiosk to the Android settings (owner PIN when set). */
    fun openAndroidSettings(activity: android.app.Activity) {
        gate.authorize(OwnerGate.Reason.ExitKiosk) {
            activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            app.kinframe.frame.system.ReturnButton.show(activity)
        }
    }

    /** Opens frame settings (owner PIN when set). */
    fun openSettings() = gate.authorize(OwnerGate.Reason.OpenSettings) { settingsOpen.value = true }

    fun closeSettings() {
        settingsOpen.value = false
        gate.lock()
    }

    /**
     * Sets ([pin]) or removes (null) the owner PIN at the frame. Only the salted hash leaves this function: it goes
     * into the settings document through the outbox, so it applies at once, offline too. Returns false without a session.
     */
    suspend fun setOwnerPin(pin: String?): Boolean {
        val outbox = session.value?.outbox ?: return false
        val hash = pin?.let { withContext(Dispatchers.Default) { OwnerPin.hash(it) } }
        val value = hash?.let { KinJson.encodeToJsonElement(PinHashDto.serializer(), it) } ?: JsonNull
        outbox.settingsPatch(buildJsonObject { put("kiosk", buildJsonObject { put("owner_pin_hash", value) }) })
        g.prefs.pinAttempts.failures = 0
        g.prefs.pinAttempts.lockedUntil = 0
        return true
    }

    companion object { private const val TAG = "KinFrame" }
}
