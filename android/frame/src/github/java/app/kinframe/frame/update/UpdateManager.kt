package app.kinframe.frame.update

import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.FrameUpdate
import app.kinframe.frame.AppScreen
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Frame app self-update (docs/api/README.md §5.7, product spec 4.10).
 *
 * - Checks every 24 h plus up to 90 min jitter, on `sync_now`, from "Check for update" in settings, and after a
 *   `426 app_update_required` from any device call.
 * - Automatic checks and installs wait until nobody touched the frame for 15 minutes and the screen was not switched
 *   off by hand. A check the person started on the frame installs right away.
 * - Downloads resumably, verifies sha256, package name, signing certificate and version code, then installs with
 *   root (`pm install -r`) or hands the APK to the system installer.
 * - Keeps the replaced APK; if the crash-loop guard trips within 10 minutes of the new version's first start, it
 *   reinstalls the old one (`pm install -r -d`) and never offers that version code again.
 *
 * State lives in [UpdateStore] so it survives the process being killed by the install.
 */
class UpdateManager(private val c: FrameController) : AppUpdates {
    private val g = c.g
    private val dir = File(g.app.filesDir, "updates")
    val store = UpdateStore(File(g.stateDir, "update.json"))
    private val installer = Installer(g.app, g.root, dir)
    private val downloader = ApkDownloader(g.api, dir)
    private val busy = Mutex()
    private val triggers = Channel<UpdateTrigger>(Channel.UNLIMITED)
    @Volatile private var installNow = false
    @Volatile private var lastRequiredCheck = 0L

    private val _status = MutableStateFlow(UpdateStatus())
    override val status: StateFlow<UpdateStatus> = _status

    private val _notice = MutableStateFlow<UpdateAnnouncement?>(null)
    override val notice: StateFlow<UpdateAnnouncement?> = _notice

    override val fromStore = false

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ start-up

    /**
     * Runs once per process before the first screen is chosen: settles an install the previous process started,
     * and rolls back when this freshly installed version is crash-looping. Returns true while a rollback runs.
     */
    override suspend fun onAppStart(): Boolean = withContext(Dispatchers.IO) {
        runCatching { dir.mkdirs(); installer.startedMarker(BuildConfig.VERSION_CODE).createNewFile() }
        store.record.pending?.let { settle(it) }
        val r = store.record
        if (UpdatePolicy.shouldRollBack(r, BuildConfig.VERSION_CODE, now(), g.crashGuard.inCrashLoop) && g.root.isAvailable()) {
            val prev = File(r.previousApk!!)
            if (prev.exists()) {
                Log.w(TAG, "version ${BuildConfig.VERSION_CODE} crash-loops after an update: rolling back to ${r.previousCode}")
                store.update {
                    it.copy(
                        state = "installing", lastError = "crash_loop ${BuildConfig.VERSION_CODE}",
                        skipped = (it.skipped + BuildConfig.VERSION_CODE).distinct().takeLast(20),
                        pending = PendingInstall(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, r.previousCode, "", now(), rollback = true),
                    )
                }
                installer.scheduleRelaunch(60_000)
                installer.runRootScript("rollback", prev, null, null)
                return@withContext true
            }
        }
        // The rollback window: keep the previous APK until it has passed.
        if (r.previousApk != null && r.pending == null) {
            val left = r.installedAt + UpdatePolicy.ROLLBACK_WINDOW_MS - now()
            if (r.installedAt == 0L || left !in 0..UpdatePolicy.ROLLBACK_WINDOW_MS) dropPrevious()
            else c.scope.launch { delay(left + 1_000); if (!g.crashGuard.inCrashLoop) dropPrevious() }
        }
        false
    }

    private fun settle(p: PendingInstall) {
        val res = UpdatePolicy.resolvePending(p, BuildConfig.VERSION_CODE, now(), installer.readLog())
        if (res != UpdatePolicy.Resolution.StillRunning) Log.i(TAG, "pending install of ${p.targetCode} (rollback=${p.rollback}): $res")
        when (res) {
            is UpdatePolicy.Resolution.StillRunning -> Unit
            is UpdatePolicy.Resolution.Updated -> {
                store.update {
                    it.copy(
                        state = "updated", lastError = null, pending = null,
                        installedCode = BuildConfig.VERSION_CODE, installedAt = now(), announce = res.versionName,
                    )
                }
                installer.cancelRelaunch()
            }
            is UpdatePolicy.Resolution.Failed -> {
                store.update { it.copy(state = "failed", lastError = res.error, pending = null, nextCheckAt = now() + UpdatePolicy.RETRY_AFTER_FAILURE_MS) }
                installer.cancelRelaunch()
                if (!p.rollback) dropPrevious()
            }
            is UpdatePolicy.Resolution.RolledBack -> {
                store.update {
                    it.copy(
                        state = "rolled_back", lastError = res.error, pending = null,
                        skipped = (it.skipped + res.badCode).distinct().takeLast(20),
                        installedCode = 0, installedAt = 0,
                    )
                }
                dropPrevious()
                g.crashGuard.clearLoop()
                _notice.value = UpdateAnnouncement.RolledBack
            }
        }
        if (store.record.pending == null) {
            dir.listFiles()?.filter { it.name.startsWith("kinframe-frame-") }?.forEach { it.delete() }
        }
    }

    private fun dropPrevious() {
        store.record.previousApk?.let { File(it).delete() }
        store.update { it.copy(previousApk = null, previousCode = 0) }
    }

    /** Starts the scheduler. Call once, after [onAppStart]. */
    override fun start() {
        g.api.onUpdateRequired = ::onUpdateRequired
        store.update { it.copy(nextCheckAt = UpdatePolicy.firstCheckAt(now(), it.nextCheckAt, UpdatePolicy.jitter())) }
        store.record.announce?.let { name ->
            c.scope.launch {
                delay(3_000)
                _notice.value = UpdateAnnouncement.Updated(name)
                store.update { it.copy(announce = null) }
            }
        }
        c.scope.launch(Dispatchers.Default) {
            while (true) {
                val t = withTimeoutOrNull(60_000) { triggers.receive() }
                try {
                    when {
                        t != null -> check(t)
                        now() >= store.record.nextCheckAt && interruptible(userInitiated = false) -> check(UpdateTrigger.Scheduled)
                    }
                    store.record.pending?.let { p -> if (!busy.isLocked) settle(p) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "update loop", e)
                }
            }
        }
    }

    override fun requestCheck(trigger: UpdateTrigger) {
        if (trigger == UpdateTrigger.User) installNow = true
        triggers.trySend(trigger)
    }

    /** Hook for `426 app_update_required` (called on an OkHttp thread). */
    override fun onUpdateRequired() {
        val t = SystemClock.elapsedRealtime()
        if (lastRequiredCheck != 0L && t - lastRequiredCheck < 10 * 60_000L) return
        lastRequiredCheck = t
        requestCheck(UpdateTrigger.Required)
    }

    override fun dismissNotice() { _notice.value = null }

    /** Heartbeat fields `update_state` and `last_update_error`. */
    override fun heartbeatState(): Pair<String, String?> = store.record.let { it.state to it.lastError }

    // ------------------------------------------------------------------ check, download, install

    private fun interruptible(userInitiated: Boolean): Boolean {
        val screen = c.screen.value
        if (screen is AppScreen.ShuttingDown || screen is AppScreen.SafeToUnplug || screen is AppScreen.Restarting) return false
        return UpdatePolicy.block(
            SystemClock.elapsedRealtime(), c.display.lastTouchElapsed, c.display.screenOffByHand, userInitiated,
        ) == UpdatePolicy.Block.None
    }

    private suspend fun check(trigger: UpdateTrigger) {
        if (!busy.tryLock()) return
        val userInitiated = trigger == UpdateTrigger.User
        if (userInitiated) installNow = true
        try {
            if (store.record.pending != null) return
            if (c.session.value == null) {
                if (userInitiated) _status.value = UpdateStatus(UpdatePhase.Failed, error = "offline")
                return
            }
            _status.value = UpdateStatus(UpdatePhase.Checking)
            val channel = g.settings.settings.value.updates.channel.takeIf { it == "stable" || it == "beta" }
            val offer = try {
                g.api.checkUpdate(channel, store.record.skipped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: ${e.message}")
                _status.value = UpdateStatus(UpdatePhase.Failed, error = "server_unreachable")
                store.update { it.copy(nextCheckAt = now() + UpdatePolicy.RETRY_AFTER_FAILURE_MS) }
                return
            }
            store.update { it.copy(lastCheckAt = now(), nextCheckAt = UpdatePolicy.nextCheckAfter(now(), UpdatePolicy.jitter())) }
            if (offer == null) {
                _status.value = UpdateStatus(UpdatePhase.UpToDate)
                return
            }
            UpdatePolicy.rejectOffer(offer, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, store.record.skipped)?.let {
                Log.i(TAG, "ignoring offered ${offer.versionCode}: $it")
                _status.value = UpdateStatus(UpdatePhase.UpToDate)
                return
            }
            Log.i(TAG, "update available: ${offer.versionName} (${offer.versionCode}), trigger $trigger")
            downloadAndInstall(offer)
        } finally {
            installNow = false
            busy.unlock()
        }
    }

    private suspend fun downloadAndInstall(offer: FrameUpdate) {
        store.update { it.copy(state = "downloading", lastError = null) }
        _status.value = UpdateStatus(UpdatePhase.Downloading, offer.versionName, 0f)
        val apk = try {
            downloader.fetch(offer) { done -> _status.value = UpdateStatus(UpdatePhase.Downloading, offer.versionName, done.toFloat() / offer.size) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("download_failed: ${e.message?.take(80)}", offer)
        }
        val error = withContext(Dispatchers.IO) {
            UpdatePolicy.verifyDownload(
                offer, ApkDownloader.sha256(apk), apk.length(), installer.archiveInfo(apk), installer.installedSigners(),
                BuildConfig.VERSION_CODE,
            )
        }
        if (error != null) {
            downloader.discard(offer)
            return fail(error, offer)
        }
        store.update { it.copy(state = "waiting") }
        _status.value = UpdateStatus(UpdatePhase.Waiting, offer.versionName)
        while (!interruptible(userInitiated = installNow)) delay(5_000)
        install(offer, apk)
    }

    private fun fail(error: String, offer: FrameUpdate?) {
        Log.w(TAG, "update ${offer?.versionCode} failed: $error")
        store.update { it.copy(state = "failed", lastError = error, nextCheckAt = now() + UpdatePolicy.RETRY_AFTER_FAILURE_MS) }
        _status.value = UpdateStatus(UpdatePhase.Failed, offer?.versionName, error = error)
        c.session.value?.sendHeartbeatNow()
    }

    private suspend fun install(offer: FrameUpdate, apk: File) {
        val root = g.root.isAvailable()
        val previous = withContext(Dispatchers.IO) { runCatching { installer.keepInstalledApk() }.getOrNull() }
        installer.startedMarker(offer.versionCode).delete()
        store.update {
            it.copy(
                state = if (root) "installing" else "needs_user", lastError = null,
                pending = PendingInstall(offer.versionCode, offer.versionName, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, now(), viaRoot = root),
                previousApk = previous?.path, previousCode = if (previous != null) BuildConfig.VERSION_CODE else 0,
            )
        }
        // Tell the owner before the process goes away.
        withTimeoutOrNull(3_000) { c.session.value?.sendHeartbeatNow()?.join() }
        if (!root) {
            _status.value = UpdateStatus(UpdatePhase.NeedsUser, offer.versionName)
            _notice.value = UpdateAnnouncement.NeedsUser
            if (!installer.launchSystemInstaller(apk)) {
                store.update { it.copy(pending = null) }
                fail("installer_unavailable", offer)
            }
            return
        }
        _status.value = UpdateStatus(UpdatePhase.Installing, offer.versionName)
        withContext(Dispatchers.IO) { runCatching { g.db.checkpoint() } }
        Log.i(TAG, "installing ${offer.versionName} (${offer.versionCode}) with root")
        installer.scheduleRelaunch(60_000)
        installer.runRootScript("install", apk, previous, installer.startedMarker(offer.versionCode))
        // The package manager normally kills this process now. If it doesn't, the script's log says why.
        val deadline = SystemClock.elapsedRealtime() + UpdatePolicy.INSTALL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(2_000)
            val p = store.record.pending ?: return
            settle(p)
            if (store.record.pending == null) {
                _status.value = UpdateStatus(UpdatePhase.Failed, offer.versionName, error = store.record.lastError)
                c.session.value?.sendHeartbeatNow()
                return
            }
        }
    }

    companion object {
        private const val TAG = "KinUpdate"
    }
}
