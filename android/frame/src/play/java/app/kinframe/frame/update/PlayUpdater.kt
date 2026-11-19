package app.kinframe.frame.update

import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.DeviceApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Updates of the Google Play build (docs/play-frame.md): Play's in-app updates on the self-update's cadence and idle
 * rules ([UpdatePolicy]), with no APK download or install of its own and no call to `GET /v1/device/update`.
 *
 * - Checks every 24 h plus up to 90 min jitter, on `sync_now`, from "Check for update" (settings, setup wizard), and
 *   after a `426 app_update_required` from any device call (at most every 10 minutes).
 * - A scheduled or remote check that finds an update starts Play's **flexible** flow once nobody touched the frame for
 *   15 minutes and the screen was not switched off by hand; the download runs in the background and is installed
 *   ([InAppUpdates.complete], Play restarts the app) at the next such idle moment.
 * - After a `426`, and when someone on the frame asks, it starts the **immediate** flow at once (flexible when Play
 *   allows only that): the frame can't talk to its server until it is updated.
 * - Without the Play Store or Play services, or when the app wasn't installed from Play, [InAppUpdates.info] is null and
 *   nothing happens.
 *
 * Android-free apart from logging, so it runs in JVM tests with a fake [InAppUpdates].
 */
class PlayUpdater(
    private val play: InAppUpdates,
    val store: UpdateStore,
    private val versionCode: Int,
    private val versionName: String,
    /** Whether the frame may be interrupted now (the person asked on the frame: [UpdatePolicy.block] with userInitiated). */
    private val interruptible: (userInitiated: Boolean) -> Boolean,
    /** Something the owner should see changed (sends a heartbeat). */
    private val onStateChanged: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val jitter: () -> Long = { UpdatePolicy.jitter() },
) {
    private val busy = Mutex()
    private val triggers = Channel<UpdateTrigger>(Channel.UNLIMITED)
    @Volatile private var lastRequired = 0L
    /** Found by a scheduled or remote check; its flexible flow starts when the frame is idle. */
    @Volatile private var offer: InAppUpdates.Info? = null
    /** A flexible update is downloaded and waits to be installed. */
    @Volatile private var downloaded = false

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status

    private val _notice = MutableStateFlow<UpdateAnnouncement?>(null)
    val notice: StateFlow<UpdateAnnouncement?> = _notice

    /** Routes `426 app_update_required` from every device call here. */
    fun bind(api: DeviceApi) { api.onUpdateRequired = ::onUpdateRequired }

    /** Settles an update the previous process started: the version that runs now tells whether it worked. */
    fun onAppStart() {
        val p = store.record.pending ?: return
        when {
            versionCode >= p.targetCode -> store.update {
                it.copy(
                    state = "updated", lastError = null, pending = null,
                    installedCode = versionCode, installedAt = now(), announce = versionName,
                )
            }
            now() - p.startedAt !in 0..PENDING_MAX_MS -> store.update { it.copy(state = "idle", pending = null) }
        }
    }

    /** Schedules the first check (5 min to 24 h after start, as the self-update) and returns a version to announce. */
    fun prepare(): String? {
        store.update { it.copy(nextCheckAt = UpdatePolicy.firstCheckAt(now(), it.nextCheckAt, jitter())) }
        val name = store.record.announce ?: return null
        store.update { it.copy(announce = null) }
        return name
    }

    fun announce(versionName: String) { _notice.value = UpdateAnnouncement.Updated(versionName) }

    fun dismissNotice() { _notice.value = null }

    fun requestCheck(trigger: UpdateTrigger) { triggers.trySend(trigger) }

    fun onUpdateRequired() {
        val t = elapsed()
        if (lastRequired != 0L && t - lastRequired < REQUIRED_THROTTLE_MS) return
        lastRequired = t
        requestCheck(UpdateTrigger.Required)
    }

    /** Back in the app (Play's screens are activities of their own): resume a running update. */
    fun onResumed() {
        if (store.record.pending != null || downloaded) requestCheck(UpdateTrigger.Remote)
    }

    fun heartbeatState(): Pair<String, String?> = store.record.let { it.state to it.lastError }

    /** One turn of the scheduler: a requested check, else the scheduled one when due, else idle work. */
    suspend fun runOnce(waitMs: Long) {
        val t = withTimeoutOrNull(waitMs) { triggers.receive() }
        when {
            t != null -> check(t)
            now() >= store.record.nextCheckAt && interruptible(false) -> check(UpdateTrigger.Scheduled)
            else -> idleWork()
        }
    }

    /** Runs the checks requested so far (tests, and the loop's catch-up). */
    suspend fun processPending() {
        while (true) check(triggers.tryReceive().getOrNull() ?: return)
    }

    private suspend fun idleWork() {
        if (busy.isLocked) return
        if (downloaded && interruptible(false)) {
            complete()
            return
        }
        val o = offer ?: return
        if (o.flexibleAllowed && interruptible(false)) {
            offer = null
            startFlow(InAppUpdates.Type.Flexible, o)
        }
    }

    suspend fun check(trigger: UpdateTrigger) {
        if (!busy.tryLock()) return
        val user = trigger == UpdateTrigger.User
        try {
            _status.value = UpdateStatus(UpdatePhase.Checking)
            val info = play.info()
            store.update { it.copy(lastCheckAt = now(), nextCheckAt = UpdatePolicy.nextCheckAfter(now(), jitter())) }
            if (info == null) {
                _status.value = if (user) UpdateStatus(UpdatePhase.Failed, error = "play_unavailable") else UpdateStatus()
                return
            }
            val name = versionNameOf(info.versionCode)
            when {
                info.downloaded -> {
                    downloaded = true
                    waiting(name)
                    if (user || trigger == UpdateTrigger.Required || interruptible(false)) complete()
                }
                info.immediateInProgress -> startFlow(InAppUpdates.Type.Immediate, info)
                !info.available || info.versionCode <= versionCode -> {
                    offer = null
                    _status.value = UpdateStatus(UpdatePhase.UpToDate)
                    if (store.record.pending == null && store.record.state in setOf("needs_user", "downloading", "waiting")) {
                        store.update { it.copy(state = "idle") }
                    }
                }
                user || trigger == UpdateTrigger.Required -> when {
                    info.immediateAllowed -> startFlow(InAppUpdates.Type.Immediate, info)
                    info.flexibleAllowed -> startFlow(InAppUpdates.Type.Flexible, info)
                    else -> _status.value = UpdateStatus(UpdatePhase.Failed, name, error = "update_not_allowed")
                }
                else -> {
                    // Scheduled or remote: Play asks the person, so only when nobody is busy with the frame.
                    offer = info
                    _status.value = UpdateStatus(UpdatePhase.Waiting, name)
                    if (info.flexibleAllowed && interruptible(false)) {
                        offer = null
                        startFlow(InAppUpdates.Type.Flexible, info)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check", e)
            _status.value = UpdateStatus(UpdatePhase.Failed, error = "play_error")
        } finally {
            busy.unlock()
        }
    }

    private suspend fun startFlow(type: InAppUpdates.Type, info: InAppUpdates.Info) {
        val name = versionNameOf(info.versionCode)
        val immediate = type == InAppUpdates.Type.Immediate
        store.update {
            it.copy(
                state = if (immediate) "installing" else "needs_user", lastError = null,
                pending = PendingInstall(info.versionCode, name ?: "${info.versionCode}", versionCode, versionName, now(), viaRoot = false),
            )
        }
        onStateChanged()
        _status.value = UpdateStatus(if (immediate) UpdatePhase.Installing else UpdatePhase.NeedsUser, name)
        Log.i(TAG, "starting the ${type.name.lowercase()} update to ${info.versionCode}")
        when (play.start(type)) {
            InAppUpdates.Result.Accepted -> if (!immediate) {
                store.update { it.copy(state = "downloading") }
                _status.value = UpdateStatus(UpdatePhase.Downloading, name, 0f)
            }
            InAppUpdates.Result.Declined -> {
                // Asked again at the next scheduled check (or the next 426).
                store.update { it.copy(state = "idle", pending = null) }
                _status.value = UpdateStatus()
            }
            InAppUpdates.Result.Failed -> {
                store.update {
                    it.copy(state = "failed", lastError = "play_flow_failed", pending = null, nextCheckAt = now() + UpdatePolicy.RETRY_AFTER_FAILURE_MS)
                }
                _status.value = UpdateStatus(UpdatePhase.Failed, name, error = "play_flow_failed")
                onStateChanged()
            }
        }
    }

    private fun waiting(name: String?) {
        store.update { it.copy(state = "waiting") }
        _status.value = UpdateStatus(UpdatePhase.Waiting, name)
    }

    private suspend fun complete() {
        store.update { it.copy(state = "installing") }
        _status.value = UpdateStatus(UpdatePhase.Installing, _status.value.versionName ?: store.record.pending?.targetName)
        onStateChanged()
        if (!play.complete()) {
            downloaded = false
            store.update { it.copy(state = "failed", lastError = "play_complete_failed", nextCheckAt = now() + UpdatePolicy.RETRY_AFTER_FAILURE_MS) }
            _status.value = UpdateStatus(UpdatePhase.Failed, error = "play_complete_failed")
        }
    }

    /** Progress of an accepted flexible update ([InAppUpdates.listen]). */
    fun onProgress(p: InAppUpdates.Progress) {
        val name = store.record.pending?.targetName
        when (p.state) {
            InAppUpdates.Progress.State.Pending, InAppUpdates.Progress.State.Downloading -> {
                if (store.record.state != "downloading") store.update { it.copy(state = "downloading") }
                _status.value = UpdateStatus(UpdatePhase.Downloading, name, if (p.total > 0) p.bytes.toFloat() / p.total else 0f)
            }
            InAppUpdates.Progress.State.Downloaded -> {
                downloaded = true
                waiting(name)
                onStateChanged()
                // Installed by the scheduler once the frame is idle (or right away from "Install now").
                requestCheck(UpdateTrigger.Remote)
            }
            InAppUpdates.Progress.State.Installing -> _status.value = UpdateStatus(UpdatePhase.Installing, name)
            InAppUpdates.Progress.State.Installed -> Unit
            InAppUpdates.Progress.State.Failed -> {
                downloaded = false
                store.update { it.copy(state = "failed", lastError = "play_install_failed ${p.errorCode}", pending = null) }
                _status.value = UpdateStatus(UpdatePhase.Failed, name, error = "play_install_failed")
                onStateChanged()
            }
            InAppUpdates.Progress.State.Canceled -> {
                downloaded = false
                store.update { it.copy(state = "idle", pending = null) }
                _status.value = UpdateStatus()
            }
        }
    }

    companion object {
        private const val TAG = "KinPlayUpdate"
        const val REQUIRED_THROTTLE_MS = 10 * 60_000L
        /** An update that neither finished nor failed after this long is forgotten (Play may still finish it). */
        const val PENDING_MAX_MS = 24 * 3600_000L

        /**
         * The version name for a version code of the release scheme (docs/api/README.md §5.7):
         * `major·1 000 000 + minor·10 000 + patch·100 + n`, n = 99 for a release, N for `-beta.N`. Play only reports
         * the code. Null for codes outside the scheme.
         */
        fun versionNameOf(code: Int): String? {
            if (code < 10_000) return null
            val n = code % 100
            val base = "${code / 1_000_000}.${code / 10_000 % 100}.${code / 100 % 100}"
            return when (n) {
                99 -> base
                in 1..98 -> "$base-beta.$n"
                else -> null
            }
        }
    }
}
