package app.kinframe.family

import android.app.NotificationManager
import android.content.Context
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Problem
import app.kinframe.core.social.Badge
import app.kinframe.core.social.Circle
import app.kinframe.core.social.Comment
import app.kinframe.core.social.CommentCreate
import app.kinframe.core.social.CommentPage
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.ConversationCreate
import app.kinframe.core.social.ConversationKind
import app.kinframe.core.social.ConversationMember
import app.kinframe.core.social.FeedPrompt
import app.kinframe.core.social.Mention
import app.kinframe.core.social.MentionText
import app.kinframe.core.social.Message
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.MessagePage
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.PostPrompt
import app.kinframe.core.social.PromptStatus
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.ReminderLevel
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.SocialPush
import app.kinframe.core.social.ThreadSummary
import app.kinframe.core.social.TimelinePage
import app.kinframe.core.social.TimelinePost
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.notify.SocialNotifications
import app.kinframe.family.send.OutboxChat
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.chats.ChatMedia
import app.kinframe.family.ui.chats.ChatsNotice
import app.kinframe.family.ui.chats.ChatsViewModel
import app.kinframe.family.ui.chats.ConversationViewModel
import app.kinframe.family.ui.chats.ReadReceipts
import app.kinframe.family.ui.chats.Waveform
import app.kinframe.family.ui.home.HomeViewModel
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.social.HomeEntry
import app.kinframe.family.ui.social.MessageRules
import app.kinframe.family.ui.social.PostViewModel
import app.kinframe.family.ui.social.ReplyTarget
import app.kinframe.family.ui.social.SocialProblems
import app.kinframe.family.ui.social.ThreadLogic
import app.kinframe.family.ui.social.TimelineLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/** Every SocialApi call fails unless a test overrides it. */
private val UnimplementedSocial: SocialApi = Proxy.newProxyInstance(SocialApi::class.java.classLoader, arrayOf(SocialApi::class.java)) { _, m, _ ->
    throw UnsupportedOperationException(m.name)
} as SocialApi

open class FakeSocial : SocialApi by UnimplementedSocial

private fun pref(id: String, name: String = id) = PersonRef(id, name)

private fun comment(id: String, parent: String? = null, by: String = "u2", body: String = "hi") =
    Comment(id, "p1", parentId = parent, author = pref(by), body = body, createdAt = "2026-11-19T10:00:0${id.last()}Z")

private fun timelinePost(id: String, prompt: PostPrompt? = null, thread: ThreadSummary = ThreadSummary(id)) =
    TimelinePost(id, poster = pref("u1", "Clara"), createdAt = "2026-11-19T09:00:00Z", feedId = "fd", feedName = "Peeters", prompt = prompt, thread = thread)

private fun message(id: String, by: String = "u2", clientId: String? = null, at: String = "2026-11-19T10:00:00Z") =
    Message(id, "cv", author = pref(by), body = "hello $id", clientId = clientId, createdAt = at)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SocialTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- logic

    @Test fun threadsNestRepliesUnderTheirTopComment() {
        val flat = listOf(comment("c1"), comment("c2", parent = "c1"), comment("c3"), comment("c4", parent = "c1"), comment("c5", parent = "gone"))
        val groups = ThreadLogic.nest(flat)
        assertEquals(listOf("c1", "c3", "c5"), groups.map { it.top.id })
        assertEquals(listOf("c2", "c4"), groups[0].replies.map { it.id })
        assertEquals(listOf("c3", "c5"), ThreadLogic.remove(flat, "c1").map { it.id })
    }

    @Test fun replyingToAReplyQuotesThePersonUnderTheSameTopComment() {
        val r = ReplyTarget.of(comment("c2", parent = "c1", by = "anna"), me = "me")
        assertEquals("c1", r.parentId)
        assertEquals("anna", r.quotedUserId)
        val top = ReplyTarget.of(comment("c1", by = "anna"), me = "me")
        assertEquals("c1", top.parentId)
        assertNull(top.quotedUserId)
    }

    @Test fun consecutivePromptAnswersGetOneHeader() {
        val p = PostPrompt("pr", "Your view")
        val entries = TimelineLogic.entries(listOf(timelinePost("a", p), timelinePost("b", p), timelinePost("c"), timelinePost("d", p)))
        assertEquals(HomeEntry.PromptHeader("pr", "Your view", 2), entries[0])
        assertEquals(5, entries.size)
        assertTrue("a single answer has no header", entries.last() is HomeEntry.Post)
    }

    @Test fun newCommentsUpdateTheTimelineSummary() {
        val t = ThreadSummary("p1", commentCount = 2, latestComments = listOf(comment("c1"), comment("c2")))
        val n = ThreadLogic.withComment(t, comment("c3"), mine = false)
        assertEquals(3, n.commentCount)
        assertEquals(listOf("c2", "c3"), n.latestComments.map { it.id })
        assertEquals(1, n.unreadCount)
        assertEquals(n, ThreadLogic.withComment(n, comment("c3"), mine = false).copy(latestComments = n.latestComments))
    }

    @Test fun messageRulesEditOnlyOwnRecentTextAndModeratorsDelete() {
        val now = java.time.Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val mine = message("m1", by = "me", at = "2026-11-19T10:00:00Z")
        assertTrue(MessageRules.canEdit(mine, "me", 24, now))
        assertFalse(MessageRules.canEdit(mine, "me", 1, now))
        assertFalse(MessageRules.canEdit(message("m2"), "me", 24, now))
        assertTrue(MessageRules.canDelete(message("m2"), "me", moderator = true))
        assertFalse(MessageRules.canDelete(message("m2"), "me", moderator = false))
        assertEquals("https://example.org/a", MessageRules.firstLink("see https://example.org/a."))
    }

    @Test fun readReceiptsListWhoReadThroughTheMessage() {
        val c = Conversation("cv", ConversationKind.GROUP, readReceipts = true, members = listOf(
            ConversationMember(pref("me"), lastReadMessageId = "m3"),
            ConversationMember(pref("anna"), lastReadMessageId = "m2"),
            ConversationMember(pref("ben"), lastReadMessageId = "m1"),
        ))
        assertEquals(listOf("anna"), ReadReceipts.seenBy(c, message("m2", by = "me"), "me").map { it.userId })
        assertTrue(ReadReceipts.seenBy(c.copy(readReceipts = false), message("m2", by = "me"), "me").isEmpty())
    }

    @Test fun waveformBarsKeepPeaks() {
        assertEquals(listOf(9, 7), Waveform.bars(listOf(1, 9, 3, 7), 2))
        assertEquals(0, Waveform.level(0))
        assertEquals(100, Waveform.level(32767))
    }

    @Test fun notImplementedServersSayNotYet() {
        assertEquals(R.string.social_problem_not_ready, SocialProblems.message(ApiException(501, Problem(code = "feature_disabled"))))
        assertEquals(R.string.chat_problem_cannot_message, SocialProblems.message(ApiException(403, Problem(code = "cannot_message"))))
    }

    // ---------------------------------------------------------------- live events and pushes

    @Test fun websocketEventsParse() {
        val c = LiveEvent.parse("""{"type":"comment.created","post_id":"p1","comment":{"id":"c1","post_id":"p1","kind":"person","created_at":"x"}}""")
        assertEquals("c1", (c as SocialLive.CommentCreated).comment.id)
        val r = LiveEvent.parse("""{"type":"reaction.updated","target_kind":"message","target_id":"m1","conversation_id":"cv","summary":{"total":1,"counts":[],"people":[],"frames":[]}}""")
        assertEquals("cv", (r as SocialLive.ReactionUpdated).conversationId)
        val t = LiveEvent.parse("""{"type":"typing","conversation_id":"cv","user_id":"u2","display_name":"Anna","activity":"recording","expires_at":"2026-11-19T10:00:06Z"}""")
        assertEquals("recording", (t as SocialLive.Typing).activity)
        val b = LiveEvent.parse("""{"type":"badge.updated","badge":{"total":3,"conversations":2,"threads":1}}""")
        assertEquals(Badge(3, 2, 1), (b as SocialLive.BadgeUpdated).badge)
        assertNull("unknown types are ignored", LiveEvent.parse("""{"type":"something.new"}"""))
        assertNull("malformed payloads are ignored", LiveEvent.parse("""{"type":"message.created"}"""))
        assertTrue(LiveEvent.parse("""{"type":"feed.membership_updated","feed_id":"f"}""") is LiveEvent.FeedMembershipUpdated)
    }

    @Test fun pushesCarryTagsTargetsAndReminderActions() {
        val msg = PushMessage.parse("""{"type":"reminder_quiet","key":"reminder_quiet","title":"t","body":"b","ids":{"frame_id":"f1"},"tag":"reminder_quiet:f1","actions":["snooze_month","fewer_reminders"]}""".toByteArray())!!
        assertEquals(listOf(SocialPush.ACTION_SNOOZE_MONTH, SocialPush.ACTION_FEWER_REMINDERS), msg.actions)
        assertEquals("reminder_quiet:f1", SocialNotifications.tagOf(msg))
        assertEquals(SocialNotifications.TARGET_FRAME_REQUEST, SocialNotifications.targetOf(msg))
        val chat = PushMessage("x", "Cousins", "Anna: hi", mapOf("conversation_id" to "cv", "message_id" to "m"), key = SocialPush.MESSAGE)
        assertEquals("conversation:cv", SocialNotifications.tagOf(chat))
        assertEquals(SocialNotifications.TARGET_CONVERSATION, SocialNotifications.targetOf(chat))
        val mention = PushMessage("x", "t", "b", mapOf("post_id" to "p", "comment_id" to "c"), key = SocialPush.MENTION)
        assertEquals(SocialNotifications.TARGET_POST, SocialNotifications.targetOf(mention))
        assertEquals("mention:c", SocialNotifications.tagOf(mention))
        assertEquals(listOf("a", "b"), SocialNotifications.parseActions("a, b"))
        assertEquals(listOf("a"), SocialNotifications.parseActions("""["a"]"""))
        assertEquals(30, SocialNotifications.reminderUpdate(SocialPush.ACTION_SNOOZE_MONTH)!!.snoozeDays)
        assertEquals(ReminderLevel.FEWER, SocialNotifications.reminderUpdate(SocialPush.ACTION_FEWER_REMINDERS)!!.level)
    }

    @Test fun chatPushesShowOnePerConversationWithReplyAndReminderButtons() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        SocialNotifications.createChannels(ctx)
        val first = PushMessage("message", "Cousins", "Anna: hi", mapOf("conversation_id" to "cv", "message_id" to "m1"), key = SocialPush.MESSAGE)
        SocialNotifications.show(ctx, first)
        SocialNotifications.show(ctx, first.copy(body = "Ben: hello", ids = mapOf("conversation_id" to "cv", "message_id" to "m2")))
        val shown = Shadows.shadowOf(nm).activeNotifications.filter { it.tag == "conversation:cv" }
        assertEquals("the newest replaces the older", 1, shown.size)
        val actions = shown.single().notification.actions.map { it.title.toString() }
        assertTrue(actions.contains(ctx.getString(R.string.chat_notif_reply)))
        // The open conversation's messages aren't shown.
        SocialNotifications.show(ctx, first.copy(ids = mapOf("conversation_id" to "other")), openConversation = "other")
        assertTrue(Shadows.shadowOf(nm).activeNotifications.none { it.tag == "conversation:other" })
        val reminder = PushMessage("reminder_streak", "Peeters", "Two photos to go", mapOf("feed_id" to "fd"), key = SocialPush.REMINDER_STREAK)
        SocialNotifications.show(ctx, reminder)
        val r = Shadows.shadowOf(nm).activeNotifications.single { it.tag == "reminder_streak:fd" }
        assertEquals(listOf(ctx.getString(R.string.engagement_action_snooze), ctx.getString(R.string.engagement_action_fewer)), r.notification.actions.map { it.title.toString() })
        SocialNotifications.clearConversation(ctx, "cv")
        assertTrue(Shadows.shadowOf(nm).activeNotifications.none { it.tag == "conversation:cv" })
    }

    // ---------------------------------------------------------------- outbox

    @Test fun chatSendsBecomeMessagesWithTheirUploads() {
        val photo = OutboxItem("i1", "content://1", mime = "image/jpeg", name = "a.jpg", size = 1, uploadId = "up1", caption = "Look")
        val send = OutboxSend("cl", "s", emptyList(), emptyList(), listOf(photo), 0, chat = OutboxChat("cv", "Cousins", body = "@Anna", mentions = listOf(Mention("u2", 0, 5)), replyToId = "m1"))
        val m = OutboxChat.message(send)
        assertEquals("cl", m.clientId)
        assertEquals("up1", m.items!!.single().uploadId)
        assertEquals("Look", m.items!!.single().caption)
        assertEquals("m1", m.replyToId)
        assertEquals(listOf("Cousins"), send.recipientNames)
        val voice = send.copy(items = listOf(photo.copy(kind = OutboxChat.VOICE_KIND, uploadId = "v1")), chat = OutboxChat("cv", "Cousins", voice = true, voiceDurationMs = 3000))
        val vm = OutboxChat.message(voice)
        assertEquals("v1", vm.voice!!.uploadId)
        assertNull(vm.items)
    }

    // ---------------------------------------------------------------- view models

    @Test fun homeLoadsTheTimelineAndReactsAtOnce() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val sent = mutableListOf<String?>()
        val api = object : FakeSocial() {
            override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?) =
                TimelinePage(listOf(timelinePost("p1"), timelinePost("p2")), nextBefore = null)
            override suspend fun setReaction(targetKind: String, targetId: String, emoji: String?, postId: String?): ReactionSummary {
                sent += emoji; return ReactionSummary(total = 1, mine = emoji)
            }
        }
        val vm = HomeViewModel(api, object : FakeApi() {
            override suspend fun frames() = emptyList<app.kinframe.core.api.Frame>()
            override suspend fun feeds() = emptyList<Feed>()
        }, { "me" }, live, engagement = false)
        assertEquals(2, (vm.state.value.posts as Load.Ready).value.size)
        vm.react((vm.state.value.posts as Load.Ready).value[0], "love")
        assertEquals(listOf<String?>("love"), sent)
        assertEquals("love", (vm.state.value.posts as Load.Ready).value[0].thread.reactions.mine)
        live.emit(SocialLive.PostRemoved("p2", "fd", null))
        assertEquals(listOf("p1"), (vm.state.value.posts as Load.Ready).value.map { it.id })
        live.emit(SocialLive.CommentCreated("p1", comment("c7")))
        assertEquals(1, (vm.state.value.posts as Load.Ready).value[0].thread.commentCount)
    }

    @Test fun homeOnAServerWithoutSocialShowsNotYet() = runTest {
        val vm = HomeViewModel(object : FakeSocial() {
            override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?): TimelinePage =
                throw ApiException(501, Problem(code = "feature_disabled"))
        }, object : FakeApi() {
            override suspend fun frames() = emptyList<app.kinframe.core.api.Frame>()
            override suspend fun feeds() = emptyList<Feed>()
        }, { "me" }, emptyFlow(), engagement = false)
        assertEquals(Load.Error(R.string.social_problem_not_ready), vm.state.value.posts)
    }

    @Test fun lastWeekIsTheMondayBeforeThisWeeksMonday() {
        val vm = HomeViewModel(object : FakeSocial() {
            override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?) = TimelinePage()
        }, object : FakeApi() {
            override suspend fun frames() = emptyList<app.kinframe.core.api.Frame>()
            override suspend fun feeds() = emptyList<Feed>()
        }, { "me" }, emptyFlow(), engagement = false, today = { java.time.LocalDate.parse("2026-11-19") })
        assertEquals("2026-11-09", vm.lastWeek())
    }

    private fun threadApi(created: MutableList<CommentCreate>, deleted: MutableList<String> = mutableListOf()) = object : FakeSocial() {
        override suspend fun post(postId: String) = timelinePost("p1", thread = ThreadSummary("p1", canComment = true))
        override suspend fun comments(postId: String, after: String?, limit: Int) = CommentPage(listOf(comment("c1", by = "anna"), comment("c2", parent = "c1", by = "me")))
        override suspend fun markThreadRead(postId: String, upTo: String) = Unit
        override suspend fun createComment(postId: String, comment: CommentCreate, idempotencyKey: String): Comment {
            created += comment; return Comment("c9", postId, parentId = comment.parentId, author = pref("me"), body = comment.body, mentions = comment.mentions.orEmpty(), createdAt = "2026-11-19T10:00:09Z")
        }
        override suspend fun deleteComment(postId: String, commentId: String) { deleted += commentId }
        override suspend fun setReaction(targetKind: String, targetId: String, emoji: String?, postId: String?) = ReactionSummary(total = 1, mine = emoji)
    }

    private val kinWithMembers = object : FakeApi() {
        override suspend fun feedMembers(feedId: String) = listOf(FeedMember("anna", "Anna"), FeedMember("me", "Me"))
    }

    @Test fun postThreadRepliesWithMentionsAndQuotes() = runTest {
        val created = mutableListOf<CommentCreate>()
        val vm = PostViewModel(threadApi(created), kinWithMembers, "p1", { "me" }, emptyFlow())
        assertEquals(1, vm.state.value.groups.size)
        assertEquals(listOf("anna"), vm.state.value.people.map { it.userId })
        vm.reply(vm.state.value.comments.first { it.id == "c2" }.copy(author = pref("anna")))
        val q = MentionText("@An").query(3)!!
        vm.setDraft(MentionText("@An").insert(q, "anna", "Anna").first)
        vm.send()
        val c = created.single()
        assertEquals("c1", c.parentId)
        assertEquals("anna", c.quotedUserId)
        assertEquals(listOf(Mention("anna", 0, 5)), c.mentions)
        assertEquals(listOf("c2", "c9"), vm.state.value.groups.single().replies.map { it.id })
        assertEquals("", vm.state.value.draft.text)
        assertEquals(1, vm.state.value.postOrNull!!.thread.commentCount)
    }

    @Test fun deletingOwnTopCommentWithRepliesLeavesATombstone() = runTest {
        val deleted = mutableListOf<String>()
        val api = object : FakeSocial() {
            override suspend fun post(postId: String) = timelinePost("p1")
            override suspend fun comments(postId: String, after: String?, limit: Int) = CommentPage(listOf(comment("c1", by = "me"), comment("c2", parent = "c1", by = "anna")))
            override suspend fun markThreadRead(postId: String, upTo: String) = Unit
            override suspend fun deleteComment(postId: String, commentId: String) { deleted += commentId }
        }
        val vm = PostViewModel(api, kinWithMembers, "p1", { "me" }, emptyFlow())
        vm.delete(vm.state.value.comments[0])
        assertEquals(listOf("c1"), deleted)
        assertTrue(vm.state.value.comments[0].deleted)
        assertEquals(2, vm.state.value.comments.size)
    }

    @Test fun threadFollowsLiveCommentsAndRemoval() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val vm = PostViewModel(threadApi(mutableListOf()), kinWithMembers, "p1", { "me" }, live)
        live.emit(SocialLive.CommentCreated("p1", comment("c5", by = "ben")))
        live.emit(SocialLive.CommentCreated("other", comment("c6", by = "ben")))
        assertEquals(listOf("c1", "c2", "c5"), vm.state.value.comments.map { it.id })
        live.emit(SocialLive.CommentDeleted("p1", "c1"))
        assertEquals(listOf("c5"), vm.state.value.comments.map { it.id })
    }

    @Test fun chatListSortsByActivityAndCountsUnread() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val created = mutableListOf<ConversationCreate>()
        val api = object : FakeSocial() {
            override suspend fun conversations(archived: Boolean) = listOf(
                Conversation("a", ConversationKind.FAMILY, "Peeters", lastActivityAt = "2026-11-18T10:00:00Z"),
                Conversation("b", ConversationKind.DIRECT, "Anna", lastActivityAt = "2026-11-19T10:00:00Z"),
            )
            override suspend fun createConversation(create: ConversationCreate, idempotencyKey: String): Conversation {
                created += create; return Conversation("c", create.kind, "Ben", lastActivityAt = "2026-11-19T11:00:00Z")
            }
        }
        val vm = ChatsViewModel(api, { "me" }, live)
        assertEquals(listOf("b", "a"), (vm.state.value.conversations as Load.Ready).value.map { it.id })
        live.emit(SocialLive.MessageCreated("a", message("m1", at = "2026-11-19T12:00:00Z").copy(conversationId = "a")))
        val list = (vm.state.value.conversations as Load.Ready).value
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(1, list[0].unreadCount)
        val opened = mutableListOf<ChatsNotice>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { opened += it } }
        vm.start(listOf("ben"), null)
        assertEquals(ConversationKind.DIRECT, created.single().kind)
        assertEquals(ChatsNotice.Open("c"), opened.single())
        job.cancel()
    }

    private fun convApi(sent: MutableList<MessageCreate>) = object : FakeSocial() {
        override suspend fun conversation(conversationId: String) = Conversation("cv", ConversationKind.GROUP, "Cousins", readReceipts = true,
            members = listOf(ConversationMember(pref("me")), ConversationMember(pref("anna"))), unreadCount = 1)
        override suspend fun messages(conversationId: String, before: String?, after: String?, limit: Int) = MessagePage(listOf(message("m1", by = "anna")))
        override suspend fun markConversationRead(conversationId: String, messageId: String) = Unit
        override suspend fun sendTyping(conversationId: String, activity: String) = Unit
        override suspend fun sendMessage(conversationId: String, message: MessageCreate, idempotencyKey: String): Message {
            sent += message; return message("m2", by = "me", clientId = message.clientId).copy(body = message.body)
        }
    }

    private val noMedia = object : ChatMedia {
        val queued = mutableListOf<List<android.net.Uri>>()
        override suspend fun queueMedia(conversationId: String, name: String, uris: List<android.net.Uri>, body: String?, mentions: List<Mention>, replyToId: String?): String {
            queued += uris; return "q"
        }
        override suspend fun queueVoice(conversationId: String, name: String, file: File, durationMs: Long, replyToId: String?) = "v"
    }

    @Test fun conversationSendsTextAndReplacesThePendingBubble() = runTest {
        val sent = mutableListOf<MessageCreate>()
        val vm = ConversationViewModel(convApi(sent), FakeApi(), "cv", { "me" }, emptyFlow(), noMedia, newKey = { "cl1" })
        assertEquals(0, vm.state.value.conversationOrNull!!.unreadCount)
        vm.reply(vm.state.value.messages.single())
        vm.onDraft("  hi there ")
        vm.send()
        assertEquals("hi there", sent.single().body)
        assertEquals("m1", sent.single().replyToId)
        assertEquals("cl1", sent.single().clientId)
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(listOf("m1", "m2"), vm.state.value.messages.map { it.id })
        assertNull(vm.state.value.replyTo)
    }

    @Test fun conversationShowsTypingAndReadPositions() = runTest {
        Dispatchers.resetMain()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        var now = 1_000_000L
        val vm = ConversationViewModel(convApi(mutableListOf()), FakeApi(), "cv", { "me" }, live, noMedia, now = { now })
        testScheduler.advanceUntilIdle()
        live.emit(SocialLive.Typing("cv", "anna", "Anna", "typing", null))
        testScheduler.runCurrent()
        assertEquals(listOf("Anna"), vm.state.value.typing.map { it.name })
        now += 7000
        advanceTimeBy(7000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.typing.isEmpty())
        live.emit(SocialLive.ReadUpdated("cv", "anna", "m1"))
        testScheduler.runCurrent()
        assertEquals("m1", vm.state.value.conversationOrNull!!.members.single { it.person.userId == "anna" }.lastReadMessageId)
    }

    @Test fun conversationMediaGoesThroughTheOutbox() = runTest {
        val vm = ConversationViewModel(convApi(mutableListOf()), FakeApi(), "cv", { "me" }, emptyFlow(), noMedia)
        vm.sendMedia(listOf(android.net.Uri.parse("content://a"), android.net.Uri.parse("content://b")))
        assertEquals(2, noMedia.queued.single().size)
    }

    @Test fun composerOffersOnlyFamiliesYouMayPostToWithCirclesAndPrompts() = runTest {
        val sends = mutableListOf<OutboxSend>()
        val kin = object : FakeApi() {
            override suspend fun frames() = emptyList<app.kinframe.core.api.Frame>()
            override suspend fun feeds() = listOf(
                Feed("f1", "Peeters", role = FeedRole.POSTER, circleCount = 1),
                Feed("f2", "Janssens", role = FeedRole.MEMBER),
            )
        }
        val social = object : FakeSocial() {
            override suspend fun circles(feedId: String) = listOf(Circle("c1", feedId, "Cousins", myRole = "member"))
            override suspend fun feedPrompts(feedId: String) = listOf(FeedPrompt("pr", feedId, "2026-11-16", PromptStatus.CURRENT, title = "Your view"))
        }
        val vm = ComposerViewModel({ kin }, { Limits() }, { "https://k" }, { null }, { sends += it }, newId = { "id" }, feedsEnabled = { true }, social = { social })
        assertEquals(listOf("f1"), vm.state.value.feeds.map { it.id })
        assertEquals("Cousins", vm.state.value.circles["f1"]!!.single().name)
        vm.toggleFeed("f1")
        vm.setAudience("f1", "c1")
        vm.answer("pr")
        val info = app.kinframe.family.send.MediaInfo(android.net.Uri.parse("content://x"), "x.jpg", "image/jpeg", 10, "photo")
        val withItems = ComposerViewModel({ kin }, { Limits() }, { "https://k" }, { info }, { sends += it }, newId = { "id" }, feedsEnabled = { true }, social = { social })
        withItems.add(listOf(android.net.Uri.parse("content://x")))
        withItems.toggleFeed("f1")
        withItems.setAudience("f1", "c1")
        withItems.answer("pr")
        withItems.send()
        assertEquals(listOf("c1"), sends.single().circleIds)
        assertEquals("pr", sends.single().promptId)
        withItems.toggleFeed("f1")
        assertNull("unselecting the family drops its prompt", withItems.state.value.answering)
    }
}
