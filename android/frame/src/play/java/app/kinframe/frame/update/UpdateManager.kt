package app.kinframe.frame.update

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import app.kinframe.frame.AppScreen
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/**
 * Updates of the Google Play build: Play's in-app updates ([PlayUpdater]). Unlike the GitHub build this never asks the
 * server for releases (`GET /v1/device/update`), never downloads or installs an APK and never uses root; Play does the
 * install (docs/play-frame.md).
 */
class UpdateManager(private val c: FrameController) : AppUpdates {
    private val g = c.g
    @Volatile private var activity: WeakReference<Activity>? = null

    private val play = PlayInAppUpdates(g.app) { activity?.get() }
    private val updater = PlayUpdater(
        play = play,
        store = UpdateStore(File(g.stateDir, "update.json")),
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        interruptible = ::interruptible,
        onStateChanged = { c.session.value?.sendHeartbeatNow() },
    )

    override val status: StateFlow<UpdateStatus> get() = updater.status
    override val notice: StateFlow<UpdateAnnouncement?> get() = updater.notice
    override val fromStore = true

    override suspend fun onAppStart(): Boolean = withContext(Dispatchers.IO) {
        // A frame that ran the GitHub build before may have left downloaded APKs behind.
        runCatching { File(g.app.filesDir, "updates").takeIf { it.exists() }?.deleteRecursively() }
        updater.onAppStart()
        false
    }

    override fun start() {
        updater.bind(g.api)
        play.listen(updater::onProgress)
        updater.prepare()?.let { name -> c.scope.launch { delay(3_000); updater.announce(name) } }
        c.scope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    updater.runOnce(60_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "update loop", e)
                }
            }
        }
    }

    override fun requestCheck(trigger: UpdateTrigger) = updater.requestCheck(trigger)
    override fun onUpdateRequired() = updater.onUpdateRequired()
    override fun dismissNotice() = updater.dismissNotice()
    override fun heartbeatState(): Pair<String, String?> = updater.heartbeatState()

    override fun attach(activity: Activity?) {
        this.activity = activity?.let { WeakReference(it) }
        if (activity != null) updater.onResumed()
    }

    private fun interruptible(userInitiated: Boolean): Boolean {
        val screen = c.screen.value
        if (screen is AppScreen.ShuttingDown || screen is AppScreen.SafeToUnplug || screen is AppScreen.Restarting) return false
        // Play's screens need an activity to show over.
        if (activity?.get() == null) return false
        return UpdatePolicy.block(
            SystemClock.elapsedRealtime(), c.display.lastTouchElapsed, c.display.screenOffByHand, userInitiated,
        ) == UpdatePolicy.Block.None
    }

    companion object {
        private const val TAG = "KinPlayUpdate"
    }
}
