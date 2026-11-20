package app.kinframe.frame

import app.kinframe.frame.system.RootShell
import app.kinframe.frame.system.TimeSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** What sets the Google Play build apart (docs/play-frame.md): no root, no self-update, "play" in the heartbeat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayBuildTest {
    /** Counts attempts to start `su` instead of starting it. */
    private class CountingRoot : RootShell() {
        var spawned = 0
        override fun startSu(): Process { spawned++; throw IllegalStateException("su must not run in the Play build") }
    }

    @Test fun neverRunsSu() = runBlocking {
        val root = CountingRoot()
        assertFalse(root.isAvailable())
        assertFalse(root.known)
        val r = root.run("id")
        assertEquals(-1, r.exitCode)
        assertEquals(0, root.spawned)
    }

    @Test fun clockIsReportOnly() = runBlocking {
        // The clock is off by a day and NTP says so: without root, TimeSync only reports it (Android's network time fixes it).
        val root = CountingRoot()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("time-play", android.content.Context.MODE_PRIVATE)
        val ntp = object : TimeSync.Ntp() { override suspend fun offset(host: String) = 86_400_000L }
        val sync = TimeSync(root, prefs, ntp, listOf("a", "b"), wallMs = { 1_900_000_000_000L })
        assertEquals(TimeSync.Outcome.NoRoot, sync.sync())
        assertEquals(0, root.spawned)
    }

    @Test fun distributionIsPlay() {
        assertTrue(Distribution.isPlay)
        assertFalse(Distribution.rootAllowed)
        assertEquals("play", BuildConfig.DISTRIBUTION)
        assertTrue("play" in Distribution.heartbeatFeatures())
    }

    @Test fun noRemoteHelpInThisBuild() {
        // Remote help (docs/spec/remote-help.md) needs root: its engine, script and tarball reader are GitHub-only.
        for (name in listOf("RemoteHelpEngine", "RemoteHelpScript", "TailscaleArchive", "RemoteHelpPayload")) {
            assertFalse(name, runCatching { Class.forName("app.kinframe.frame.remotehelp.$name") }.isSuccess)
        }
        assertFalse(app.kinframe.core.api.FrameFeatures.REMOTE_HELP in Distribution.heartbeatFeatures())
    }

    @Test fun noSelfUpdateCodeInThisBuild() {
        // The installer, the APK downloader and the root install script exist only in the GitHub build.
        for (name in listOf("app.kinframe.frame.update.Installer", "app.kinframe.frame.update.ApkDownloader")) {
            assertFalse(name, runCatching { Class.forName(name) }.isSuccess)
        }
        assertTrue(File("src/play/AndroidManifest.xml").readText().contains("REQUEST_INSTALL_PACKAGES\" tools:node=\"remove\""))
    }
}
