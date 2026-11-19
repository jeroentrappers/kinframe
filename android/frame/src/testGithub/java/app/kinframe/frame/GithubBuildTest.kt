package app.kinframe.frame

import app.kinframe.frame.system.RootShell
import app.kinframe.frame.update.UpdateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GitHub build keeps today's behaviour: root where the frame has it, self-update, the same heartbeat features. */
class GithubBuildTest {
    private class CountingRoot : RootShell() {
        var spawned = 0
        override fun startSu(): Process { spawned++; throw java.io.IOException("no su on the test host") }
    }

    @Test fun triesRoot() = runBlocking {
        val root = CountingRoot()
        assertFalse(root.isAvailable()) // no su here, but it was tried
        assertEquals(1, root.spawned)
        assertEquals(-1, root.run("id").exitCode)
        assertEquals(2, root.spawned)
    }

    @Test fun distributionIsGithub() {
        assertFalse(Distribution.isPlay)
        assertTrue(Distribution.rootAllowed)
        assertEquals("github", BuildConfig.DISTRIBUTION)
        assertEquals(app.kinframe.core.api.FrameFeatures.SUPPORTED, Distribution.heartbeatFeatures())
    }

    @Test fun selfUpdatesFromTheServer() {
        // The self-update and its installer are part of this build (docs/api/README.md §5.7).
        assertTrue(runCatching { Class.forName("app.kinframe.frame.update.Installer") }.isSuccess)
        assertTrue(app.kinframe.frame.update.AppUpdates::class.java.isAssignableFrom(UpdateManager::class.java))
    }
}
