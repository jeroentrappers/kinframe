package app.kinframe.frame

import app.kinframe.frame.slideshow.EmptyState
import app.kinframe.frame.system.SessionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StartupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun slideshow(
        loaded: Boolean = true, hasSlide: Boolean = false, empty: EmptyState? = null, awaitingFirstSync: Boolean = true,
        net: Boolean = true, clockWrong: Boolean = false, gaveUp: Boolean = false,
    ) = Startup.step(StartupSignals(AppScreen.Slideshow, StartupStep.LoadingPhotos, loaded, hasSlide, empty, awaitingFirstSync, net, clockWrong, gaveUp))

    @Test fun loadingFollowsTheStartSteps() {
        for (s in listOf(StartupStep.Starting, StartupStep.CheckingLibrary, StartupStep.CheckingUpdate)) {
            assertEquals(s, Startup.step(StartupSignals(AppScreen.Loading, s)))
        }
    }

    @Test fun otherScreensEndTheSplash() {
        for (screen in listOf(AppScreen.Setup, AppScreen.Recovery, AppScreen.Restarting)) {
            assertEquals(StartupStep.Ready, Startup.step(StartupSignals(screen, StartupStep.CheckingUpdate)))
        }
    }

    @Test fun slideshowWaitsForTheFirstPhoto() {
        assertEquals(StartupStep.LoadingPhotos, slideshow(loaded = false))
        assertEquals(StartupStep.PreparingPhoto, slideshow())
        // Offline or with a wrong clock it still shows the photos it has.
        assertEquals(StartupStep.PreparingPhoto, slideshow(net = false, clockWrong = true))
        assertEquals(StartupStep.Ready, slideshow(hasSlide = true))
        assertEquals(StartupStep.Ready, slideshow(hasSlide = true, net = false))
        assertEquals(StartupStep.Ready, slideshow(gaveUp = true))
    }

    @Test fun emptyStatesShowRightAwayExceptAnEmptyLibraryBeforeTheFirstSync() {
        assertEquals(StartupStep.Ready, slideshow(empty = EmptyState.AllHidden))
        assertEquals(StartupStep.Ready, slideshow(empty = EmptyState.Downloading(12)))
        assertEquals(StartupStep.WaitingForWifi, slideshow(empty = EmptyState.NoPhotos, net = false, clockWrong = true))
        assertEquals(StartupStep.SettingClock, slideshow(empty = EmptyState.NoPhotos, clockWrong = true))
        assertEquals(StartupStep.ConnectingToServer, slideshow(empty = EmptyState.NoPhotos))
        assertEquals(StartupStep.Ready, slideshow(empty = EmptyState.NoPhotos, awaitingFirstSync = false))
        assertEquals(StartupStep.Ready, slideshow(empty = EmptyState.NoPhotos, gaveUp = true))
    }

    /** Each SessionMarker in turn is one process start; [newBoot] rewrites the marker's boot id as a reboot would. */
    @Test fun powerCutNoticeOnlyOnceAfterAPowerCut() {
        val dir = tmp.newFolder()
        val f = File(dir, "session.json")
        fun newBoot() = f.writeText(f.readText().replace(Regex("\"boot\":\"[^\"]*\""), "\"boot\":\"boot-${System.nanoTime()}\""))
        fun start() = SessionMarker(dir).apply { onStart() }
        fun notice(m: SessionMarker, dropped: Int = 0) = PowerCutNotice.forStart(m.needsIntegrityCheck, dropped)

        // The very first start ever: nothing.
        assertNull(notice(start()))
        // App restarts within the same boot (update, crash, crash-loop recovery): nothing.
        assertNull(notice(start()))
        // Power cut, then power on: the notice, once.
        newBoot()
        assertEquals(PowerCutNotice(redownload = false), notice(start()))
        // The app restarts in that same boot (it crashed, or updated): not again.
        assertNull(notice(start()))
        // Power off from the menu (or Android's own shutdown), then on: nothing.
        start().markClean()
        newBoot()
        assertNull(notice(start()))
        // kinframe's own restart marks the shutdown clean too.
        start().markClean()
        newBoot()
        assertNull(notice(start()))
        // A power cut where the check dropped files, or started the library over: photos come again.
        newBoot()
        assertEquals(PowerCutNotice(redownload = true), notice(start(), dropped = 3))
        newBoot()
        assertEquals(PowerCutNotice(redownload = true), notice(start(), dropped = -1))
    }
}
