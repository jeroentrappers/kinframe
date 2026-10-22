package app.kinframe.frame.system

import app.kinframe.frame.storage.StoragePolicy
import app.kinframe.frame.sync.FrameSession.Connection
import app.kinframe.frame.ui.FrameStatus
import app.kinframe.frame.ui.FrameStatuses
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptivePortalTest {
    private val probe = "http://kinframe.appmire.be/.well-known/kinframe".toHttpUrl()

    @Test fun probeUrlIsPlainHttpOnTheServer() {
        assertEquals(probe, CaptivePortal.probeFor("https://kinframe.appmire.be"))
        assertEquals("http://192.168.1.5:8080/.well-known/kinframe".toHttpUrl(), CaptivePortal.probeFor("http://192.168.1.5:8080/"))
        assertNull(CaptivePortal.probeFor("not a url"))
    }

    @Test fun classifiesAnswers() {
        assertEquals(CaptivePortal.Result.Open, CaptivePortal.classify(probe, 308, "https://kinframe.appmire.be/.well-known/kinframe", null, ""))
        assertEquals(CaptivePortal.Result.Open, CaptivePortal.classify(probe, 200, null, "application/json", """{"name":"kinframe"}"""))
        assertEquals(
            CaptivePortal.Result.Portal("https://login.hotel.example/?u=x"),
            CaptivePortal.classify(probe, 302, "https://login.hotel.example/?u=x", "text/html", ""),
        )
        assertEquals(CaptivePortal.Result.Portal(probe.toString()), CaptivePortal.classify(probe, 200, null, "text/html", "<html>Welcome</html>"))
        assertEquals(CaptivePortal.Result.Portal(probe.toString()), CaptivePortal.classify(probe, 511, null, null, ""))
        assertEquals(CaptivePortal.Result.Unknown, CaptivePortal.classify(probe, 404, null, null, ""))
    }

    @Test fun oneStatusCardMostActionableFirst() {
        val ok = StoragePolicy.Level.Ok
        assertNull(FrameStatuses.of(Connection.Online, true, false, ok))
        assertEquals(FrameStatus.CaptivePortal, FrameStatuses.of(Connection.ServerUnreachable, true, true, StoragePolicy.Level.Critical))
        assertEquals(FrameStatus.Offline, FrameStatuses.of(Connection.Connecting, false, false, ok))
        assertEquals(FrameStatus.Offline, FrameStatuses.of(Connection.Offline, true, false, ok))
        assertEquals(FrameStatus.ServerUnreachable, FrameStatuses.of(Connection.ServerUnreachable, true, false, StoragePolicy.Level.Critical))
        assertEquals(FrameStatus.StorageCritical, FrameStatuses.of(Connection.Online, true, false, StoragePolicy.Level.Critical))
        assertNull(FrameStatuses.of(Connection.Online, true, false, StoragePolicy.Level.Low))
        assertEquals(60_000L, FrameStatuses.delayMs(FrameStatus.Offline))
        assertEquals(0L, FrameStatuses.delayMs(FrameStatus.CaptivePortal))
    }
}
