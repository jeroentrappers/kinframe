package app.kinframe.core

import app.kinframe.core.api.Discovery
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendsPage
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.servers.AccountIndex
import app.kinframe.core.servers.Merging
import app.kinframe.core.servers.RemoteApi
import app.kinframe.core.servers.Routes
import app.kinframe.core.servers.RoutingKinApi
import app.kinframe.core.servers.RoutingSocialApi
import app.kinframe.core.servers.ServerRoute
import app.kinframe.core.social.Badge
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.ThreadSummary
import app.kinframe.core.social.TimelinePage
import app.kinframe.core.social.TimelinePost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** A fake of an API interface: [answers] by method name, everything else fails. Suspend functions answer at once. */
@Suppress("UNCHECKED_CAST")
inline fun <reified T> fake(calls: MutableList<String> = mutableListOf(), noinline answers: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, m, args ->
        when (m.name) {
            "toString" -> "fake ${T::class.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> {
                val a = args.orEmpty().toList().dropLast(1) // the continuation
                calls += m.name + a.filterIsInstance<String>().joinToString(",", "(", ")")
                answers(m.name, a) ?: if (m.returnType == Void.TYPE || m.name.startsWith("delete")) Unit else throw UnsupportedOperationException(m.name)
            }
        }
    } as T

class RoutingTest {
    private fun disco(name: String, social: Boolean = true) = Discovery(name = name, feeds = true, social = social, chats = social, serverName = name)

    private class R(override val id: String, override val kin: KinApi, override val social: SocialApi?, override val discovery: Discovery) : ServerRoute {
        override val leaving: LeavingApi? = null
        override val remote: RemoteApi? = null
    }

    private fun post(id: String, at: String) = TimelinePost(id = id, poster = PersonRef("u", "U"), createdAt = at, thread = ThreadSummary(postId = id))

    @Test
    fun framesMergeAndDetailsGoToTheirOwnServer() = runBlocking {
        val callsA = mutableListOf<String>()
        val callsB = mutableListOf<String>()
        val a = fake<KinApi>(callsA) { n, args ->
            when (n) {
                "frames" -> listOf(Frame(id = "fa", name = "Kitchen"))
                "frame" -> Frame(id = args[0] as String, name = "Kitchen")
                "sends" -> SendsPage(listOf(Send(id = "s1", createdAt = "2026-11-19T10:00:00Z"), Send(id = "s3", createdAt = "2026-11-17T10:00:00Z")), "ca")
                else -> null
            }
        }
        val b = fake<KinApi>(callsB) { n, args ->
            when (n) {
                "frames" -> listOf(Frame(id = "fb", name = "Oma"))
                "frame" -> Frame(id = args[0] as String, name = "Oma")
                "sends" -> SendsPage(listOf(Send(id = "s2", createdAt = "2026-11-18T10:00:00Z")), null)
                else -> null
            }
        }
        val index = AccountIndex()
        val routes = Routes({ listOf(R("A", a, null, disco("A")), R("B", b, null, disco("B"))) }, index)
        val api = RoutingKinApi(routes, a)

        assertEquals(listOf("fa", "fb"), api.frames().map { it.id })
        assertEquals("B", index.get("fb"))
        assertEquals("Oma", api.frame("fb").name)
        assertTrue("frame(fb)" in callsB)
        assertTrue("frame(fb)" !in callsA)
        // Unknown ids go to the primary account.
        api.frame("zz")
        assertTrue("frame(zz)" in callsA)

        val page = api.sends(null, 30, "all")
        assertEquals(listOf("s1", "s2", "s3"), page.sends.map { it.id })
        // Only A has more: the next page asks A alone, with A's cursor.
        assertEquals(mapOf("A" to "ca"), Merging.decodeCursor(page.nextCursor))
        callsB.clear()
        api.sends(page.nextCursor, 30, "all")
        assertTrue(callsB.none { it.startsWith("sends") })
        assertTrue(callsA.contains("sends(ca,all)"))
    }

    @Test
    fun oneServerDownKeepsTheOthers() = runBlocking {
        val a = fake<KinApi> { n, _ -> if (n == "frames") listOf(Frame(id = "fa", name = "K")) else null }
        val b = fake<KinApi> { n, _ -> if (n == "frames") throw java.io.IOException("down") else null }
        val routes = Routes({ listOf(R("A", a, null, disco("A")), R("B", b, null, disco("B"))) }, AccountIndex())
        assertEquals(listOf("fa"), RoutingKinApi(routes, a).frames().map { it.id })
    }

    @Test
    fun homeChatsAndBadgeAcrossServers() = runBlocking {
        val sa = fake<SocialApi> { n, _ ->
            when (n) {
                "timeline" -> TimelinePage(listOf(post("pa1", "2026-11-19T10:00:00Z"), post("pa2", "2026-11-10T10:00:00Z")), "na")
                "conversations" -> listOf(Conversation(id = "ca", kind = "direct", lastActivityAt = "2026-11-10T10:00:00Z"))
                "badge" -> Badge(3, 2, 1)
                else -> null
            }
        }
        val sb = fake<SocialApi> { n, _ ->
            when (n) {
                "timeline" -> TimelinePage(listOf(post("pb1", "2026-11-15T10:00:00Z")), null)
                "conversations" -> listOf(Conversation(id = "cb", kind = "group", lastActivityAt = "2026-11-18T10:00:00Z"))
                "badge" -> Badge(4, 4, 0)
                "post" -> post("pb1", "2026-11-15T10:00:00Z")
                else -> null
            }
        }
        val kin = fake<KinApi> { _, _ -> null }
        val index = AccountIndex()
        val routes = Routes({ listOf(R("A", kin, sa, disco("A")), R("B", kin, sb, disco("B"))) }, index)
        val api = RoutingSocialApi(routes, sa)
        val page = api.timeline()
        assertEquals(listOf("pa1", "pb1", "pa2"), page.posts.map { it.id })
        assertEquals(mapOf("A" to "na"), Merging.decodeCursor(page.nextBefore))
        assertEquals("B", index.get("pb1"))
        assertEquals("pb1", api.post("pb1").id)
        assertEquals(listOf("cb", "ca"), api.conversations().map { it.id })
        assertEquals(Badge(7, 6, 1), api.badge())
    }

    @Test
    fun indexIsBoundedAndForgetsAnAccount() {
        val index = AccountIndex(capacity = 2)
        var changes = 0
        index.onChange = { changes++ }
        index.put("a", "A"); index.put("b", "A"); index.put("c", "B")
        assertNull(index.get("a"))
        assertEquals("B", index.get("c"))
        index.forget("B")
        assertNull(index.get("c"))
        assertTrue(changes >= 3)
    }
}
