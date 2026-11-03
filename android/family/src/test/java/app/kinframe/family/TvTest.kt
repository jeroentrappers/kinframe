package app.kinframe.family

import app.kinframe.core.api.FrameCommand
import app.kinframe.core.api.CommandResult
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.TvLink
import app.kinframe.core.api.TvLinkCreate
import app.kinframe.family.ui.tv.TvLinks
import app.kinframe.family.ui.tv.TvMode
import app.kinframe.family.ui.tv.TvNotice
import app.kinframe.family.ui.tv.TvScope
import app.kinframe.family.ui.tv.TvViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Show on TV: link bodies like the web's `linkBody`, and the dialog's view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun json(b: TvLinkCreate) = KinJson.encodeToString(TvLinkCreate.serializer(), b)

    @Test fun linkBodiesFollowTheScope() {
        assertEquals("""{"interval_seconds":15,"purpose":"tv","expires_in_hours":12}""", json(TvLinks.body(TvScope.All, 15, "tv")))
        assertEquals("""{"interval_seconds":10,"purpose":"cast","expires_in_hours":6,"album_id":"a1"}""", json(TvLinks.body(TvScope.Album("a1", "Xmas"), 10, "cast")))
        assertEquals(listOf("p1", "p2"), TvLinks.body(TvScope.Selection(listOf("p1", "p2")), 10, "tv").placementIds)
        assertEquals(500, TvLinks.body(TvScope.Selection(List(600) { "p$it" }), 10, "tv").placementIds!!.size)
        // A mirror shows whatever the frame shows: no scope.
        assertEquals("""{"interval_seconds":10,"purpose":"mirror","expires_in_hours":12}""", json(TvLinks.body(TvScope.Selection(listOf("p1")), 10, "mirror")))
    }

    private fun link(id: String, purpose: String = "tv") = TvLink(id, "f1", "t$id", "https://k.example/tv/f1?t=t$id", purpose, expiresAt = "2026-11-03T22:00:00Z", createdByName = "Anna")

    @Test fun linksForTheTvBrowserAndForPresenting() = runTest {
        val made = mutableListOf<TvLinkCreate>()
        val revoked = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val api = object : FakeApi() {
            override suspend fun tvLinks(frameId: String) = listOf(link("old"), link("c", "cast"))
            override suspend fun createTvLink(frameId: String, request: TvLinkCreate): TvLink { made += request; return link("n${made.size}", request.purpose) }
            override suspend fun revokeTvLink(frameId: String, linkId: String) { revoked += linkId }
            override suspend fun command(frameId: String, command: FrameCommand): CommandResult { commands += command.type; return CommandResult(true) }
        }
        val vm = TvViewModel(api, "f1", TvScope.Selection(listOf("p1")))
        val notices = mutableListOf<TvNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        assertEquals(2, vm.state.value.active.size)
        vm.setInterval(30)
        vm.makeLink()
        assertEquals("n1", vm.state.value.link!!.id)
        assertEquals(TvLinkCreate(30, "tv", 12, placementIds = listOf("p1")), made[0])
        vm.present()
        assertEquals("cast", made[1].purpose)
        assertTrue(notices.any { it is TvNotice.Present && it.link.id == "n2" })
        vm.setMode(TvMode.Mirror)
        assertNull(vm.state.value.link) // changing mode clears the link
        vm.makeLink()
        assertEquals(TvLinkCreate(30, "mirror", 12), made[2])
        vm.revoke(vm.state.value.link!!)
        assertEquals(listOf("n3"), revoked)
        assertNull(vm.state.value.link)
        vm.remote(next = true); vm.remote(next = false)
        assertEquals(listOf("next", "previous"), commands)
        job.cancel()
    }
}
