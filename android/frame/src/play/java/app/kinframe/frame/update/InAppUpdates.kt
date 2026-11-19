package app.kinframe.frame.update

/**
 * Google Play's in-app updates as the frame uses them, free of Play classes so [PlayUpdater] can be tested with a fake.
 * The real one is [PlayInAppUpdates]. Every call degrades to "nothing to do" on a tablet without the Play Store or
 * Play services, or when the app was not installed from Play.
 */
interface InAppUpdates {
    enum class Type { Flexible, Immediate }

    /** What Play knows about an update for this app. */
    data class Info(
        /** A newer version is on Play for this device. */
        val available: Boolean,
        val versionCode: Int = 0,
        val flexibleAllowed: Boolean = false,
        val immediateAllowed: Boolean = false,
        /** An immediate update started earlier is still running: it must be resumed. */
        val immediateInProgress: Boolean = false,
        /** A flexible update has been downloaded and waits for [complete]. */
        val downloaded: Boolean = false,
    )

    enum class Result {
        /** The person accepted (flexible: the download runs; immediate: Play installs and restarts the app). */
        Accepted,
        /** The person declined or closed Play's screen. */
        Declined,
        /** Play could not start the flow (no activity, no Play Store, not installed from Play, …). */
        Failed,
    }

    /** Progress of an accepted flexible update. */
    data class Progress(val state: State, val bytes: Long = 0, val total: Long = 0, val errorCode: Int = 0) {
        enum class State { Pending, Downloading, Downloaded, Installing, Installed, Failed, Canceled }
    }

    /** Null when in-app updates are not available on this device (no Play Store, not from Play, no answer). */
    suspend fun info(): Info?

    /** Shows Play's update flow for [type]. */
    suspend fun start(type: Type): Result

    /** Installs a downloaded flexible update; Play restarts the app. False when that could not start. */
    suspend fun complete(): Boolean

    /** Called with the progress of flexible updates, on the main thread. */
    fun listen(onProgress: (Progress) -> Unit)
}
