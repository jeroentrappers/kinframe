package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.FeedCodes
import app.kinframe.core.api.FeedCreate
import app.kinframe.core.api.FeedInviteAccept
import app.kinframe.core.api.FeedInviteCreate
import app.kinframe.core.api.FeedInvitePurpose
import app.kinframe.core.api.FeedInviteStatus
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementOrigin
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendCreate
import app.kinframe.core.api.SendItemCreate
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.api.SubscriptionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

class FeedApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpKinApi

    @Before
    fun setUp() {
        server.start()
        api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val subJson = """{"id":"s1","status":"pending","history":"last_n","history_count":50,
        "feed":{"id":"fd1","name":"Peeters kids","admin_names":["Anna"]},"frame":{"id":"f1","name":"Oma's frame","online":true},
        "requested_by_name":"Ben","backfill":{"state":"running","placed":10,"total":50},"can_manage":true,"created_at":"2026-11-18T10:00:00Z"}"""

    @Test
    fun parsesFeedsWithSettingsAndRoles() = runBlocking {
        server.enqueue(json("""[{"id":"fd1","name":"Peeters kids","role":"poster","member_count":3,"subscription_count":5,
            "posters_can_invite":true,"future":1},{"id":"fd2","name":"Opa","role":"admin","member_count":1,"subscription_count":0}]"""))
        val feeds = api.feeds()
        assertEquals(listOf("Peeters kids", "Opa"), feeds.map { it.name })
        assertFalse(feeds[0].isAdmin)
        assertTrue("posters_can_invite lets posters invite", feeds[0].canInvite)
        assertTrue(feeds[1].isAdmin && feeds[1].canInvite)
        assertTrue("default: invites need approval", feeds[1].inviteRequiresApproval)
        assertEquals("/v1/feeds", server.takeRequest().url.encodedPath)
    }

    @Test
    fun createFeedSendsNameAndSettingsWithIdempotencyKey() = runBlocking {
        server.enqueue(json("""{"id":"fd1","name":"Peeters kids","role":"owner","member_count":1,"subscription_count":0}""", 201))
        val f = api.createFeed(FeedCreate("Peeters kids"), "k1")
        assertTrue(f.isOwner)
        val r = server.takeRequest()
        assertEquals("k1", r.headers["Idempotency-Key"])
        val body = KinJson.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("Peeters kids", body["name"]!!.let { (it as JsonPrimitive).content })
        assertEquals("false", body["posters_can_invite"].toString())
        assertEquals("true", body["invite_requires_approval"].toString())
    }

    @Test
    fun invitesAlwaysCarryMaxUsesAndPurpose() = runBlocking {
        server.enqueue(json("""{"id":"i1","purpose":"subscribe","token":"tok","code":"KF7QM2XA","url":"https://k/fi/tok","expires_at":"2026-11-25T10:00:00Z"}""", 201))
        val inv = api.createFeedInvite("fd1", FeedInviteCreate(FeedInvitePurpose.SUBSCRIBE), "k2")
        assertEquals("KF7Q-M2XA", FeedCodes.format(inv.code))
        val r = server.takeRequest()
        assertEquals("/v1/feeds/fd1/invites", r.url.encodedPath)
        assertEquals("""{"purpose":"subscribe","max_uses":null,"expires_in_hours":168}""", r.body!!.utf8())
    }

    @Test
    fun lookupAndAcceptSubscribeInvite() = runBlocking {
        server.enqueue(json("""{"token":"tok","purpose":"subscribe","feed_name":"Peeters kids","invited_by_name":"Anna","requires_approval":false,
            "frames":[{"frame_id":"f1","frame_name":"Opa","subscription_status":null},{"frame_id":"f2","frame_name":"Oma","subscription_status":"active"}]}"""))
        val p = api.lookupFeedInvite("KF7QM2XA")
        assertEquals("tok", p.token)
        assertNull(p.frames[0].subscriptionStatus)
        assertEquals(SubscriptionStatus.ACTIVE, p.frames[1].subscriptionStatus)
        val lookup = server.takeRequest()
        assertEquals("/v1/feed-invites:lookup", lookup.url.encodedPath)
        assertEquals("""{"code":"KF7QM2XA"}""", lookup.body!!.utf8())

        server.enqueue(json("""{"status":"subscribed","feed":{"id":"fd1","name":"Peeters kids","role":"poster","member_count":2,"subscription_count":1},
            "subscriptions":[$subJson]}"""))
        val r = api.acceptFeedInvite("tok", FeedInviteAccept(listOf("f1"), SubscriptionHistory.NONE), "k3")
        assertEquals(FeedInviteStatus.SUBSCRIBED, r.status)
        assertEquals("Peeters kids", r.subscriptions.single().feed.name)
        val accept = server.takeRequest()
        assertEquals("/v1/feed-invites/tok/accept", accept.url.encodedPath)
        assertEquals("""{"frame_ids":["f1"],"history":"none"}""", accept.body!!.utf8())
    }

    @Test
    fun subscriptionLifecycleCalls() = runBlocking {
        server.enqueue(json(subJson, 201))
        val s = api.requestSubscription("fd1", SubscriptionRequest("f1", SubscriptionHistory.LAST_N, 50), "k4")
        assertEquals(50, s.historyCount)
        assertTrue(s.backfill!!.running)
        assertTrue(s.isLive && s.canManage && !s.canDecide)
        assertEquals("""{"frame_id":"f1","history":"last_n","history_count":50,"birthdays":true}""", server.takeRequest().body!!.utf8())

        listOf("approve", "reject", "pause", "resume").forEach { server.enqueue(json(subJson)) }
        api.approveSubscription("s1"); api.rejectSubscription("s1"); api.pauseSubscription("s1"); api.resumeSubscription("s1")
        assertEquals(listOf("approve", "reject", "pause", "resume").map { "/v1/subscriptions/s1/$it" }, (1..4).map { server.takeRequest().url.encodedPath })

        server.enqueue(json(subJson))
        api.updateSubscription("s1", SubscriptionUpdate(SubscriptionHistory.ALL))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"history":"all"}""", patch.body!!.utf8())

        server.enqueue(json("[$subJson]"))
        api.frameSubscriptions("f1", SubscriptionStatus.ACTIVE)
        val list = server.takeRequest()
        assertEquals("/v1/frames/f1/subscriptions", list.url.encodedPath)
        assertEquals("active", list.url.queryParameter("status"))

        server.enqueue(json("""{"type":"x","title":"t","status":409,"code":"already_subscribed"}""", 409))
        try {
            api.requestSubscription("fd1", SubscriptionRequest("f1"), "k5"); fail()
        } catch (e: ApiException) {
            assertEquals(ProblemCode.ALREADY_SUBSCRIBED, e.code)
        }
    }

    @Test
    fun postsWithAndWithoutReceipts() = runBlocking {
        server.enqueue(json("""{"posts":[
            {"id":"p1","feed_id":"fd1","poster":{"user_id":"u1","display_name":"Ben"},"created_at":"2026-11-18T10:00:00Z","items":[{"id":"i1"}],
             "receipts":{"frames":5,"delivered":5,"seen":4,"paused":1},"reactions":[{"frame_id":"f1","frame_name":"Oma","item_id":"i1","placement_id":"pl1","reaction":"love"}]},
            {"id":"p2","feed_id":"fd1","poster":{"user_id":"u2","display_name":"Carla"},"created_at":"2026-11-17T10:00:00Z","items":[],"receipts":null,"reactions":null}
            ],"next_cursor":null}"""))
        val page = api.feedPosts("fd1", limit = 20, posterId = "u1")
        assertEquals(4, page.posts[0].receipts!!.seen)
        assertEquals("love", page.posts[0].reactions!!.single().reaction)
        assertNull(page.posts[1].receipts)
        assertNull(page.posts[1].reactions)
        val r = server.takeRequest()
        assertEquals("u1", r.url.queryParameter("poster_id"))

        server.enqueue(MockResponse.Builder().code(204).build())
        api.removeFeedPostItem("fd1", "p1", "i1")
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/v1/feeds/fd1/posts/p1/items/i1", del.url.encodedPath)
    }

    @Test
    fun membersLeaveAndRemoveWithPosts() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        api.removeFeedMember("fd1", "u2", removePosts = true)
        assertEquals("true", server.takeRequest().url.queryParameter("remove_posts"))
        server.enqueue(MockResponse.Builder().code(204).build())
        api.leaveFeed("fd1", removeMyPosts = false)
        assertEquals("""{"remove_my_posts":false}""", server.takeRequest().body!!.utf8())
        server.enqueue(json("""{"user_id":"u2","display_name":"Ben","role":"admin"}"""))
        assertEquals("admin", api.updateFeedMember("fd1", "u2", "admin").role)
        assertEquals("""{"role":"admin"}""", server.takeRequest().body!!.utf8())
        server.enqueue(json("""{"id":"fd1","name":"Kids","role":"owner"}"""))
        api.updateFeed("fd1", buildJsonObject { put("name", JsonPrimitive("Kids")) })
        assertEquals("PATCH", server.takeRequest().method)
    }

    @Test
    fun sendCreateLeavesFeedIdsOutWhenEmptyAndNeedsARecipient() {
        val item = SendItemCreate("u1")
        assertEquals("""{"frame_ids":["f1"],"items":[{"upload_id":"u1"}]}""",
            KinJson.encodeToString(SendCreate.serializer(), SendCreate.of(listOf("f1"), emptyList(), listOf(item))))
        assertEquals("""{"frame_ids":[],"items":[{"upload_id":"u1"}],"feed_ids":["fd1"]}""",
            KinJson.encodeToString(SendCreate.serializer(), SendCreate.of(emptyList(), listOf("fd1"), listOf(item))))
        try {
            SendCreate.of(emptyList(), emptyList(), listOf(item)); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun forwardToFeeds() = runBlocking {
        server.enqueue(json("""{"id":"s1","created_at":"2026-11-18T10:00:00Z"}""", 202))
        api.forwardSend("s1", emptyList(), listOf("fd1"))
        assertEquals("""{"frame_ids":[],"feed_ids":["fd1"]}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun sendListsItsFeedsWithReceipts() {
        val s = KinJson.decodeFromString(Send.serializer(), """{"id":"s1","created_at":"2026-11-18T10:00:00Z","frames":[],
            "feeds":[{"feed_id":"fd1","feed_name":"Peeters kids","post_id":"p1","summary":{"frames":5,"delivered":5,"seen":4}}]}""")
        assertEquals("Peeters kids", s.feeds.single().feedName)
        assertEquals(4, s.feeds.single().summary!!.seen)
    }

    @Test
    fun placementFeedFieldsAndCredit() {
        val feed = KinJson.decodeFromString(Placement.serializer(), """{"id":"p1","item":{"id":"i1","sender_name":"Anna"},
            "origin":"feed","origin_feed_id":"fd1","origin_feed_name":"Peeters kids","feed_ids":["fd1","fd2"],"backfill":true}""")
        assertEquals(PlacementOrigin.FEED, feed.origin)
        assertEquals(listOf("fd1", "fd2"), feed.feedIds)
        assertTrue(feed.backfill)
        assertEquals("Peeters kids", feed.creditFeedName)
        // A direct send that is also held by a feed is credited as a direct send.
        val direct = KinJson.decodeFromString(Placement.serializer(), """{"id":"p2","item":{"id":"i2"},"origin":"send","origin_feed_name":"X","feed_ids":["fd1"]}""")
        assertNull(direct.creditFeedName)
        // Older servers: no feed fields at all.
        val old = KinJson.decodeFromString(Placement.serializer(), """{"id":"p3","item":{"id":"i3"}}""")
        assertEquals(PlacementOrigin.SEND, old.origin)
        assertFalse(old.backfill)
        assertTrue(old.feedIds.isEmpty())
    }

    @Test
    fun discoveryAndSettingsFeedFields() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"k","feeds":true,"limits":{"max_feeds_per_user":3}}""")
        assertTrue(d.feeds)
        assertEquals(3, d.limits!!.maxFeedsPerUser)
        assertEquals(5000, d.limits!!.maxFeedBackfillItems)
        assertFalse(KinJson.decodeFromString(Discovery.serializer(), """{"name":"k"}""").feeds)
        val s = FrameSettings.parse(KinJson.parseToJsonElement("""{"version":2,"slideshow":{"feed_id":"fd1","album_id":null}}""").jsonObject)
        assertEquals("fd1", s.slideshow.feedId)
        assertNull(s.slideshow.albumId)
    }

    @Test
    fun heartbeatFeatures() {
        val hb = app.kinframe.core.api.Heartbeat(appVersion = "0.7.0", uptimeS = 5, features = app.kinframe.core.api.FrameFeatures.SUPPORTED)
        assertTrue(KinJson.encodeToString(app.kinframe.core.api.Heartbeat.serializer(), hb).contains(""""features":["feed_backfill","birthdays","comments","photo_requests","prompts"]"""))
        // Without features the field is left out, as before.
        assertFalse(KinJson.encodeToString(app.kinframe.core.api.Heartbeat.serializer(), hb.copy(features = null)).contains("features"))
    }

    @Test
    fun heartbeatTellsThePlayBuildApart() {
        val f = app.kinframe.core.api.FrameFeatures
        // The GitHub build sends exactly what it always sent; the Play build adds the informational "play".
        assertEquals(f.SUPPORTED, f.forDistribution("github"))
        assertEquals(f.SUPPORTED + "play", f.forDistribution("play"))
        val hb = app.kinframe.core.api.Heartbeat(appVersion = "0.7.0", uptimeS = 5, features = f.forDistribution("play"))
        assertTrue(KinJson.encodeToString(app.kinframe.core.api.Heartbeat.serializer(), hb).contains(""""features":["feed_backfill","birthdays","comments","photo_requests","prompts","play"]"""))
    }

    @Test
    fun feedCodesTellLettersFromFrameDigits() {
        assertEquals("KF7QM2XA", FeedCodes.normalize("kf7q-m2xa"))
        assertEquals("KF7QM2XA", FeedCodes.normalize(" KF7Q M2XA "))
        // Crockford decoding: O reads as 0, I and L as 1.
        assertEquals("K07Q1M1A", FeedCodes.normalize("KO7QIMLA"))
        assertNull("8 digits are a frame code", FeedCodes.normalize("12345678"))
        assertNull("too short", FeedCodes.normalize("KF7Q-M2X"))
        assertTrue(FeedCodes.looksLikeFeedCode("KF7"))
        assertFalse(FeedCodes.looksLikeFeedCode("1234 5678"))
        assertEquals("KF7Q-M2", FeedCodes.format("KF7QM2"))
        assertEquals("KF7", FeedCodes.format("KF7"))
        assertEquals(8, FeedCodes.chars("KF7QM2XA-EXTRA").length)
    }
}
