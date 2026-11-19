package app.kinframe.frame.update

import app.kinframe.core.api.AppUpdateRequiredException
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.core.auth.MemoryCredentialStore
import app.kinframe.frame.FakeServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Google Play build's updates (docs/play-frame.md): Play's in-app updates through [InAppUpdates], never the
 * server's `GET /v1/device/update` and never an APK install of its own.
 */
class PlayUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val fake = FakeServer()
    private lateinit var api: DeviceApi

    private var now = 1_800_000_000_000L
    private var elapsed = 50_000_000L
    private var idle = true
    private var stateChanges = 0

    /** Records what the updater asks Play for. */
    private class FakePlay : InAppUpdates {
        var info: InAppUpdates.Info? = InAppUpdates.Info(available = false)
        var result = InAppUpdates.Result.Accepted
        var completeOk = true
        val started = mutableListOf<InAppUpdates.Type>()
        var infoCalls = 0
        var completes = 0
        override suspend fun info(): InAppUpdates.Info? { infoCalls++; return info }
        override suspend fun start(type: InAppUpdates.Type): InAppUpdates.Result { started += type; return result }
        override suspend fun complete(): Boolean { completes++; return completeOk }
        override fun listen(onProgress: (InAppUpdates.Progress) -> Unit) = Unit
    }

    private val play = FakePlay()
    private lateinit var store: UpdateStore

    private fun updater(versionCode: Int = 20099, versionName: String = "0.2.0") = PlayUpdater(
        play, store, versionCode, versionName,
        interruptible = { user -> user || idle },
        onStateChanged = { stateChanges++ },
        now = { now }, elapsed = { elapsed }, jitter = { 0L },
    )

    private val available = InAppUpdates.Info(available = true, versionCode = 30099, flexibleAllowed = true, immediateAllowed = true)

    @Before fun setUp() {
        server.dispatcher = fake
        server.start()
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("d", "s")), "kinframe-frame/0.2.0 (20099)")
        store = UpdateStore(File(tmp.newFolder("state"), "update.json"))
    }

    @After fun tearDown() { server.close() }

    private fun serverPaths() = fake.requests.map { it.url.encodedPath }

    @Test fun updateRequiredStartsAnImmediateUpdateThroughPlay() = runBlocking {
        val u = updater()
        u.bind(api)
        play.info = available
        fake.status["/v1/device/state"] = 426
        try {
            api.state()
            fail("expected 426")
        } catch (e: AppUpdateRequiredException) {
            // the frame can't talk to its server until it is updated
        }
        u.processPending()
        assertEquals(listOf(InAppUpdates.Type.Immediate), play.started)
        assertEquals("installing", u.heartbeatState().first)
        assertEquals(30099, store.record.pending!!.targetCode)
        assertEquals("0.3.0", store.record.pending!!.targetName)
        assertTrue(stateChanges > 0)
        // Never the server's release mirror.
        assertTrue(serverPaths().toString(), serverPaths().none { it.contains("/device/update") })
    }

    @Test fun updateRequiredIsThrottledToOnceInTenMinutes() = runBlocking {
        val u = updater()
        play.info = available
        play.result = InAppUpdates.Result.Declined
        u.onUpdateRequired(); u.processPending()
        u.onUpdateRequired(); u.processPending()
        assertEquals(1, play.started.size)
        elapsed += PlayUpdater.REQUIRED_THROTTLE_MS + 1
        u.onUpdateRequired(); u.processPending()
        assertEquals(2, play.started.size)
    }

    @Test fun updateRequiredFallsBackToFlexibleWhenPlayAllowsOnlyThat() = runBlocking {
        val u = updater()
        play.info = available.copy(immediateAllowed = false)
        u.onUpdateRequired(); u.processPending()
        assertEquals(listOf(InAppUpdates.Type.Flexible), play.started)
        assertEquals("downloading", u.heartbeatState().first)
    }

    @Test fun scheduledCheckWaitsForAnIdleFrameThenStartsAFlexibleUpdate() = runBlocking {
        val u = updater()
        u.prepare()
        // The first check comes 5 minutes after start at the earliest, as the self-update's.
        assertEquals(now + UpdatePolicy.FIRST_CHECK_MIN_MS, store.record.nextCheckAt)
        play.info = available
        idle = false
        now = store.record.nextCheckAt
        u.check(UpdateTrigger.Scheduled)
        assertEquals(1, play.infoCalls)
        assertTrue("someone is using the frame: Play doesn't ask yet", play.started.isEmpty())
        assertEquals(UpdatePhase.Waiting, u.status.value.phase)
        assertEquals(now + UpdatePolicy.CHECK_INTERVAL_MS, store.record.nextCheckAt)
        idle = true
        u.runOnce(waitMs = 1)
        assertEquals(listOf(InAppUpdates.Type.Flexible), play.started)
        // Downloaded: installed at the next idle moment (Play restarts the app).
        u.onProgress(InAppUpdates.Progress(InAppUpdates.Progress.State.Downloaded))
        assertEquals("waiting", u.heartbeatState().first)
        idle = false
        u.runOnce(waitMs = 1) // the Remote check the download queued: not idle, so it waits
        assertEquals(0, play.completes)
        idle = true
        play.info = available.copy(downloaded = true)
        u.runOnce(waitMs = 1)
        assertEquals(1, play.completes)
        assertTrue(serverPaths().none { it.contains("/device/update") })
    }

    @Test fun someoneOnTheFrameGetsTheImmediateFlowRightAway() = runBlocking {
        val u = updater()
        play.info = available
        idle = false
        u.check(UpdateTrigger.User)
        assertEquals(listOf(InAppUpdates.Type.Immediate), play.started)
    }

    @Test fun upToDateDoesNothing() = runBlocking {
        val u = updater()
        play.info = InAppUpdates.Info(available = false)
        u.check(UpdateTrigger.User)
        assertTrue(play.started.isEmpty())
        assertEquals(UpdatePhase.UpToDate, u.status.value.phase)
        assertEquals("idle", u.heartbeatState().first)
    }

    @Test fun withoutPlayNothingHappens() = runBlocking {
        val u = updater()
        play.info = null // no Play Store, no Play services, or not installed from Play
        u.onUpdateRequired(); u.processPending()
        u.check(UpdateTrigger.Scheduled)
        u.runOnce(waitMs = 1)
        assertTrue(play.started.isEmpty())
        assertEquals(0, play.completes)
        assertEquals(UpdatePhase.Idle, u.status.value.phase)
        assertEquals("idle", u.heartbeatState().first)
        // Someone who asked is told it didn't work.
        u.check(UpdateTrigger.User)
        assertEquals(UpdatePhase.Failed, u.status.value.phase)
        assertEquals("play_unavailable", u.status.value.error)
    }

    @Test fun declinedIsAskedAgainAtTheNextCheck() = runBlocking {
        val u = updater()
        play.info = available
        play.result = InAppUpdates.Result.Declined
        u.check(UpdateTrigger.Scheduled)
        assertEquals("idle", u.heartbeatState().first)
        assertNull(store.record.pending)
        assertEquals(now + UpdatePolicy.CHECK_INTERVAL_MS, store.record.nextCheckAt)
    }

    @Test fun flowFailureIsReportedAndRetriedInAnHour() = runBlocking {
        val u = updater()
        play.info = available
        play.result = InAppUpdates.Result.Failed
        u.check(UpdateTrigger.Remote)
        assertEquals("failed" to "play_flow_failed", u.heartbeatState())
        assertEquals(now + UpdatePolicy.RETRY_AFTER_FAILURE_MS, store.record.nextCheckAt)
    }

    @Test fun theNewVersionAnnouncesTheUpdate() = runBlocking {
        val old = updater(versionCode = 20099, versionName = "0.2.0")
        play.info = available
        old.check(UpdateTrigger.User)
        // Play installed 0.3.0 and restarted the app.
        val updated = updater(versionCode = 30099, versionName = "0.3.0")
        updated.onAppStart()
        assertEquals("updated", updated.heartbeatState().first)
        assertNull(store.record.pending)
        assertEquals("0.3.0", updated.prepare())
        assertNull("announced once", updated.prepare())
    }

    @Test fun aStaleUpdateIsForgotten() = runBlocking {
        val u = updater()
        play.info = available
        play.result = InAppUpdates.Result.Accepted
        u.check(UpdateTrigger.Remote)
        assertFalse(store.record.pending == null)
        now += PlayUpdater.PENDING_MAX_MS + 1
        updater().onAppStart()
        assertNull(store.record.pending)
        assertEquals("idle", store.record.state)
    }

    @Test fun versionNamesFollowTheReleaseScheme() {
        assertEquals("0.2.0", PlayUpdater.versionNameOf(20099))
        assertEquals("0.3.0-beta.2", PlayUpdater.versionNameOf(30002))
        assertEquals("1.12.3", PlayUpdater.versionNameOf(1_120_399))
        assertNull(PlayUpdater.versionNameOf(1))
        assertNull(PlayUpdater.versionNameOf(20000))
    }
}
