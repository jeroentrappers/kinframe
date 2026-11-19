package app.kinframe.family

import app.kinframe.core.api.Album
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.GoogleImport
import app.kinframe.core.api.GoogleImportCreate
import app.kinframe.core.api.GoogleImportProgress
import app.kinframe.core.api.GoogleImportStatus
import app.kinframe.core.api.ImmichAlbum
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceConnection
import app.kinframe.core.api.PhotoSourceCounts
import app.kinframe.core.api.PhotoSourceCreate
import app.kinframe.core.api.PhotoSourceDeletePolicy
import app.kinframe.core.api.PhotoSourceErrorCode
import app.kinframe.core.api.PhotoSourceHistory
import app.kinframe.core.api.PhotoSourceOwner
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.PhotoSourceStatus
import app.kinframe.core.api.PhotoSourceTarget
import app.kinframe.core.api.PhotoSourceTestResult
import app.kinframe.core.api.PhotoSourceUpdate
import app.kinframe.core.api.Problem
import app.kinframe.core.api.ProblemCode
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.sources.AddSourceViewModel
import app.kinframe.family.ui.sources.GoogleImportFlow
import app.kinframe.family.ui.sources.GoogleOpen
import app.kinframe.family.ui.sources.GooglePhase
import app.kinframe.family.ui.sources.PhotoSourcesViewModel
import app.kinframe.family.ui.sources.SourceCredit
import app.kinframe.family.ui.sources.SourceFix
import app.kinframe.family.ui.sources.SourceHeadline
import app.kinframe.family.ui.sources.SourceNotice
import app.kinframe.family.ui.sources.SourceScope
import app.kinframe.family.ui.sources.SourceStatus
import app.kinframe.family.ui.sources.SourceStep
import app.kinframe.family.ui.sources.SourceTarget
import app.kinframe.family.ui.sources.SourceTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun source(
    id: String = "ps1", status: String = PhotoSourceStatus.ACTIVE, provider: String = PhotoSourceProvider.IMMICH_LINK,
    code: String? = null, running: Boolean = false, pending: Int = 0, synced: String? = "2026-11-19T09:00:00Z",
    canManage: Boolean = true, feed: Boolean = false, albumId: String = "a1",
) = PhotoSource(
    id, provider, "Garden 2026",
    target = if (feed) PhotoSourceTarget(PhotoSourceTarget.KIND_FEED, feedId = "fd1", feedName = "Peeters kids")
    else PhotoSourceTarget(frameId = "f1", frameName = "Oma", albumId = albumId, albumName = "Garden"),
    owner = PhotoSourceOwner("u1", "Anna"), urlHint = "https://photos.peeters.be/share/…",
    serverUrl = if (provider == PhotoSourceProvider.IMMICH_API) "https://immich.peeters.be" else null,
    status = status, running = running, lastErrorCode = code, pendingRemovals = 342,
    counts = PhotoSourceCounts(imported = 214, pending = pending), lastSyncedAt = synced, nextSyncAt = "2026-11-19T09:30:00Z",
    canManage = canManage, isOwner = true,
)

private val albumTarget = SourceTarget.album("f1", "Oma", "a1", "Garden")
private val feedTarget = SourceTarget.feed("fd1", "Peeters kids")
private val allProviders = listOf(PhotoSourceProvider.IMMICH_LINK, PhotoSourceProvider.IMMICH_API, PhotoSourceProvider.ICLOUD_LINK)
private const val LINK = "https://photos.peeters.be/share/abcdEFGH1234"

private fun gi(status: String, auth: String? = null, picker: String? = null, picked: Int = 0, imported: Int = 0, sends: List<String> = emptyList()) =
    GoogleImport("gi1", status, PhotoSourceTarget(frameId = "f1", albumId = "a1", albumName = "Garden"), authUrl = auth, pickerUri = picker,
        progress = GoogleImportProgress(picked = picked, imported = imported), sendIds = sends)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhotoSourcesTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------------------------------------- status mapping

    @Test fun statusMapsToToneHeadlineAndFix() {
        val ok = SourceStatus.of(source())
        assertEquals(SourceTone.Good, ok.tone)
        assertEquals(SourceHeadline.Synced("2026-11-19T09:00:00Z", "2026-11-19T09:30:00Z"), ok.headline)
        assertNull(ok.problem)
        assertEquals(SourceHeadline.Running, SourceStatus.of(source(running = true)).headline)
        assertEquals("first import: imported of imported + pending", SourceHeadline.Importing(214, 220), SourceStatus.of(source(pending = 6)).headline)
        assertEquals(SourceHeadline.Waiting, SourceStatus.of(source(synced = null)).headline)
        assertEquals(SourceTone.Paused, SourceStatus.of(source(status = PhotoSourceStatus.PAUSED)).tone)

        val down = SourceStatus.of(source(status = PhotoSourceStatus.ERROR, code = PhotoSourceErrorCode.UNREACHABLE))
        assertEquals(SourceTone.Danger, down.tone)
        assertEquals(R.string.source_err_unreachable, down.problem)
        assertEquals("the host, never the link", listOf<Any>("photos.peeters.be"), down.problemArgs)
        assertEquals(SourceFix.None, down.fix)

        val retry = SourceStatus.of(source(code = PhotoSourceErrorCode.UPSTREAM_ERROR))
        assertEquals("a single retried failure stays green", SourceTone.Good, retry.tone)
        assertTrue(retry.retrying)

        val mass = SourceStatus.of(source(status = PhotoSourceStatus.NEEDS_ATTENTION, code = PhotoSourceErrorCode.MASS_REMOVAL))
        assertEquals(SourceTone.Warn, mass.tone)
        assertEquals(SourceFix.ConfirmRemovals, mass.fix)
        assertEquals(listOf<Any>(342), mass.problemArgs)

        fun fix(code: String, provider: String = PhotoSourceProvider.IMMICH_LINK, manage: Boolean = true) =
            SourceStatus.of(source(status = PhotoSourceStatus.NEEDS_ATTENTION, code = code, provider = provider, canManage = manage)).fix
        assertEquals(SourceFix.TakeOver, fix(PhotoSourceErrorCode.OWNER_LOST_ACCESS))
        assertEquals(SourceFix.NewPassword, fix(PhotoSourceErrorCode.AUTH_FAILED))
        assertEquals(SourceFix.NewKey, fix(PhotoSourceErrorCode.AUTH_FAILED, PhotoSourceProvider.IMMICH_API))
        assertEquals(SourceFix.NewLink, fix(PhotoSourceErrorCode.LINK_NOT_FOUND))
        assertEquals(SourceFix.NewKey, fix(PhotoSourceErrorCode.CREDENTIALS_UNREADABLE, PhotoSourceProvider.IMMICH_API))
        assertEquals(SourceFix.None, fix(PhotoSourceErrorCode.TLS_ERROR))
        assertEquals("only managers get a fix button", SourceFix.None, fix(PhotoSourceErrorCode.LINK_NOT_FOUND, manage = false))

        val api = source(status = PhotoSourceStatus.ERROR, code = PhotoSourceErrorCode.UNREACHABLE, provider = PhotoSourceProvider.IMMICH_API)
        assertEquals("immich.peeters.be", SourceStatus.host(api))
        assertEquals(R.string.source_err_owner_lost_feed, SourceStatus.errorText(PhotoSourceErrorCode.OWNER_LOST_ACCESS, source(feed = true)).first)
        assertEquals(R.string.source_err_format_icloud, SourceStatus.errorText(PhotoSourceErrorCode.FORMAT_CHANGED, source(provider = PhotoSourceProvider.ICLOUD_LINK)).first)
        assertEquals(R.string.source_err_unknown, SourceStatus.simpleError("brand_new_code"))
    }

    @Test fun creditAddsTheService() {
        val fmt = { n: String, v: String -> "$n via $v" }
        assertEquals("Anna", SourceCredit.text("Anna", null, fmt))
        assertEquals("Anna via Immich", SourceCredit.text("Anna", app.kinframe.core.api.ItemVia("immich", "Immich"), fmt))
        assertEquals("Anna via Google Photos", SourceCredit.text("Anna", app.kinframe.core.api.ItemVia("google_photos", ""), fmt))
    }

    // ---------------------------------------------------------------------------------------------- wizard

    private open inner class WizardApi : FakeApi() {
        val created = mutableListOf<Pair<PhotoSourceCreate, String>>()
        val tested = mutableListOf<PhotoSourceConnection>()
        var testResult = PhotoSourceTestResult(ok = true, albumName = "Garden 2026", photos = 214, videos = 6)
        var createError: Exception? = null
        override suspend fun testPhotoSource(connection: PhotoSourceConnection): PhotoSourceTestResult { tested += connection; return testResult }
        override suspend fun createPhotoSource(request: PhotoSourceCreate, idempotencyKey: String): PhotoSource {
            created += request to idempotencyKey
            createError?.let { createError = null; throw it }
            return source()
        }
    }

    @Test fun wizardLinkFlowForAnAlbum() = runTest {
        val api = WizardApi()
        val vm = AddSourceViewModel(api, albumTarget, allProviders, privateNetworks = false, newKey = { "key-1" })
        assertEquals(SourceStep.Provider, vm.state.value.step)
        assertFalse("first step closes the screen", vm.back())
        vm.choose(PhotoSourceProvider.IMMICH_LINK)
        assertEquals(SourceStep.Connection, vm.state.value.step)
        assertEquals(R.string.source_need_link, vm.state.value.connectionIssue)
        vm.onLink("https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz")
        assertEquals(R.string.source_link_is_icloud, vm.state.value.connectionIssue)
        vm.test()
        assertTrue("nothing is sent with an issue", api.tested.isEmpty())
        vm.onLink("Look: $LINK")
        assertNull(vm.state.value.connectionIssue)
        vm.onPassword("pw")
        vm.test()
        assertEquals(LINK, api.tested.single().url)
        assertEquals("pw", api.tested.single().password)
        val s = vm.state.value
        assertEquals(SourceStep.Options, s.step)
        assertEquals("Garden 2026", s.name)
        assertEquals("album defaults", PhotoSourceHistory.ALL, s.history)
        assertEquals(PhotoSourceDeletePolicy.MIRROR, s.onUpstreamDelete)
        assertEquals(220, s.firstImportCount)
        assertTrue("more than 200 photos into an album: suggest album-only", s.suggestAlbumOnly)
        vm.setHistory(PhotoSourceHistory.LAST_N, 50)
        assertFalse(vm.state.value.suggestAlbumOnly)
        vm.setHistory(PhotoSourceHistory.ALL)
        assertTrue(vm.back())
        assertEquals(SourceStep.Connection, vm.state.value.step)
        vm.test()
        vm.setNotifyReactions(false)
        vm.create()
        val (req, key) = api.created.single()
        assertEquals("a1", req.albumId)
        assertNull(req.feedId)
        assertNull("the default name isn't sent", req.name)
        assertFalse(req.notifyReactions)
        assertEquals("key-1", key)
        assertTrue(vm.state.value.created != null)
    }

    @Test fun wizardFeedDefaultsAndRetryKeepsTheKey() = runTest {
        val api = WizardApi().apply { createError = ApiException(503, Problem(code = "server_error")) }
        var n = 0
        val vm = AddSourceViewModel(api, feedTarget, listOf(PhotoSourceProvider.ICLOUD_LINK), privateNetworks = true, newKey = { "key-${++n}" })
        assertEquals("one provider: straight to the connection", SourceStep.Connection, vm.state.value.step)
        assertTrue(vm.state.value.bestEffort)
        vm.onLink("https://www.icloud.com/photos/#0abcDEF123")
        assertEquals(R.string.source_err_unsupported_link, vm.state.value.connectionIssue)
        vm.onLink("https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz")
        vm.test()
        val s = vm.state.value
        assertEquals(PhotoSourceHistory.LAST_N, s.history)
        assertEquals(PhotoSourceHistory.FEED_DEFAULT_COUNT, s.historyCount)
        assertEquals(PhotoSourceDeletePolicy.KEEP, s.onUpstreamDelete)
        assertFalse("never suggested for feeds", s.suggestAlbumOnly)
        vm.create()
        assertTrue(vm.state.value.createError != null)
        vm.create()
        assertEquals(listOf("key-1", "key-1"), api.created.map { it.second })
        assertEquals("fd1", api.created.last().first.feedId)
        assertEquals(20, api.created.last().first.historyCount)
        assertTrue(vm.back())
        assertFalse("one provider: the connection is the first step", vm.back())
    }

    @Test fun wizardImmichServerPicksAnAlbum() = runTest {
        val api = WizardApi().apply {
            testResult = PhotoSourceTestResult(ok = true, serverVersion = "v1.132.3", albums = listOf(ImmichAlbum("alb-1", "Christmas", 40), ImmichAlbum("alb-2", "Archive", 900)))
        }
        val vm = AddSourceViewModel(api, albumTarget, allProviders, privateNetworks = false)
        vm.choose(PhotoSourceProvider.IMMICH_API)
        vm.onServer("http://photos.peeters.be")
        vm.onKey("abcdefgh12")
        assertEquals(R.string.source_server_https, vm.state.value.connectionIssue)
        vm.onServer("http://192.168.1.10:2283")
        assertNull(vm.state.value.connectionIssue)
        assertTrue("home network on a server that can't reach it", vm.state.value.privateHint)
        vm.onServer("photos.peeters.be/api")
        assertFalse(vm.state.value.privateHint)
        vm.test()
        assertNull("listing albums: no album id yet", api.tested.single().immichAlbumId)
        assertEquals("https://photos.peeters.be", api.tested.single().serverUrl)
        assertEquals(SourceStep.Album, vm.state.value.step)
        vm.continueFromAlbum()
        assertEquals("pick one first", SourceStep.Album, vm.state.value.step)
        vm.pickAlbum("alb-2")
        vm.continueFromAlbum()
        assertEquals(SourceStep.Options, vm.state.value.step)
        assertEquals("Archive", vm.state.value.name)
        assertEquals(900, vm.state.value.firstImportCount)
        assertTrue(vm.back())
        assertEquals("back from options goes to the album list", SourceStep.Album, vm.state.value.step)
        vm.continueFromAlbum()
        vm.create()
        assertEquals("alb-2", api.created.single().first.connection.immichAlbumId)
        assertEquals("abcdefgh12", api.created.single().first.connection.apiKey)
    }

    @Test fun wizardTurnsOnAlbumOnlyWhenAsked() = runTest {
        val patched = mutableListOf<Pair<Int, String>>()
        val api = object : WizardApi() {
            override suspend fun albums(frameId: String) = listOf(Album("a1", "Garden", version = 7))
            override suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: kotlinx.serialization.json.JsonObject): Album {
                patched += version to patch.toString(); return Album("a1", "Garden", onlyInAlbumMode = true)
            }
        }
        val vm = AddSourceViewModel(api, albumTarget, allProviders, privateNetworks = false, link = LINK)
        assertEquals("a shared link skips the provider step", SourceStep.Connection, vm.state.value.step)
        assertEquals(PhotoSourceProvider.IMMICH_LINK, vm.state.value.provider)
        vm.test()
        vm.setAlbumOnly(true)
        vm.create()
        assertEquals(listOf(7 to """{"only_in_album_mode":true}"""), patched)
        // An album that is album-only already isn't asked again.
        assertFalse(AddSourceViewModel(api, albumTarget.copy(albumOnly = true), allProviders, false, LINK).also { it.test() }.state.value.suggestAlbumOnly)
    }

    @Test fun wizardGoesBackToTheLinkWhenTheServerCantReadIt() = runTest {
        val api = WizardApi().apply { createError = ApiException(422, Problem(code = ProblemCode.SOURCE_TEST_FAILED, detail = PhotoSourceErrorCode.LINK_NOT_FOUND)) }
        val vm = AddSourceViewModel(api, albumTarget, allProviders, false, LINK)
        vm.test()
        vm.create()
        assertEquals(SourceStep.Connection, vm.state.value.step)
        assertEquals(R.string.source_err_link_not_found, vm.state.value.testError)
        val failed = WizardApi().apply { testResult = PhotoSourceTestResult(ok = false, errorCode = PhotoSourceErrorCode.PASSWORD_REQUIRED) }
        val vm2 = AddSourceViewModel(failed, albumTarget, allProviders, false, LINK)
        vm2.test()
        assertEquals(SourceStep.Connection, vm2.state.value.step)
        assertEquals(R.string.source_err_password_required, vm2.state.value.testError)
    }

    // ---------------------------------------------------------------------------------------------- lists

    @Test fun listFollowsLiveEventsAndActions() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        val vm = PhotoSourcesViewModel(object : FakeApi() {
            override suspend fun albumPhotoSources(frameId: String, albumId: String) = listOf(source())
            override suspend fun updatePhotoSource(sourceId: String, update: PhotoSourceUpdate): PhotoSource {
                calls += "patch ${update.toJson()}"; return source(status = if (update.paused == true) PhotoSourceStatus.PAUSED else PhotoSourceStatus.ACTIVE)
            }
            override suspend fun syncPhotoSource(sourceId: String, confirmRemovals: Boolean): PhotoSource { calls += "sync $confirmRemovals"; return source(running = true) }
            override suspend fun deletePhotoSource(sourceId: String, removeItems: Boolean) { calls += "delete $removeItems" }
        }, SourceScope.Album("f1", "a1"), live)
        val notices = mutableListOf<SourceNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        assertEquals(1, (vm.state.value.sources as Load.Ready).value.size)
        live.emit(LiveEvent.PhotoSourceUpdated(source(id = "ps2")))
        live.emit(LiveEvent.PhotoSourceUpdated(source(id = "other", albumId = "a9")))
        assertEquals("only this album's sources", listOf("ps1", "ps2"), (vm.state.value.sources as Load.Ready).value.map { it.id })
        live.emit(LiveEvent.PhotoSourceUpdated(source(id = "ps2", status = PhotoSourceStatus.ERROR)))
        assertEquals(PhotoSourceStatus.ERROR, (vm.state.value.sources as Load.Ready).value[1].status)
        live.emit(LiveEvent.PhotoSourceRemoved("ps2", "a1", null))
        assertEquals(listOf("ps1"), (vm.state.value.sources as Load.Ready).value.map { it.id })
        val s = source()
        vm.setPaused(s, true)
        assertTrue((vm.state.value.sources as Load.Ready).value.single().isPaused)
        vm.syncNow(s)
        vm.confirmRemovals(s)
        vm.takeOver(s, null)
        vm.update(s, PhotoSourceUpdate())
        vm.delete(s, removeItems = true)
        assertEquals(listOf("""patch {"paused":true}""", "sync false", "sync true", """patch {"take_over":true}""", "delete true"), calls)
        assertEquals(emptyList<PhotoSource>(), (vm.state.value.sources as Load.Ready).value)
        assertEquals(SourceNotice.Message(R.string.source_deleted_removed, listOf("Garden 2026")), notices.last())
        job.cancel()
    }

    @Test fun syncTooSoonIsExplained() = runTest {
        val vm = PhotoSourcesViewModel(object : FakeApi() {
            override suspend fun myPhotoSources() = listOf(source())
            override suspend fun syncPhotoSource(sourceId: String, confirmRemovals: Boolean): PhotoSource = throw ApiException(429, Problem(code = ProblemCode.RATE_LIMITED))
        }, SourceScope.Mine, emptyFlow())
        val notices = mutableListOf<SourceNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        vm.syncNow(source())
        assertEquals(SourceNotice.Error(R.string.source_problem_rate_limited), notices.single())
        assertTrue(vm.state.value.busy.isEmpty())
        job.cancel()
    }

    // ---------------------------------------------------------------------------------------------- Google flow

    private inner class GoogleApi : FakeApi() {
        val started = mutableListOf<GoogleImportCreate>()
        var current = gi(GoogleImportStatus.NEEDS_AUTH, auth = "https://accounts.google.com/auth")
        var cancelled = 0
        val unsent = mutableListOf<String>()
        override suspend fun startGoogleImport(request: GoogleImportCreate, idempotencyKey: String): GoogleImport { started += request; return current }
        override suspend fun googleImport(importId: String) = current
        override suspend fun cancelGoogleImport(importId: String): GoogleImport { cancelled++; current = current.copy(status = GoogleImportStatus.CANCELLED); return current }
        override suspend fun unsend(sendId: String) { unsent += sendId }
        override suspend fun myGoogleImports() = listOf(current)
    }

    @Test fun googleFlowConsentReturnPickImportDone() = runTest {
        val api = GoogleApi()
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val flow = GoogleImportFlow({ api }, backgroundScope, live, pollMs = 3_000)
        val opens = mutableListOf<GoogleOpen>()
        val finished = mutableListOf<GoogleImport>()
        backgroundScope.launch { flow.opens.collect { opens += it } }
        backgroundScope.launch { flow.finished.collect { finished += it } }
        runCurrent()
        assertEquals(GooglePhase.Idle, flow.state.value.phase)

        flow.start(albumTarget)
        runCurrent()
        assertEquals(GoogleImportCreate(albumId = "a1", returnUri = "app.kinframe:/google-import"), api.started.single())
        assertEquals(GooglePhase.Consent, flow.state.value.phase)
        assertEquals(listOf<GoogleOpen>(GoogleOpen.Consent("https://accounts.google.com/auth")), opens)

        // Google redirects to the app; the query is only a hint, the import is fetched.
        api.current = gi(GoogleImportStatus.PICKING, picker = "https://photospicker.google.com/xyz")
        flow.onReturn("app.kinframe:/google-import?import_id=gi1&status=picking")
        runCurrent()
        assertEquals(GooglePhase.Picking, flow.state.value.phase)
        assertEquals(GoogleOpen.Picker("https://photospicker.google.com/xyz"), opens.last())
        flow.onResume()
        runCurrent()
        assertEquals("the picker opens once", 2, opens.size)

        // The server notices Done; the app polls.
        api.current = gi(GoogleImportStatus.IMPORTING, picked = 23, imported = 9)
        advanceTimeBy(3_100); runCurrent()
        assertEquals(GooglePhase.Importing, flow.state.value.phase)
        assertEquals(9, flow.state.value.import?.progress?.imported)
        // The websocket is quicker than polling.
        live.emit(LiveEvent.GoogleImportUpdated(gi(GoogleImportStatus.DONE, picked = 23, imported = 23, sends = listOf("s1"))))
        runCurrent()
        assertEquals(GooglePhase.Done, flow.state.value.phase)
        assertEquals(1, finished.size)
        // A late poll answer doesn't bring it back.
        flow.onReturn("app.kinframe:/google-import?import_id=gi1&status=picking")
        runCurrent()
        assertEquals(GooglePhase.Done, flow.state.value.phase)
        flow.undo()
        runCurrent()
        assertEquals(listOf("s1"), api.unsent)
        assertTrue(flow.state.value.undone)
        flow.dismiss()
        assertEquals(GooglePhase.Idle, flow.state.value.phase)
    }

    @Test fun googleFlowCancelFailAndOtherImports() = runTest {
        val api = GoogleApi().apply { current = gi(GoogleImportStatus.PICKING, picker = "https://photospicker.google.com/xyz") }
        val flow = GoogleImportFlow({ api }, backgroundScope, emptyFlow())
        val opens = mutableListOf<GoogleOpen>()
        backgroundScope.launch { flow.opens.collect { opens += it } }
        runCurrent()
        flow.start(feedTarget)
        runCurrent()
        assertEquals("fresh token: straight to the picker", GooglePhase.Picking, flow.state.value.phase)
        assertEquals("fd1", api.started.single().feedId)
        assertNull(api.started.single().albumId)
        // A return for somebody else's import is ignored.
        flow.onReturn("app.kinframe:/google-import?import_id=other&status=picking")
        flow.cancel()
        runCurrent()
        assertEquals(1, api.cancelled)
        assertEquals(GooglePhase.Cancelled, flow.state.value.phase)
        flow.cancel()
        assertEquals("final: nothing to cancel", 1, api.cancelled)

        api.current = gi(GoogleImportStatus.FAILED).copy(errorCode = "consent_denied")
        flow.start(albumTarget)
        runCurrent()
        assertEquals(GooglePhase.Failed, flow.state.value.phase)
        assertEquals(R.string.google_error_consent, GoogleImportFlow.errorText(flow.state.value.import?.errorCode))

        val off = GoogleImportFlow({ object : FakeApi() {
            override suspend fun startGoogleImport(request: GoogleImportCreate, idempotencyKey: String): GoogleImport =
                throw ApiException(503, Problem(code = ProblemCode.FEATURE_DISABLED))
        } }, backgroundScope, emptyFlow())
        off.start(albumTarget)
        runCurrent()
        assertEquals(GooglePhase.Error, off.state.value.phase)
        assertEquals(R.string.google_error_disabled, off.state.value.error)
    }

    @Test fun googleFlowResumesWithoutJumpingIntoGoogle() = runTest {
        val api = GoogleApi().apply { current = gi(GoogleImportStatus.PICKING, picker = "https://photospicker.google.com/xyz") }
        val flow = GoogleImportFlow({ api }, backgroundScope, emptyFlow())
        val opens = mutableListOf<GoogleOpen>()
        backgroundScope.launch { flow.opens.collect { opens += it } }
        runCurrent()
        flow.recover()
        runCurrent()
        assertEquals(GooglePhase.Picking, flow.state.value.phase)
        assertTrue("after a restart the picker isn't opened by itself", opens.isEmpty())
        flow.reopen()
        runCurrent()
        assertEquals(1, opens.size)
        api.current = gi(GoogleImportStatus.DONE, imported = 3)
        flow.dismiss()
        flow.resume("gi1")
        runCurrent()
        assertEquals("a notification tap shows the result", GooglePhase.Done, flow.state.value.phase)
    }

    @Test fun googleReturnLinks() {
        assertTrue(GoogleImportFlow.isReturn("app.kinframe:/google-import?import_id=gi1&status=picking"))
        assertTrue(GoogleImportFlow.isReturn("app.kinframe:///google-import"))
        assertFalse("the sign-in redirect", GoogleImportFlow.isReturn("app.kinframe:/callback?code=x"))
        assertFalse(GoogleImportFlow.isReturn("https://kinframe.appmire.be/f/12345678"))
        assertFalse(GoogleImportFlow.isReturn(null))
    }

    // ---------------------------------------------------------------------------------------------- events and pushes

    @Test fun parsesPhotoSourceEventsAndRoutesPushes() {
        val up = LiveEvent.parse("""{"type":"photo_source.updated","source":{"id":"ps1","provider":"immich_link","name":"Garden","target":{"kind":"album","album_id":"a1"},"status":"error","running":false,"history":"all","on_upstream_delete":"mirror","include_videos":true,"counts":{"imported":1,"pending":0},"created_at":"2026-11-19T10:00:00Z"}}""")
        assertEquals(PhotoSourceStatus.ERROR, (up as LiveEvent.PhotoSourceUpdated).source.status)
        assertEquals(LiveEvent.PhotoSourceRemoved("ps1", null, "fd1"), LiveEvent.parse("""{"type":"photo_source.removed","source_id":"ps1","feed_id":"fd1"}"""))
        val g = LiveEvent.parse("""{"type":"google_import.updated","import":{"id":"gi1","status":"importing","target":{"kind":"feed","feed_id":"fd1"},"progress":{"picked":5,"imported":2},"created_at":"2026-11-19T10:00:00Z"}}""")
        assertEquals(2, (g as LiveEvent.GoogleImportUpdated).import.progress.imported)
        fun push(key: String, ids: Map<String, String>) = PushMessage("push", "", "", ids, key)
        assertEquals(Notifications.TARGET_PHOTO_SOURCES, Notifications.targetOf(push("photo_source_attention", mapOf("source_id" to "ps1", "album_id" to "a1", "frame_id" to "f1"))))
        assertEquals(Notifications.TARGET_GOOGLE_IMPORT, Notifications.targetOf(push("google_import_done", mapOf("import_id" to "gi1"))))
    }
}
