package app.kinframe.family

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Frame
import app.kinframe.core.api.Invite
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.Member
import app.kinframe.core.api.Problem
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.frame.FrameDetailViewModel
import app.kinframe.family.ui.frame.FrameEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Transferring ownership and moving a frame to a new device (web `TransferDialog`, `ReplaceDeviceDialog`). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OwnershipTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val lies = Member("u2", "Lies", role = "admin")

    private open inner class Api : FakeApi() {
        val calls = mutableListOf<String>()
        override suspend fun frame(frameId: String) = Frame("f1", "Oma", role = "owner")
        override suspend fun members(frameId: String) = listOf(Member("me", "Me", role = "owner"), lies)
        override suspend fun joinRequests(frameId: String) = emptyList<JoinRequest>()
        override suspend fun invites(frameId: String) = emptyList<Invite>()
        override suspend fun transferOwnership(frameId: String, userId: String) { calls += "transfer $userId" }
        override suspend fun leaveFrame(frameId: String, removeMyItems: Boolean) { calls += "leave $removeMyItems" }
        override suspend fun replaceDevice(frameId: String, code: String): Frame { calls += "replace $code"; return Frame("f1", "Oma", role = "owner", online = true) }
    }

    private fun vm(api: Api) = FrameDetailViewModel(api, "f1", { "me" }, emptyFlow())

    @Test fun transferThenLeaveGoesBackToTheFrames() = runTest {
        val api = Api()
        val m = vm(api)
        val events = mutableListOf<FrameEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events += it } }
        m.transfer(lies, leaveAfter = true, removeMine = true)
        assertEquals(listOf("transfer u2", "leave true"), api.calls)
        assertEquals(FrameEvent.Message(R.string.transfer_done_left, "Lies", "Oma"), events[0])
        assertEquals(FrameEvent.Gone, events[1])
        job.cancel()
    }

    @Test fun aFailedLeaveAfterAGoodTransferKeepsTheFrame() = runTest {
        val api = object : Api() {
            override suspend fun leaveFrame(frameId: String, removeMyItems: Boolean) = throw ApiException(409, Problem(code = "owner_cannot_leave"))
        }
        val m = vm(api)
        val events = mutableListOf<FrameEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { m.events.collect { events += it } }
        m.transfer(lies, leaveAfter = true, removeMine = false)
        assertEquals(FrameEvent.Error(R.string.problem_owner_cannot_leave), events[0])
        assertEquals(FrameEvent.Message(R.string.transfer_done, "Lies"), events[1])
        assertTrue(FrameEvent.Gone !in events)
        job.cancel()
    }

    @Test fun moveToANewDevice() = runTest {
        val api = Api()
        val m = vm(api)
        m.replaceDevice("12345678")
        assertEquals(listOf("replace 12345678"), api.calls)
        assertTrue(m.state.value.replaced)
        assertTrue((m.state.value.frame as Load.Ready).value.online)
    }
}
