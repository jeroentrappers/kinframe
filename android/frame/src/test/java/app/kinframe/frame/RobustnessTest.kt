package app.kinframe.frame

import app.kinframe.frame.system.CrashGuard
import app.kinframe.frame.system.SessionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RobustnessTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun crashLoopSkipsTheItemThenShowsRecovery() {
        var now = 1_000_000L
        val dir = tmp.newFolder()
        fun guard() = CrashGuard(dir) { now } // a fresh instance per "process start"
        guard().apply { currentItem = "bad"; recordCrash() }
        assertTrue(guard().skippedItems().isEmpty())
        now += 30_000
        guard().apply { currentItem = "bad"; recordCrash() }
        assertEquals(setOf("bad"), guard().skippedItems())
        assertFalse(guard().inCrashLoop)
        repeat(2) { now += 10_000; guard().apply { currentItem = "other"; recordCrash() } }
        assertTrue(guard().inCrashLoop)
        guard().clearLoop()
        assertFalse(guard().inCrashLoop)
        assertEquals(4, guard().crashCount24h())
        // Crashes older than 5 minutes don't count toward a loop.
        now += 10 * 60_000
        guard().apply { recordCrash() }
        assertFalse(guard().inCrashLoop)
    }

    @Test
    fun cleanAndUncleanShutdownTracking() {
        val dir = tmp.newFolder()
        val first = SessionMarker(dir).apply { onStart() }
        assertTrue("first boot counts as clean", first.lastShutdownClean)
        // Same boot, app restarted (e.g. update): verdict unchanged, no integrity check.
        val again = SessionMarker(dir).apply { onStart() }
        assertTrue(again.lastShutdownClean)
        assertFalse(again.needsIntegrityCheck)
        // Simulate a power cut: the marker says "running" with an older boot id.
        val f = File(dir, "session.json")
        f.writeText(f.readText().replace(Regex("\"boot\":\"[^\"]*\""), "\"boot\":\"previous-boot\""))
        val afterCut = SessionMarker(dir).apply { onStart() }
        assertFalse(afterCut.lastShutdownClean)
        assertTrue(afterCut.needsIntegrityCheck)
        // Clean power-off, then reboot.
        afterCut.markClean()
        f.writeText(f.readText().replace(Regex("\"boot\":\"[^\"]*\""), "\"boot\":\"older-boot\""))
        val afterClean = SessionMarker(dir).apply { onStart() }
        assertTrue(afterClean.lastShutdownClean)
        assertFalse(afterClean.needsIntegrityCheck)
    }
}
