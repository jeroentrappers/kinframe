package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Placement
import app.kinframe.core.leaving.AccountDeletion
import app.kinframe.core.leaving.CommentsChoice
import app.kinframe.core.leaving.DeletionPhotos
import app.kinframe.core.leaving.HttpLeavingApi
import app.kinframe.core.leaving.KeepAnswer
import app.kinframe.core.leaving.KeepRequestAnswer
import app.kinframe.core.leaving.KeepRequestCreate
import app.kinframe.core.leaving.LeaveFeedRequest
import app.kinframe.core.leaving.LeaveFrameRequest
import app.kinframe.core.leaving.MemoryPreferencesUpdate
import app.kinframe.core.leaving.PhotosChoice
import app.kinframe.core.servers.HttpRemoteApi
import app.kinframe.core.servers.Merging
import app.kinframe.core.servers.RemoteOnEnd
import app.kinframe.core.servers.RemoteSubscriptionCreate
import app.kinframe.core.social.Conversation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LeavingApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpLeavingApi
    private lateinit var remote: HttpRemoteApi

    @Before
    fun setUp() {
        server.start()
        api = HttpLeavingApi(OkHttpClient(), server.url("/v1"), tokens)
        remote = HttpRemoteApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private val mockwebserver3.RecordedRequest.pq: String get() = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    private fun mockwebserver3.RecordedRequest.json() = KinJson.parseToJsonElement(body!!.utf8()).jsonObject

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    @Test
    fun leavePreviewAndLeaveFeedWithChoice() = runBlocking {
        server.enqueue(json("""{"scope":"feed","feed_id":"f1","name":"Janssens","member":true,"photos":214,"comments":38,
            "frames":[{"frame_id":"fr1","frame_name":"Oma","role":"member","can_leave":true,"suggested":true,"direct_photos":3},
                      {"frame_id":"fr2","frame_name":"Kitchen","role":"owner","can_leave":false,"suggested":false}]}"""))
        val p = api.leavePreview(feedId = "f1")
        assertEquals("/v1/me/leave-preview?feed_id=f1", server.takeRequest().pq)
        assertEquals(214, p.photos)
        assertEquals(listOf(true, false), p.frames.map { it.suggested })
        assertFalse(p.frames[1].canLeave)

        server.enqueue(json("""{"taking_id":"t1","photos_taken_away":214,"comments_deleted":38,"until":"2026-12-19T10:00:00Z",
            "frames":[{"frame_id":"fr1","frame_name":"Oma","photos":200}],"remote_frames":1,"left_frame_ids":["fr1"]}"""))
        val r = api.leaveFeed("f1", LeaveFeedRequest(PhotosChoice.TAKE, CommentsChoice.DELETE, listOf("fr1")))!!
        val req = server.takeRequest()
        assertEquals("/v1/feeds/f1/leave", req.pq)
        val body = req.json()
        assertEquals("take", body["photos"]!!.jsonPrimitive.content)
        assertEquals("delete", body["comments"]!!.jsonPrimitive.content)
        assertEquals("fr1", body["leave_frame_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("t1", r.takingId)
        assertEquals(1, r.remoteFrames)
        assertEquals(listOf("fr1"), r.leftFrameIds)
    }

    @Test
    fun leaveFrameKeepAnswers204() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertNull(api.leaveFrame("fr1", LeaveFrameRequest(PhotosChoice.KEEP)))
        val req = server.takeRequest()
        assertEquals("/v1/frames/fr1/leave", req.pq)
        assertEquals("keep", req.json()["photos"]!!.jsonPrimitive.content)
    }

    @Test
    fun formerPlacesAndTakeAway() = runBlocking {
        server.enqueue(json("""{"feeds":[{"feed_id":"f1","name":"Janssens","photos":12}],"frames":[{"frame_id":"fr9","name":"Oma","photos":3}]}"""))
        val fp = api.formerPlaces()
        assertEquals("Janssens", fp.feeds.single().name)
        assertEquals(3, fp.frames.single().photos)
        server.takeRequest()

        server.enqueue(json("""{"taking_id":"t2","photos_taken_away":12,"comments_deleted":0,"frames":[]}"""))
        api.takeAwayFromFeed("f1", app.kinframe.core.leaving.TakeAwayRequest(CommentsChoice.KEEP), "k1")
        val req = server.takeRequest()
        assertEquals("/v1/feeds/f1/take-away", req.pq)
        assertEquals("k1", req.headers["Idempotency-Key"])
    }

    @Test
    fun takenAwayGroupsKeepRequestsAndAnswers() = runBlocking {
        server.enqueue(json("""{"groups":[{"taking_id":"t1","by_name":"Tom","taken_at":"2026-11-19T10:00:00Z","until":"2026-12-19T10:00:00Z",
            "count":214,"can_ask":false,"cannot_ask_reason":"blocked"}]}"""))
        val g = api.frameTakenAway("fr1").groups.single()
        assertEquals("/v1/frames/fr1/taken-away", server.takeRequest().pq)
        assertEquals("blocked", g.cannotAskReason)

        server.enqueue(json("""{"placements":[{"id":"p1","item":{"id":"i1"},"taken_away":{"taking_id":"t1","by_name":"Tom","at":"2026-11-19T10:00:00Z","until":"2026-12-19T10:00:00Z"}}],
            "next_cursor":null,"counts":{"taken_away":1}}"""))
        val page = api.takenAwayPlacements("fr1", "t1")
        assertEquals("/v1/frames/fr1/placements?visibility=taken_away&taking_id=t1&limit=120", server.takeRequest().pq)
        assertEquals("Tom", page.placements.single().takenAway!!.byName)
        assertEquals(1, page.counts.takenAway)

        server.enqueue(json("""{"id":"k1","status":"open","scope":"frame","frame_id":"fr1","taking_id":"t1","asked_by_name":"Lies",
            "asked_at":"2026-11-20T10:00:00Z","expires_at":"2026-12-19T10:00:00Z","item_count":12}""", 201))
        val k = api.createKeepRequest(KeepRequestCreate("t1", frameId = "fr1", itemIds = listOf("i1")), "idem")
        val kr = server.takeRequest()
        assertEquals("/v1/keep-requests", kr.pq)
        assertNull(kr.json()["feed_id"])
        assertTrue(k.isOpen)

        server.enqueue(json("""{"id":"k1","status":"partly_allowed","scope":"frame","taking_id":"t1","asked_by_name":"Lies",
            "asked_at":"2026-11-20T10:00:00Z","expires_at":"2026-12-19T10:00:00Z","item_count":12,"allowed_count":1}"""))
        val a = api.answerKeepRequest("k1", KeepRequestAnswer(KeepAnswer.SOME, listOf("i1")))
        val ar = server.takeRequest()
        assertEquals("/v1/keep-requests/k1/answer", ar.pq)
        assertEquals("some", ar.json()["allow"]!!.jsonPrimitive.content)
        assertEquals(1, a.allowedCount)
    }

    @Test
    fun memoriesAndAccountDeletion() = runBlocking {
        server.enqueue(json("""{"hidden_people":[{"user_id":"u1","display_name":"Tom"}],"hidden_memories":["p1"]}"""))
        val m = api.updateMemories(MemoryPreferencesUpdate(hiddenMemories = listOf("p1", "p2")))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertNull(req.json()["hidden_people"])
        assertEquals("Tom", m.hiddenPeople.single().displayName)

        server.enqueue(MockResponse.Builder().code(204).build())
        api.deleteAccount(AccountDeletion(DeletionPhotos.LEAVE))
        val d = server.takeRequest()
        assertEquals("DELETE", d.method)
        assertEquals("leave", d.json()["photos"]!!.jsonPrimitive.content)
    }

    @Test
    fun keepRequestRefusedWhenBlocked() = runBlocking {
        server.enqueue(json("""{"type":"about:blank","title":"Forbidden","status":403,"code":"forbidden"}""", 403))
        try {
            api.createKeepRequest(KeepRequestCreate("t1", frameId = "fr1"), "idem"); fail()
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun remoteSubscriptions() = runBlocking {
        server.enqueue(json("""{"server":{"server_name":"Janssens","server_url":"https://kinframe.janssens.be/","host":"kinframe.janssens.be"},
            "feed_name":"Janssens","frame_count":3,"this_server_name":"appmire","this_server_host":"kinframe.appmire.be"}"""))
        val p = remote.previewInvite("https://kinframe.janssens.be/fi/abc")
        assertEquals("/v1/remote-invites:preview", server.takeRequest().pq)
        assertEquals("kinframe.janssens.be", p.server.host)

        server.enqueue(json("""{"id":"rs1","frame_id":"fr1","status":"active","feed_name":"Janssens","history":"last_n","history_count":50,
            "on_end":"keep","created_at":"2026-11-19T10:00:00Z","server":{"server_name":"Janssens","server_url":"https://kinframe.janssens.be/"}}""", 201))
        val s = remote.createRemoteSubscription("fr1", RemoteSubscriptionCreate("https://kinframe.janssens.be/fi/abc", "last_n", 50, onEnd = RemoteOnEnd.KEEP), "idem")
        val req = server.takeRequest()
        assertEquals("last_n", req.json()["history"]!!.jsonPrimitive.content)
        assertTrue(s.isLive)

        server.enqueue(MockResponse.Builder().code(204).build())
        remote.deleteRemoteSubscription("fr1", "rs1", "remove")
        assertEquals("/v1/frames/fr1/remote-subscriptions/rs1?placements=remove", server.takeRequest().pq)
    }

    @Test
    fun newFieldsOnSharedModels() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"kinframe","server_id":"s1","server_name":"Janssens","base_url":"https://k.example/","leaving":true,"remote_families":true}""")
        assertEquals("s1", d.serverId)
        assertTrue(d.leaving && d.remoteFamilies)
        val c = KinJson.decodeFromString(Conversation.serializer(), """{"id":"c1","kind":"direct","read_only":true,"read_only_reason":"no_longer_contacts"}""")
        assertEquals("no_longer_contacts", c.readOnlyReason)
        val pl = KinJson.decodeFromString(Placement.serializer(), """{"id":"p","item":{"id":"i","remote_sender":{"name":"Lies","server_name":"Janssens","server_url":"https://a/"},"former_sender_name":"Tom"},
            "origin":"feed","origin_remote":{"remote_subscription_id":"rs1","server_name":"Janssens","server_url":"https://a/","feed_name":"Janssens"}}""")
        assertEquals("Lies", pl.item.remoteSender!!.name)
        assertEquals("rs1", pl.originRemote!!.remoteSubscriptionId)
    }

    @Test
    fun mergingCursorsAndInterleave() {
        val c = Merging.encodeCursor(mapOf("a" to "x1", "b" to null))!!
        assertEquals(mapOf("a" to "x1"), Merging.decodeCursor(c))
        assertNull(Merging.encodeCursor(mapOf("a" to null)))
        assertNull(Merging.decodeCursor(null))
        val merged = Merging.interleave(listOf(listOf("2026-11-19T10:00:00Z", "2026-11-17T10:00:00Z"), listOf("2026-11-18T10:00:00+01:00")), { it })
        assertEquals(listOf("2026-11-19T10:00:00Z", "2026-11-18T10:00:00+01:00", "2026-11-17T10:00:00Z"), merged)
        assertTrue(Merging.sameServer("https://k.example/", "https://K.example"))
        assertFalse(Merging.sameServer("https://k.example/", "https://k.example:8443/"))
    }
}
