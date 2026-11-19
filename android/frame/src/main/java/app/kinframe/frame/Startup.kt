package app.kinframe.frame

import app.kinframe.frame.slideshow.EmptyState

/**
 * What the frame is doing while the start-up splash ([app.kinframe.frame.ui.BootSplash]) is up. [Ready] means the
 * splash can go: the first photo (or the slideshow's empty state, or another screen) is ready to show.
 */
enum class StartupStep {
    Starting,
    /** After a power cut: dropping cache entries whose files are missing or cut short, checking the database. */
    CheckingLibrary,
    /** Settling a pending self-update and checking for a crash loop after one (rollback). */
    CheckingUpdate,
    /** The slideshow is reading the library. */
    LoadingPhotos,
    /** The first photo is being decoded and rendered at screen size (seconds on the slowest frames). */
    PreparingPhoto,
    /** The library is empty and the first sync waits for Wi-Fi. */
    WaitingForWifi,
    /** The library is empty and the first sync waits for the clock (TLS fails while it is wrong). */
    SettingClock,
    /** The library is empty and the first sync is under way. */
    ConnectingToServer,
    Ready,
}

/** What the slideshow and the session report while the splash is up. */
data class StartupSignals(
    val screen: AppScreen,
    /** The step [FrameController.start] is at (only used while [screen] is [AppScreen.Loading]). */
    val boot: StartupStep,
    /** The slideshow has read the library at least once. */
    val slideshowLoaded: Boolean = false,
    /** A slide is on the stage. */
    val hasSlide: Boolean = false,
    val empty: EmptyState? = null,
    /** This start's first sync hasn't finished yet (and its grace period hasn't run out). */
    val awaitingFirstSync: Boolean = false,
    val networkConnected: Boolean = true,
    val clockWrong: Boolean = false,
    /** The splash has been up too long: show the slideshow as it is. */
    val gaveUp: Boolean = false,
)

object Startup {
    /**
     * How long an empty library waits for the first sync before the empty state shows (a frame emptied by the
     * integrity check, or a new one, usually gets its photos within seconds once Wi-Fi is up).
     */
    const val FIRST_SYNC_GRACE_MS = 20_000L

    /** The splash never stays longer than this once the slideshow is up, whatever happens. */
    const val SLIDESHOW_MAX_MS = 45_000L

    fun step(s: StartupSignals): StartupStep = when {
        s.screen == AppScreen.Loading -> s.boot
        s.screen != AppScreen.Slideshow -> StartupStep.Ready
        s.gaveUp -> StartupStep.Ready
        !s.slideshowLoaded -> StartupStep.LoadingPhotos
        s.hasSlide -> StartupStep.Ready
        s.empty == null -> StartupStep.PreparingPhoto
        s.empty == EmptyState.NoPhotos && s.awaitingFirstSync -> when {
            !s.networkConnected -> StartupStep.WaitingForWifi
            s.clockWrong -> StartupStep.SettingClock
            else -> StartupStep.ConnectingToServer
        }
        else -> StartupStep.Ready
    }
}

/**
 * The calm card after the frame lost power without shutting down (instead of a full-screen warning). [redownload]:
 * the check after the power cut had to drop photos (or start the library over), so some are downloaded again.
 */
data class PowerCutNotice(val redownload: Boolean) {
    companion object {
        /** How long the card stays without a tap. */
        const val SHOW_MS = 30_000L

        /**
         * The notice for this process start: only on the first start after a power cut ([needsIntegrityCheck] is
         * false after a clean power-off or restart, on the very first start, and for app restarts within the same
         * boot: updates, crashes, recovery), with [dropped] files dropped by the check, or -1 when the database was
         * started over.
         */
        fun forStart(needsIntegrityCheck: Boolean, dropped: Int): PowerCutNotice? =
            if (!needsIntegrityCheck) null else PowerCutNotice(redownload = dropped != 0)
    }
}
