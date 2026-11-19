package app.kinframe.frame.update

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** What started an update check. */
enum class UpdateTrigger {
    /** The 24 h schedule (plus jitter). */
    Scheduled,
    /** Someone on the frame: "Check for update" in settings, or the setup wizard's update step. */
    User,
    /** The owner's `sync_now` command. */
    Remote,
    /** A device call was answered with `426 app_update_required`. */
    Required,
}

enum class UpdatePhase { Idle, Checking, UpToDate, Downloading, Waiting, Installing, NeedsUser, Failed }

data class UpdateStatus(val phase: UpdatePhase = UpdatePhase.Idle, val versionName: String? = null, val progress: Float? = null, val error: String? = null)

/** The calm message at the bottom of the frame ([app.kinframe.frame.ui.UpdateNotice]). */
sealed interface UpdateAnnouncement {
    data class Updated(val versionName: String) : UpdateAnnouncement
    data object RolledBack : UpdateAnnouncement
    data object NeedsUser : UpdateAnnouncement
}

/**
 * How the frame app updates itself. Each distribution (build flavor, docs/play-frame.md) has its own `UpdateManager`:
 *
 * - `github`: self-update from the kinframe server (docs/api/README.md §5.7): root `pm install` or the system
 *   installer, rollback on a crash loop.
 * - `play`: Google Play in-app updates; never downloads or installs an APK itself.
 */
interface AppUpdates {
    val status: StateFlow<UpdateStatus>
    val notice: StateFlow<UpdateAnnouncement?>

    /** Updates come from an app store: settings hide the release channel and offer to open the store listing. */
    val fromStore: Boolean

    /**
     * Runs once per process before the first screen is chosen: settles an install the previous process started.
     * Returns true while a rollback runs (the frame shows "Restarting").
     */
    suspend fun onAppStart(): Boolean

    /** Starts the scheduler. Call once, after [onAppStart]. */
    fun start()

    fun requestCheck(trigger: UpdateTrigger)

    /** Hook for `426 app_update_required` (called on an OkHttp thread). */
    fun onUpdateRequired()

    fun dismissNotice()

    /** Heartbeat fields `update_state` and `last_update_error`. */
    fun heartbeatState(): Pair<String, String?>

    /** The activity that is showing (null when it goes away), for update flows that need one. */
    fun attach(activity: Activity?) {}
}
