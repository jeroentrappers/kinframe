package app.kinframe.family

import android.net.Uri
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.Problem
import app.kinframe.core.api.RedeemResult
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendFrame
import app.kinframe.core.api.SendPlacement
import app.kinframe.core.api.SendsPage
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.send.MediaInfo
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.frames.AddFrameViewModel
import app.kinframe.family.ui.frames.FramesViewModel
import app.kinframe.family.ui.history.HistoryViewModel
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.send.ItemIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Every KinApi call fails unless a test overrides it. */
private val Unimplemented: KinApi = Proxy.newProxyInstance(KinApi::class.java.classLoader, arrayOf(KinApi::class.java)) { _, m, _ ->
    throw UnsupportedOperationException(m.name)
} as KinApi

open class FakeApi : KinApi by Unimplemented

fun frame(id: String, name: String, online: Boolean = false, send: Boolean = true) =
    Frame(id, name, role = "member", permissions = Permissions(send = send), online = online)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun framesFollowLiveStatus() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 4)
        val vm = FramesViewModel(object : FakeApi() {
            override suspend fun frames() = listOf(frame("b", "Opa"), frame("a", "Oma"))
        }, live)
        val list = (vm.state.value.frames as Load.Ready).value
        assertEquals(listOf("Oma", "Opa"), list.map { it.name })
        live.emit(LiveEvent.FrameStatus("a", true, null))
        assertEquals(1, vm.state.value.online)
    }

    @Test fun framesErrorMapsProblemCode() = runTest {
        val vm = FramesViewModel(object : FakeApi() {
            override suspend fun frames(): List<Frame> = throw ApiException(403, Problem(code = "forbidden"))
        }, emptyFlow())
        assertEquals(Load.Error(R.string.problem_forbidden), vm.state.value.frames)
    }

    @Test fun addFrameRedeemsWithStableIdempotencyKey() = runTest {
        val keys = mutableListOf<String>()
        var fail = true
        val vm = AddFrameViewModel(object : FakeApi() {
            override suspend fun redeem(code: String, idempotencyKey: String): RedeemResult {
                keys += idempotencyKey
                if (fail) { fail = false; throw java.io.IOException("offline") }
                return RedeemResult("claimed", frame("f", "Kinframe"))
            }
        })
        vm.onInput("1234")
        assertEquals("1234", vm.state.value.digits)
        vm.submit() // incomplete: nothing happens
        assertTrue(keys.isEmpty())
        vm.onInput("1234 567")
        vm.onInput("1234 5678") // typed digit by digit: no auto-submit
        assertTrue(keys.isEmpty())
        vm.submit()
        assertEquals(R.string.problem_network, vm.state.value.error)
        vm.submit()
        assertEquals("claimed", vm.state.value.result?.status)
        assertEquals(1, keys.distinct().size)
        assertEquals("Kinframe", vm.state.value.name)
    }

    @Test fun pastedCodeSubmitsRightAway() = runTest {
        var redeemed = ""
        val vm = AddFrameViewModel(object : FakeApi() {
            override suspend fun redeem(code: String, idempotencyKey: String): RedeemResult {
                redeemed = code
                return RedeemResult("joined", frame("f", "Oma"))
            }
        })
        vm.onInput("https://kinframe.appmire.be/f/87654321")
        assertEquals("87654321", redeemed)
        assertEquals("joined", vm.state.value.result?.status)
    }

    private fun info(n: Int, kind: String = "photo", size: Long = 1000, duration: Long? = null) =
        MediaInfo(Uri.parse("content://media/$n"), "f$n.jpg", if (kind == "photo") "image/jpeg" else "video/mp4", size, kind, duration, 400, 300)

    @Test fun composerBuildsTheOutboxSend() = runTest {
        var queued: OutboxSend? = null
        var id = 0
        val vm = ComposerViewModel(
            api = { object : FakeApi() { override suspend fun frames() = listOf(frame("f1", "Oma", online = true), frame("f2", "Readonly", send = false)) } },
            limits = { Limits(maxItemsPerSend = 3, maxVideoSeconds = 60, maxUploadBytes = 5000) },
            server = { "https://kinframe.appmire.be/" },
            inspect = { uri -> if (uri.toString().endsWith("x")) null else info(uri.lastPathSegment!!.toInt()) },
            enqueue = { queued = it },
            newId = { "id${id++}" },
        )
        // Only frames we may send to; the single one is preselected.
        assertEquals(setOf("f1"), vm.state.value.selected)
        vm.add(listOf(Uri.parse("content://media/1"), Uri.parse("content://media/2"), Uri.parse("content://x"), Uri.parse("content://media/3"), Uri.parse("content://media/4")))
        val s = vm.state.value
        assertEquals(3, s.items.size)
        assertEquals(1, s.skipped)
        assertEquals(1, s.overLimit)
        val first = s.items[0].id
        vm.setCaption(first, "Hello")
        vm.applyCaptionToAll(first)
        vm.setFill(first, false)
        vm.setFocal(first, 0.2f, 1.4f)
        vm.send()
        val q = queued!!
        assertEquals(listOf("f1"), q.frameIds)
        assertEquals(listOf("Hello", "Hello", "Hello"), q.items.map { it.caption })
        assertEquals("fit", q.items[0].fit)
        assertEquals(0.2, q.items[0].focalX!!, 0.001)
        assertEquals(1.0, q.items[0].focalY!!, 0.001)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun composerFlagsOversizeAndTrimsLongVideos() = runTest {
        val vm = ComposerViewModel(
            api = { object : FakeApi() { override suspend fun frames() = listOf(frame("f1", "Oma")) } },
            limits = { Limits(maxItemsPerSend = 10, maxVideoSeconds = 60, maxUploadBytes = 5000) },
            server = { "" },
            inspect = { uri -> when (uri.lastPathSegment) { "1" -> info(1, size = 9000); else -> info(2, "video", duration = 120_000) } },
            enqueue = { },
        )
        vm.add(listOf(Uri.parse("content://media/1"), Uri.parse("content://media/2")))
        val s = vm.state.value
        assertEquals(ItemIssue.TooLarge, s.issue(s.items[0]))
        // Long videos start trimmed to the server's maximum, so they can be sent.
        assertEquals(0L to 60_000L, s.items[1].trim)
        assertNull(s.issue(s.items[1]))
        assertEquals(1, s.issues)
        vm.setTrim(s.items[1].id, 10_000, 11_000, app.kinframe.family.send.TrimHandle.End)
        assertEquals(8_000L to 11_000L, vm.state.value.items[1].trim)
    }

    @Test fun historyAppliesLiveUpdates() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 4)
        val s1 = Send("s1", "2026-10-22T10:00:00Z", frames = listOf(SendFrame("f", "Oma", placements = listOf(SendPlacement("p1", "i", "delivered")))))
        val vm = HistoryViewModel(object : FakeApi() {
            override suspend fun sends(cursor: String?, limit: Int, state: String) = SendsPage(listOf(s1))
        }, live, emptyFlow())
        live.emit(LiveEvent.Reaction("p1", "f", "love", "Oma"))
        val list = (vm.state.value.sends as Load.Ready).value
        assertEquals("love", list[0].frames[0].placements[0].reaction)
        val s2 = Send("s2", "2026-10-22T11:00:00Z")
        live.emit(LiveEvent.SendUpdated(s2))
        assertEquals(listOf("s2", "s1"), (vm.state.value.sends as Load.Ready).value.map { it.id })
        assertEquals("s1", vm.sendWithPlacement("p1"))
        assertNotEquals(null, vm.sendWithPlacement("p1"))
    }
}
