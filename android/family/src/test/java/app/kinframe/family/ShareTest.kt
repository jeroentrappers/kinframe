package app.kinframe.family

import app.kinframe.core.api.BulkResult
import app.kinframe.core.api.Item
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementStatus
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendFrame
import app.kinframe.core.api.SendPlacement
import app.kinframe.core.api.ShareResult
import app.kinframe.core.api.ShareSkip
import app.kinframe.family.history.Delivery
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.share.Coverage
import app.kinframe.family.ui.share.ShareEvent
import app.kinframe.family.ui.share.ShareSource
import app.kinframe.family.ui.share.ShareViewModel
import app.kinframe.family.ui.share.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun coverageFromDryRunSkips() {
        val skips = listOf(ShareSkip("p1", "A"), ShareSkip("p2", "A"), ShareSkip("p1", "B"), ShareSkip("p1", "B"))
        assertEquals(mapOf("A" to Coverage.All, "B" to Coverage.Some, "C" to Coverage.None), Sharing.coverage(skips, listOf("A", "B", "C"), 2))
    }

    @Test fun targetsAreOtherFramesYouCanSendTo() {
        val frames = listOf(frame("src", "Oma"), frame("a", "Opa"), frame("b", "Tom", send = false))
        assertEquals(listOf("a"), Sharing.targets(frames, "src").map { it.id })
    }

    private val send = Send(
        "s1", "2026-10-22T10:00:00Z", items = listOf(Item("i1", "photo"), Item("i2", "photo")),
        frames = listOf(
            SendFrame("A", "Oma", placements = listOf(SendPlacement("a1", "i1", PlacementStatus.REMOVED), SendPlacement("a2", "i2", PlacementStatus.SEEN))),
            SendFrame("B", "Opa", placements = listOf(SendPlacement("b1", "i1", PlacementStatus.SEEN), SendPlacement("b2", "i2", PlacementStatus.PENDING, sharedByName = "Ben"))),
        ),
    )

    @Test fun sourceIsTheFirstFrameStillHoldingEveryItem() {
        assertEquals(ShareSource("A", listOf("a2")), Sharing.source(send, listOf("i2")))
        assertEquals(ShareSource("B", listOf("b1", "b2")), Sharing.source(send, listOf("i1", "i2")))
        assertNull(Sharing.source(send, listOf("i3")))
        assertNull(Sharing.source(send, emptyList()))
    }

    @Test fun historyShowsWhoSharedToAFrame() {
        val d = Delivery.frames(send)
        assertEquals(emptyList<String>(), d[0].sharedBy)
        assertEquals(listOf("Ben"), d[1].sharedBy)
    }

    @Test fun pickShareAndUndo() = runTest {
        val dry = mutableListOf<Boolean>()
        val keys = mutableListOf<String?>()
        val purged = mutableListOf<Pair<String, List<String>>>()
        val api = object : FakeApi() {
            override suspend fun frames() = listOf(frame("src", "Oma"), frame("b", "Opa"), frame("c", "Cabin"), frame("t", "Tom", send = false))
            override suspend fun sharePlacements(frameId: String, placementIds: List<String>, frameIds: List<String>, dryRun: Boolean, idempotencyKey: String?): ShareResult {
                dry += dryRun
                keys += idempotencyKey
                assertEquals("src", frameId)
                if (dryRun) {
                    assertEquals(listOf("c", "b"), frameIds)
                    return ShareResult(skipped = listOf(ShareSkip("p1", "b"), ShareSkip("p2", "b")))
                }
                assertEquals(listOf("c"), frameIds)
                return ShareResult(placements = placementIds.map { Placement("n-$it", "c", Item("i-$it", "photo"), sharedByName = "Me") })
            }
            override suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String): BulkResult {
                purged += frameId to placementIds
                assertEquals("purge", action)
                return BulkResult(ok = placementIds)
            }
        }
        val vm = ShareViewModel(api)
        val events = mutableListOf<ShareEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.open(ShareSource("src", listOf("p1", "p2")))
        val targets = (vm.state.value.targets as Load.Ready).value
        assertEquals(listOf("Cabin" to Coverage.None, "Opa" to Coverage.All), targets.map { it.frame.name to it.coverage })

        vm.toggle("b") // already has everything: can't be picked
        assertTrue(vm.state.value.chosen.isEmpty())
        vm.toggle("c")
        vm.confirm()
        assertFalse(vm.state.value.open)
        assertEquals(listOf(true, false), dry)
        assertNull(keys[0])
        assertTrue(!keys[1].isNullOrBlank())
        val shared = events.single() as ShareEvent.Shared
        assertEquals(listOf("Cabin"), shared.frames)
        assertEquals(2, shared.count)

        shared.undo()
        assertEquals(listOf("c" to listOf("n-p1", "n-p2")), purged)
        assertEquals(ShareEvent.Undone, events.last())
    }
}
