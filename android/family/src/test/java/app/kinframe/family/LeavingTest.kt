package app.kinframe.family

import app.kinframe.core.api.Item
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementsPage
import app.kinframe.core.leaving.KeepAnswer
import app.kinframe.core.leaving.KeepRequest
import app.kinframe.core.leaving.KeepRequestAnswer
import app.kinframe.core.leaving.KeepRequestCreate
import app.kinframe.core.leaving.KeepRequestItem
import app.kinframe.core.leaving.KeepRequestList
import app.kinframe.core.leaving.LeaveFeedRequest
import app.kinframe.core.leaving.LeavePreview
import app.kinframe.core.leaving.LeavePreviewFrame
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.leaving.MemoryPreferences
import app.kinframe.core.leaving.MemoryPreferencesUpdate
import app.kinframe.core.leaving.PhotosChoice
import app.kinframe.core.leaving.TakeAwayResult
import app.kinframe.core.leaving.TakenAwayGroup
import app.kinframe.core.leaving.TakenAwayGroupList
import app.kinframe.core.social.PersonRef
import app.kinframe.family.data.LeavingLive
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.leaving.KeepRequestsViewModel
import app.kinframe.family.ui.leaving.LeaveSheetViewModel
import app.kinframe.family.ui.leaving.LeavingNotice
import app.kinframe.family.ui.leaving.MemoriesViewModel
import app.kinframe.family.ui.leaving.TakenAwayViewModel
import app.kinframe.family.ui.leaving.resultText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Leaving a family (docs/spec/leaving.md): the leave sheet, keep requests, taken-away groups, memories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LeavingTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val calls = mutableListOf<Pair<String, List<Any?>>>()

    private fun api(answers: (String, List<Any?>) -> Any?): LeavingApi =
        Proxy.newProxyInstance(LeavingApi::class.java.classLoader, arrayOf(LeavingApi::class.java)) { _, m, args ->
            val a = args.orEmpty().toList().dropLast(1)
            calls += m.name to a
            answers(m.name, a) ?: Unit
        } as LeavingApi

    private val preview = LeavePreview(
        scope = "feed", feedId = "f1", name = "Janssens", photos = 214, comments = 38,
        frames = listOf(
            LeavePreviewFrame("fr1", "Oma", "member", canLeave = true, suggested = true),
            LeavePreviewFrame("fr2", "Kitchen", "owner", canLeave = false, suggested = true),
            LeavePreviewFrame("fr3", "Garden", "member", canLeave = true, suggested = false),
        ),
    )

    @Test
    fun leaveSheetDefaultsToKeepAndChecksSuggestedFrames() = runTest {
        val vm = LeaveSheetViewModel(api { n, _ ->
            when (n) {
                "leavePreview" -> preview
                "leaveFeed" -> TakeAwayResult("t1", 214, 0, "2026-12-19T10:00:00Z")
                else -> null
            }
        }, "f1", null)
        val s = vm.state.value
        assertEquals(PhotosChoice.KEEP, s.photos)
        // Suggested and leavable only: not the owned frame, not the unsuggested one.
        assertEquals(setOf("fr1"), s.frames)
        val events = mutableListOf<LeavingNotice>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.setPhotos(PhotosChoice.TAKE)
        vm.toggleFrame("fr3")
        vm.leave()
        val (_, args) = calls.last { it.first == "leaveFeed" }
        val req = args[1] as LeaveFeedRequest
        assertEquals(PhotosChoice.TAKE, req.photos)
        assertEquals(setOf("fr1", "fr3"), req.leaveFrameIds.toSet())
        assertEquals("t1", (events.single() as LeavingNotice.Done).result?.takingId)
        job.cancel()
    }

    @Test
    fun resultLine() {
        val res = RuntimeEnvironment.getApplication().resources
        assertTrue(resultText(res, null).isNotBlank())
        val line = resultText(res, TakeAwayResult("t1", 214, 0, "2026-12-19T10:00:00Z", remoteFrames = 1))
        assertTrue(line, line.contains("214"))
    }

    private fun request(items: Int) = KeepRequest(
        id = "k1", takingId = "t1", askedByName = "Lies", frameName = "Oma", itemCount = items,
        items = (1..items).map { KeepRequestItem(Item(id = "i$it")) },
    )

    @Test
    fun answeringAKeepRequest() = runBlocking {
        val vm = KeepRequestsViewModel(api { n, a ->
            when (n) {
                "myKeepRequests" -> KeepRequestList(listOf(request(3)))
                "keepRequest" -> request(3)
                "answerKeepRequest" -> request(3).copy(status = "partly_allowed", allowedCount = 2)
                else -> null
            }
        })
        vm.open("k1")
        // Everything is allowed until photos are left out.
        assertEquals(setOf("i1", "i2", "i3"), vm.state.value.allowed)
        vm.toggle("i2")
        vm.answer(KeepAnswer.SOME)
        val answer = calls.last { it.first == "answerKeepRequest" }.second[1] as KeepRequestAnswer
        assertEquals(KeepAnswer.SOME, answer.allow)
        assertEquals(listOf("i1", "i3"), answer.itemIds)
        assertNull(vm.state.value.open)
        assertTrue((vm.state.value.requests as Load.Ready).value.isEmpty())

        // All picked means "all"; none picked means "none".
        vm.open("k1"); vm.answer(KeepAnswer.SOME)
        assertEquals(KeepAnswer.ALL, (calls.last { it.first == "answerKeepRequest" }.second[1] as KeepRequestAnswer).allow)
        vm.open("k1"); listOf("i1", "i2", "i3").forEach(vm::toggle); vm.answer(KeepAnswer.SOME)
        assertEquals(KeepAnswer.NONE, (calls.last { it.first == "answerKeepRequest" }.second[1] as KeepRequestAnswer).allow)
    }

    @Test
    fun askingToKeepPicksASubset() = runBlocking {
        val group = TakenAwayGroup("t1", byName = "Tom", count = 3, canAsk = true)
        val placements = (1..3).map { Placement(id = "p$it", item = Item(id = "i$it")) }
        val vm = TakenAwayViewModel(api { n, _ ->
            when (n) {
                "frameTakenAway" -> TakenAwayGroupList(listOf(group))
                "takenAwayPlacements" -> PlacementsPage(placements)
                "createKeepRequest" -> request(1)
                else -> null
            }
        }, "fr1", null) { "idem" }
        vm.ask(group)
        assertEquals(3, vm.state.value.picked.size)
        vm.send()
        assertNull((calls.last { it.first == "createKeepRequest" }.second[0] as KeepRequestCreate).itemIds)
        vm.ask(group)
        vm.togglePhoto("i2")
        vm.onNote("The ones with the children")
        vm.send()
        val c = calls.last { it.first == "createKeepRequest" }.second[0] as KeepRequestCreate
        assertEquals(listOf("i1", "i3"), c.itemIds)
        assertEquals("fr1", c.frameId)
        assertEquals("The ones with the children", c.note)
        val g = (vm.state.value.groups as Load.Ready).value.single()
        assertEquals(false, g.canAsk)
    }

    @Test
    fun memoriesLeavePeopleOutAndHideOne() = runBlocking {
        var prefs = MemoryPreferences(listOf(PersonRef("u1", "Tom")), listOf("p1"))
        val a = api { n, args ->
            when (n) {
                "memories" -> prefs
                "updateMemories" -> {
                    val u = args[0] as MemoryPreferencesUpdate
                    prefs = MemoryPreferences(
                        u.hiddenPeople?.map { PersonRef(it, it) } ?: prefs.hiddenPeople, u.hiddenMemories ?: prefs.hiddenMemories,
                    )
                    prefs
                }
                else -> null
            }
        }
        val vm = MemoriesViewModel(a) { listOf(PersonRef("u2", "Lies")) }
        vm.hidePerson(PersonRef("u2", "Lies"))
        assertEquals(listOf("u1", "u2"), (calls.last { it.first == "updateMemories" }.second[0] as MemoryPreferencesUpdate).hiddenPeople)
        MemoriesViewModel.hideMemory(a, listOf("p2"))
        assertEquals(listOf("p1", "p2"), prefs.hiddenMemories)
    }

    @Test
    fun websocketAndPushEvents() {
        val e = LiveEvent.parse("""{"type":"keep_request.updated","request":{"id":"k1","status":"allowed","taking_id":"t1","asked_by_name":"Lies"}}""")
        assertEquals("allowed", (e as LeavingLive.KeepRequestUpdated).request.status)
        val p = LiveEvent.parse("""{"type":"placement.updated","frame_id":"fr1","placement_ids":["p1","p2"],"reason":"taken_away","taking_id":"t1"}""")
        assertEquals(listOf("p1", "p2"), (p as LeavingLive.PlacementUpdated).placementIds)
        val r = LiveEvent.parse("""{"type":"remote_subscription.updated","frame_id":"fr1","remote_subscription":{"id":"rs1","frame_id":"fr1","feed_name":"Janssens",
            "server":{"server_name":"Janssens","server_url":"https://a/"}}}""")
        assertEquals("rs1", (r as LeavingLive.RemoteSubscriptionUpdated).subscription.id)
        assertTrue(app.kinframe.family.notify.LeavingNotifications.handles("keep_request"))
        val t = app.kinframe.family.OpenTarget(app.kinframe.family.notify.LeavingNotifications.TARGET, ids = mapOf("leaving_key" to "keep_request", "request_id" to "k1"))
        val nav = app.kinframe.family.ui.Navigator()
        assertTrue(app.kinframe.family.ui.openLeavingTarget(t, nav))
        assertEquals(app.kinframe.family.ui.KeepRequestsRoute("k1"), nav.current)
    }
}
