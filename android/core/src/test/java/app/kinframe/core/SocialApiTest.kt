package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Item
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.SendCreate
import app.kinframe.core.api.SendItemCreate
import app.kinframe.core.social.CommentCreate
import app.kinframe.core.social.ConversationKind
import app.kinframe.core.social.HttpSocialApi
import app.kinframe.core.social.Mention
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.PersonReaction
import app.kinframe.core.social.ReactionCount
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.ReactionTarget
import app.kinframe.core.social.ReminderLevel
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.SystemMessageType
import app.kinframe.core.social.VoiceCreate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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

class SocialApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpSocialApi

    @Before
    fun setUp() {
        server.start()
        api = HttpSocialApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private val mockwebserver3.RecordedRequest.pq: String get() = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val thread = """{"post_id":"p1","state":"open","comment_count":3,"unread_count":1,"unread_mentions":0,"muted":false,"can_comment":true,
        "latest_comments":[{"id":"c2","post_id":"p1","kind":"person","author":{"user_id":"u2","display_name":"Anna"},"body":"Look @Ben","mentions":[{"user_id":"u3","start":5,"length":4}],"created_at":"2026-11-19T10:00:00Z"}],
        "reactions":{"total":4,"counts":[{"emoji":"love","count":3},{"emoji":"laugh","count":1}],"people":[{"person":{"user_id":"u2","display_name":"Anna"},"emoji":"love"}],
        "frames":[{"frame_id":"f1","frame_name":"Oma","reaction":"love"}],"mine":"laugh"}}"""

    @Test fun timelineParsesPostsThreadsAndCursor() = runBlocking {
        server.enqueue(json("""{"posts":[{"id":"p1","kind":"feed","feed_id":"fd","feed_name":"Peeters","poster":{"user_id":"u1","display_name":"Clara"},
            "created_at":"2026-11-19T09:00:00Z","items":[{"id":"i1","kind":"photo","caption":"Beach"}],"prompt":{"id":"pr","title":"Your view"},
            "seen":{"frames":4,"seen":3},"thread":$thread,"future_field":1}],"next_before":"cur2"}"""))
        val page = api.timeline(before = "cur1", feedId = "fd")
        val req = server.takeRequest()
        assertEquals("/v1/timeline?before=cur1&limit=20&feed_id=fd", req.pq)
        assertEquals("Bearer t", req.headers["Authorization"])
        assertEquals("cur2", page.nextBefore)
        val p = page.posts.single()
        assertEquals("Beach", p.caption)
        assertEquals("Your view", p.prompt?.title)
        assertEquals(3, p.thread.commentCount)
        assertEquals("laugh", p.thread.reactions.mine)
        assertEquals("Oma", p.thread.reactions.frames.single().frameName)
        assertEquals(Mention("u3", 5, 4), p.thread.latestComments.single().mentions.single())
    }

    @Test fun createCommentSendsReplyQuoteMentionsAndKey() = runBlocking {
        server.enqueue(json("""{"id":"c9","post_id":"p1","kind":"person","parent_id":"c1","body":"@Ben yes","created_at":"x"}""", 201))
        val c = api.createComment("p1", CommentCreate("@Ben yes", parentId = "c1", quotedUserId = "u3", mentions = listOf(Mention("u3", 0, 4))), "k1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/posts/p1/comments", req.pq)
        assertEquals("k1", req.headers["Idempotency-Key"])
        val body = KinJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("c1", body["parent_id"]!!.jsonPrimitive.content)
        assertEquals("u3", body["quoted_user_id"]!!.jsonPrimitive.content)
        assertEquals("""[{"user_id":"u3","start":0,"length":4}]""", body["mentions"].toString())
        assertEquals("c1", c.parentId)
    }

    @Test fun removingAReactionSendsAnExplicitNull() = runBlocking {
        server.enqueue(json("""{"total":0,"counts":[],"people":[],"frames":[]}"""))
        val s = api.setReaction(ReactionTarget.POST_ITEM, "i1", null, postId = "p1")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/reactions/post_item/i1?post_id=p1", req.pq)
        assertEquals(JsonNull, KinJson.parseToJsonElement(req.body!!.utf8()).jsonObject["emoji"])
        assertNull(s.mine)
    }

    @Test fun conversationsParseMembersLastMessageAndSystemMessages() = runBlocking {
        server.enqueue(json("""[{"id":"cv1","kind":"group","name":"Cousins","member_count":3,"unread_count":2,"last_activity_at":"2026-11-19T10:00:00Z","muted":true,"archived":false,
            "read_receipts":true,"my_role":"admin","members":[{"person":{"user_id":"u1","display_name":"Ben"},"role":"admin","last_read_message_id":"m5"}],
            "last_message":{"id":"m6","conversation_id":"cv1","kind":"system","created_at":"2026-11-19T10:00:00Z","items":[],"deleted":false,
              "reactions":{"total":0,"counts":[],"people":[],"frames":[]},"system":{"type":"put_on_frame","person":{"user_id":"u1","display_name":"Ben"},"item_count":12,"frame_names":["Oma"],"feed_names":["Peeters"]}}}]"""))
        val c = api.conversations().single()
        assertEquals("/v1/conversations", server.takeRequest().pq)
        assertTrue(c.isGroup && c.isAdmin && c.muted && c.readReceipts)
        assertEquals("m5", c.members.single().lastReadMessageId)
        val sys = c.lastMessage!!.system!!
        assertEquals(SystemMessageType.PUT_ON_FRAME, sys.type)
        assertEquals(12, sys.itemCount)
        assertEquals(listOf("Oma"), sys.frameNames)
    }

    @Test fun messagesPageWithCursorsAndVoiceMessages() = runBlocking {
        server.enqueue(json("""{"messages":[{"id":"m1","conversation_id":"cv","kind":"person","created_at":"x","items":[],"deleted":false,
            "reactions":{"total":0,"counts":[],"people":[],"frames":[]},"voice":{"item_id":"v1","status":"ready","duration_ms":4200,"waveform":[1,50,100]}}],
            "has_more_before":true,"has_more_after":false}"""))
        val page = api.messages("cv", before = "m9", limit = 30)
        assertEquals("/v1/conversations/cv/messages?before=m9&limit=30", server.takeRequest().pq)
        assertTrue(page.hasMoreBefore)
        assertEquals(listOf(1, 50, 100), page.messages.single().voice!!.waveform)

        server.enqueue(json("""{"id":"m2","conversation_id":"cv","kind":"person","created_at":"x","items":[],"deleted":false,"client_id":"cl","reactions":{"total":0,"counts":[],"people":[],"frames":[]}}""", 201))
        val m = api.sendMessage("cv", MessageCreate(clientId = "cl", voice = VoiceCreate("up1", 4200), replyToId = "m1"), "cl")
        val req = server.takeRequest()
        val body = KinJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("up1", body["voice"]!!.jsonObject["upload_id"]!!.jsonPrimitive.content)
        assertEquals("m1", body["reply_to_id"]!!.jsonPrimitive.content)
        assertFalse("absent fields are left out", body.containsKey("items") || body.containsKey("body"))
        assertEquals("cl", m.clientId)
    }

    @Test fun mediaUrlFollowsTheSignedRedirectWithoutLoadingIt() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/v1/media/signed/abc").build())
        val url = api.mediaUrl("i1")
        assertEquals("/v1/items/i1/media/master", server.takeRequest().pq)
        assertEquals(server.url("/v1/media/signed/abc").toString(), url)
        assertEquals(1, server.requestCount)
    }

    @Test fun reminderButtonsPatchPreferences() = runBlocking {
        server.enqueue(json("""{"level":"fewer","frame_requests":true,"weekly_summary":true,"birthday_reminders":true,"quiet_hours":{"start":"21:00","end":"09:00"},"email_digest":"off"}"""))
        val p = api.updateReminderPreferences(ReminderPreferencesUpdate(level = ReminderLevel.FEWER))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("""{"level":"fewer"}""", req.body!!.utf8())
        assertEquals(ReminderLevel.FEWER, p.level)
    }

    @Test fun stubbedOperationsSurfaceAs501() = runBlocking {
        server.enqueue(json("""{"type":"about:blank","title":"Not implemented","status":501,"code":"feature_disabled"}""", 501))
        try {
            api.badge(); fail("expected 501")
        } catch (e: ApiException) {
            assertEquals(501, e.status)
            assertEquals("feature_disabled", e.code)
        }
    }

    @Test fun createDirectConversation() = runBlocking {
        server.enqueue(json("""{"id":"cv","kind":"direct","name":"Anna","member_count":2,"unread_count":0,"last_activity_at":"x","muted":false,"archived":false}"""))
        val c = api.createConversation(app.kinframe.core.social.ConversationCreate(ConversationKind.DIRECT, listOf("u2")), "k")
        val body = server.takeRequest().body!!.utf8()
        assertEquals("""{"kind":"direct","member_ids":["u2"]}""", body)
        assertTrue(c.isDirect)
    }

    @Test fun reactionSummaryWithMineMovesTheCallersReaction() {
        val s = ReactionSummary(total = 3, counts = listOf(ReactionCount("love", 2), ReactionCount("laugh", 1)), mine = "laugh")
        val changed = s.withMine(PersonReaction.LOVE)
        assertEquals(3, changed.total)
        assertEquals(listOf(ReactionCount("love", 3)), changed.counts)
        val removed = changed.withMine(null)
        assertEquals(2, removed.total)
        assertNull(removed.mine)
        assertEquals(1, ReactionSummary().withMine("pray").withMine("pray").total)
    }

    @Test fun sharedModelsReadTheNewFieldsAndOldServersStillParse() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"k","social":true,"chats":true,"voice_messages":true,"limits":{"max_comment_chars":1500}}""")
        assertTrue(d.social && d.chats && d.voiceMessages && !d.engagement)
        assertEquals(1500, d.limits!!.maxCommentChars)
        assertEquals(24, d.limits!!.messageEditHours)
        val old = KinJson.decodeFromString(Feed.serializer(), """{"id":"f","name":"P","role":"poster"}""")
        assertTrue(old.canPost)
        assertNull(old.streak)
        val member = KinJson.decodeFromString(Feed.serializer(), """{"id":"f","name":"P","role":"member","mute":"chat","circle_count":2,"streak":{"current_weeks":3,"week_photos":1,"goal":3,"met":false},"conversation_id":"cv"}""")
        assertFalse(member.canPost)
        assertEquals(FeedRole.MEMBER, member.role)
        assertEquals(3, member.streak!!.currentWeeks)
        val send = KinJson.encodeToString(SendCreate.serializer(), SendCreate.of(emptyList(), listOf("f"), listOf(SendItemCreate("u")), listOf("c"), "pr"))
        assertTrue(send.contains("\"circle_ids\":[\"c\"]") && send.contains("\"prompt_id\":\"pr\""))
        val plain = KinJson.encodeToString(SendCreate.serializer(), SendCreate.of(listOf("fr"), emptyList(), listOf(SendItemCreate("u"))))
        assertFalse(plain.contains("circle_ids") || plain.contains("prompt_id"))
    }

    @Test fun itemDisplayUrlIsForLargeImagesWithTheThumbnailAsFallback() {
        val item = KinJson.decodeFromString(Item.serializer(), """{"id":"i1","kind":"photo","thumb_url":"https://x/t256","display_url":"https://x/d"}""")
        assertEquals("https://x/d", item.displayUrl)
        assertEquals("https://x/d", item.largeImageUrl)
        assertEquals("https://x/t256", item.thumbUrl)
        // Items from before the display rendition (or older servers): the square thumbnail.
        val old = KinJson.decodeFromString(Item.serializer(), """{"id":"i2","kind":"photo","thumb_url":"https://x/t256"}""")
        assertEquals(null, old.displayUrl)
        assertEquals("https://x/t256", old.largeImageUrl)
    }
}
