package app.kinframe.frame.update

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [InAppUpdates] on Google Play's in-app update library. Any failure (no Play Store, Play services missing or too old,
 * the app sideloaded rather than installed from Play, no answer) means "no update": it is logged and nothing happens.
 */
class PlayInAppUpdates(context: Context, private val activity: () -> Activity?) : InAppUpdates {
    private val app = context.applicationContext
    private val manager: AppUpdateManager? by lazy {
        runCatching { AppUpdateManagerFactory.create(app) }.onFailure { Log.i(TAG, "in-app updates unavailable: $it") }.getOrNull()
    }
    private var listener: InstallStateUpdatedListener? = null

    override suspend fun info(): InAppUpdates.Info? = withContext(Dispatchers.Main) {
        val m = manager ?: return@withContext null
        val info = call { m.appUpdateInfo } ?: return@withContext null
        InAppUpdates.Info(
            available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
            versionCode = info.availableVersionCode(),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            immediateInProgress = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
            downloaded = info.installStatus() == InstallStatus.DOWNLOADED,
        )
    }

    override suspend fun start(type: InAppUpdates.Type): InAppUpdates.Result = withContext(Dispatchers.Main) {
        val m = manager ?: return@withContext InAppUpdates.Result.Failed
        val a = activity()?.takeUnless { it.isFinishing } ?: return@withContext InAppUpdates.Result.Failed
        // An AppUpdateInfo starts one flow only: ask again right before.
        val info = call { m.appUpdateInfo } ?: return@withContext InAppUpdates.Result.Failed
        val playType = if (type == InAppUpdates.Type.Immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        // Play's screen waits for the person; no timeout here.
        val code = call(timeoutMs = null) { m.startUpdateFlow(info, a, AppUpdateOptions.defaultOptions(playType)) }
        when (code) {
            Activity.RESULT_OK -> InAppUpdates.Result.Accepted
            Activity.RESULT_CANCELED -> InAppUpdates.Result.Declined
            ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> InAppUpdates.Result.Failed
            else -> InAppUpdates.Result.Failed
        }
    }

    override suspend fun complete(): Boolean = withContext(Dispatchers.Main) {
        val m = manager ?: return@withContext false
        // On success Play restarts the app, so this rarely returns.
        runCatching { m.completeUpdate() }.isSuccess
    }

    override fun listen(onProgress: (InAppUpdates.Progress) -> Unit) {
        val m = manager ?: return
        listener?.let { runCatching { m.unregisterListener(it) } }
        val l = InstallStateUpdatedListener { s ->
            val state = when (s.installStatus()) {
                InstallStatus.PENDING -> InAppUpdates.Progress.State.Pending
                InstallStatus.DOWNLOADING -> InAppUpdates.Progress.State.Downloading
                InstallStatus.DOWNLOADED -> InAppUpdates.Progress.State.Downloaded
                InstallStatus.INSTALLING -> InAppUpdates.Progress.State.Installing
                InstallStatus.INSTALLED -> InAppUpdates.Progress.State.Installed
                InstallStatus.CANCELED -> InAppUpdates.Progress.State.Canceled
                else -> InAppUpdates.Progress.State.Failed
            }
            onProgress(InAppUpdates.Progress(state, s.bytesDownloaded(), s.totalBytesToDownload(), s.installErrorCode()))
        }
        listener = l
        runCatching { m.registerListener(l) }.onFailure { Log.i(TAG, "install listener: $it") }
    }

    /** Awaits a Play task; null on any failure or after [timeoutMs]. */
    private suspend fun <T> call(timeoutMs: Long? = 20_000, task: () -> Task<T>): T? = try {
        val t = task()
        if (timeoutMs == null) t.await() else withTimeoutOrNull(timeoutMs) { t.await() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "in-app updates: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.resumeWithException(IllegalStateException("task canceled")) }
    }

    companion object {
        private const val TAG = "KinPlayUpdate"
    }
}
