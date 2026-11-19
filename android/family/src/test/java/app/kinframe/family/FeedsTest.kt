package app.kinframe.family

import android.net.Uri
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedInviteAccept
import app.kinframe.core.api.FeedInviteFrame
import app.kinframe.core.api.FeedInvitePreview
import app.kinframe.core.api.FeedInviteResult
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.FeedPostReceipts
import app.kinframe.core.api.FeedPostsPage
import app.kinframe.core.api.FeedPoster
import app.kinframe.core.api.FeedReceiptSummary
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Item
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Problem
import app.kinframe.core.api.SettingsDocument
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionFeed
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.api.FeedInvite
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.send.MediaInfo
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.feeds.FeedDetailViewModel
import app.kinframe.family.ui.feeds.FeedInviteViewModel
import app.kinframe.family.ui.feeds.FeedNotice
import app.kinframe.family.ui.feeds.FeedRules
import app.kinframe.family.ui.feeds.FeedsViewModel
import app.kinframe.family.ui.feeds.FrameFeedsViewModel
import app.kinframe.family.ui.frames.AddFrameViewModel
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.send.Recipients
import app.kinframe.family.util.DeepLink
import app.kinframe.family.util.DeepLinks
import app.kinframe.family.util.EnteredCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

private fun feed(id: String, name: String, role: String = FeedRole.POSTER, seeReactions: Boolean = false, frames: Int = 2) =
    Feed(id, name, role = role, membersSeeReactions = seeReactions, subscriptionCount = frames)

private fun post(id: String, poster: String, receipts: FeedReceiptSummary? = null, items: List<String> = listOf("i-$id")) =
    FeedPost(id, "fd1", FeedPoster(poster, poster.uppercase()), createdAt = "2026-11-18T10:00:00Z", items = items.map { Item(it) }, receipts = receipts)

private fun sub(id: String, status: String = SubscriptionStatus.ACTIVE, feedId: String = "fd1", frameId: String = "f1") =
    Subscription(id, status, feed = SubscriptionFeed(feedId, "Peeters kids"), frame = SubscriptionFrame(frameId, "Oma"), canManage = true)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FeedsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------------------------------------- codes and links

    @Test fun enterCodeTellsFeedCodesFromFrameCodes() {
        assertEquals(EnteredCode.Frame("12345678"), EnteredCode.parse("1234 5678", feeds = true))
        assertEquals(EnteredCode.Feed("KF7QM2XA"), EnteredCode.parse("kf7q-m2xa", feeds = true))
        assertTrue(EnteredCode.parse("KF7Q-M2XA", feeds = true).complete)
        assertFalse("partial feed code", EnteredCode.parse("KF7Q", feeds = true).complete)
        assertEquals(EnteredCode.FeedLink("abcdefghijklmnopqrstuv"), EnteredCode.parse("https://kinframe.appmire.be/fi/abcdefghijklmnopqrstuv", feeds = true))
        // A pasted frame link still yields the frame code.
        assertEquals(EnteredCode.Frame("87654321"), EnteredCode.parse("https://kinframe.appmire.be/f/87654321", feeds = true))
        // Without feeds on the server, letters are ignored as before.
        assertEquals(EnteredCode.Frame("72"), EnteredCode.parse("KF7Q-M2XA", feeds = false))
    }

    @Test fun feedInviteDeepLink() {
        val l = DeepLinks.parse("https://kinframe.appmire.be/fi/abcdefghijklmnopqrstuv")
        assertTrue(l is DeepLink.FeedInvite)
        assertEquals("abcdefghijklmnopqrstuv", (l as DeepLink.FeedInvite).token)
        assertEquals("https://kinframe.appmire.be/", l.server.toString())
        assertNull("too short for a token", DeepLinks.parse("https://kinframe.appmire.be/fi/abc"))
    }

    @Test fun addFrameFieldLooksUpFeedCodes() = runTest {
        val looked = mutableListOf<String>()
        val vm = AddFrameViewModel(object : FakeApi() {
            override suspend fun lookupFeedInvite(code: String): FeedInvitePreview {
                looked += code
                if (code == "ZZZZZZZZ") throw ApiException(404, Problem(code = "invite_invalid"))
                return FeedInvitePreview("tok-1234567890123456789012", feedName = "Peeters kids")
            }
            override suspend fun redeem(code: String, idempotencyKey: String) = error("not a frame code")
        }, feeds = true)
        vm.onInput("KF7Q")
        assertTrue(vm.state.value.isFeed)
        assertEquals("KF7Q", vm.state.value.fieldText)
        assertFalse(vm.state.value.complete)
        vm.onInput("KF7Q-M2XA") // pasted rest at once: submits
        assertEquals(listOf("KF7QM2XA"), looked)
        assertEquals("tok-1234567890123456789012", vm.state.value.feedInviteToken)
        vm.feedInviteOpened()
        assertNull(vm.state.value.feedInviteToken)
        vm.onInput("ZZZZZZZZ")
        assertEquals(R.string.feed_code_invalid, vm.state.value.error)
        // Digits switch back to a frame code.
        vm.onInput("1234")
        assertFalse(vm.state.value.isFeed)
        assertEquals("1234", vm.state.value.digits)
    }

    @Test fun pastedFeedLinkOpensTheInvite() = runTest {
        val vm = AddFrameViewModel(FakeApi(), feeds = true)
        vm.onInput("https://kinframe.appmire.be/fi/abcdefghijklmnopqrstuv")
        assertEquals("abcdefghijklmnopqrstuv", vm.state.value.feedInviteToken)
    }

    // ---------------------------------------------------------------------------------------------- sending

    @Test fun recipientsNeedAFrameOrAFeed() {
        assertFalse(Recipients.valid(emptySet(), emptySet()))
        assertTrue(Recipients.valid(setOf("f1"), emptySet()))
        assertTrue(Recipients.valid(emptySet(), setOf("fd1")))
        assertEquals(setOf("fd1"), Recipients.keep(setOf("fd1", "gone"), listOf("fd1", "fd2")))
    }

    @Test fun composerSendsToFeedsNextToFrames() = runTest {
        var queued: OutboxSend? = null
        var id = 0
        val vm = ComposerViewModel(
            api = {
                object : FakeApi() {
                    override suspend fun frames() = listOf(frame("f1", "Oma", online = true), frame("f2", "Opa"))
                    override suspend fun feeds() = listOf(feed("fd1", "Peeters kids", frames = 5), feed("fd2", "Opa's club"))
                }
            },
            limits = { Limits() }, server = { "https://k/" },
            inspect = { uri -> MediaInfo(uri, "a.jpg", "image/jpeg", 100, "photo", width = 4, height = 3) },
            enqueue = { queued = it }, newId = { "id${id++}" }, feedsEnabled = { true },
        )
        assertEquals(2, vm.state.value.feeds.size)
        vm.add(listOf(Uri.parse("content://m/1")))
        assertFalse("no recipient yet", vm.state.value.canSend)
        vm.toggleFeed("fd1")
        assertTrue("a feed alone is enough", vm.state.value.canSend)
        vm.send()
        assertEquals(emptyList<String>(), queued!!.frameIds)
        assertEquals(listOf("fd1"), queued!!.feedIds)
        assertEquals(listOf("Peeters kids"), queued!!.recipientNames)

        // "Post to this feed" from a feed: only that feed, no frame.
        vm.toggleFrame("f1")
        vm.preselectFeed("fd2")
        assertEquals(emptySet<String>(), vm.state.value.selected)
        assertEquals(setOf("fd2"), vm.state.value.selectedFeeds)
        vm.add(listOf(Uri.parse("content://m/2")))
        vm.toggleFrame("f2")
        vm.send()
        assertEquals(listOf("f2"), queued!!.frameIds)
        assertEquals(listOf("fd2"), queued!!.feedIds)
    }

    @Test fun composerHidesFeedsWhenTheServerHasNone() = runTest {
        val vm = ComposerViewModel(
            api = { object : FakeApi() { override suspend fun frames() = listOf(frame("f1", "Oma")) } },
            limits = { Limits() }, server = { "" }, inspect = { null }, enqueue = {}, feedsEnabled = { false },
        )
        assertTrue(vm.state.value.feeds.isEmpty())
        assertEquals(setOf("f1"), vm.state.value.selected)
    }

    // ---------------------------------------------------------------------------------------------- rules

    @Test fun visibilityAndMemberRules() {
        val poster = feed("fd1", "K", FeedRole.POSTER)
        val admin = feed("fd1", "K", FeedRole.ADMIN)
        val owner = feed("fd1", "K", FeedRole.OWNER)
        val open = feed("fd1", "K", FeedRole.POSTER, seeReactions = true)
        val mine = post("p1", "me")
        val theirs = post("p2", "ben")
        assertTrue(FeedRules.canSeeReceipts(poster, mine, "me"))
        assertFalse(FeedRules.canSeeReceipts(poster, theirs, "me"))
        assertTrue(FeedRules.canSeeReceipts(admin, theirs, "me"))
        assertTrue(FeedRules.canSeeReceipts(open, theirs, "me"))
        assertTrue(FeedRules.canRemovePost(poster, mine, "me"))
        assertFalse(FeedRules.canRemovePost(poster, theirs, "me"))
        assertTrue(FeedRules.canRemovePost(admin, theirs, "me"))

        val ownerM = FeedMember("o", "O", role = FeedRole.OWNER)
        val adminM = FeedMember("a", "A", role = FeedRole.ADMIN)
        val posterM = FeedMember("p", "P", role = FeedRole.POSTER)
        assertTrue(FeedRules.canManageMember(owner, adminM, "me"))
        assertFalse("nobody manages the owner", FeedRules.canManageMember(owner, ownerM, "me"))
        assertTrue(FeedRules.canManageMember(admin, posterM, "me"))
        assertFalse("admins don't manage other admins", FeedRules.canManageMember(admin, adminM, "me"))
        assertFalse(FeedRules.canManageMember(poster, posterM, "me"))
        assertFalse("not yourself", FeedRules.canManageMember(owner, FeedMember("me", "Me"), "me"))

        assertEquals(SubscriptionHistory.LAST_N to 1000, FeedRules.history(SubscriptionHistory.LAST_N, 5000))
        assertEquals(SubscriptionHistory.LAST_N to 50, FeedRules.history(SubscriptionHistory.LAST_N, null))
        assertEquals(SubscriptionHistory.ALL to null, FeedRules.history(SubscriptionHistory.ALL, 3))
        assertEquals(SubscriptionHistory.NONE to null, FeedRules.history("weird", 3))
        assertTrue(feed("x", "x", FeedRole.POSTER).copy(postersCanInvite = true).canInvite)
        assertFalse(feed("x", "x", FeedRole.POSTER).canInvite)
    }

    // ---------------------------------------------------------------------------------------------- view models

    @Test fun feedsListCreates() = runTest {
        val vm = FeedsViewModel(object : FakeApi() {
            override suspend fun feeds() = listOf(feed("b", "Zoo"), feed("a", "Aunts"))
            override suspend fun createFeed(request: app.kinframe.core.api.FeedCreate, idempotencyKey: String) = feed("n", request.name, FeedRole.OWNER)
        }, emptyFlow())
        assertEquals(listOf("Aunts", "Zoo"), (vm.state.value.feeds as Load.Ready).value.map { it.name })
        var created: Feed? = null
        vm.create("  Peeters kids ") { created = it }
        assertEquals("Peeters kids", created!!.name)
        assertEquals(3, (vm.state.value.feeds as Load.Ready).value.size)
    }

    @Test fun feedDetailLoadsReceiptsAndFollowsLiveUpdates() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val removed = mutableListOf<String>()
        var invitesLoaded = false
        val api = object : FakeApi() {
            override suspend fun feed(feedId: String) = feed("fd1", "Peeters kids", FeedRole.ADMIN)
            override suspend fun feedPosts(feedId: String, cursor: String?, limit: Int, posterId: String?) =
                FeedPostsPage(listOf(post("p1", "me", FeedReceiptSummary(frames = 5, seen = 1)), post("p2", "ben", items = listOf("a", "b"))))
            override suspend fun feedMembers(feedId: String) = listOf(FeedMember("p", "Zed"), FeedMember("o", "Owner", role = FeedRole.OWNER), FeedMember("a", "Ann", role = FeedRole.ADMIN))
            override suspend fun feedSubscriptions(feedId: String, status: String?) = listOf(sub("s1", SubscriptionStatus.PENDING), sub("s2"))
            override suspend fun feedJoinRequests(feedId: String) = listOf(JoinRequest("r1"))
            override suspend fun feedInvites(feedId: String): List<FeedInvite> { invitesLoaded = true; return emptyList() }
            override suspend fun feedPostReceipts(feedId: String, postId: String) = FeedPostReceipts(postId, FeedReceiptSummary(frames = 5, seen = 4))
            override suspend fun removeFeedPostItem(feedId: String, postId: String, itemId: String) { removed += "$postId/$itemId" }
            override suspend fun removeFeedPost(feedId: String, postId: String) { removed += postId }
            override suspend fun approveSubscription(subscriptionId: String) = sub(subscriptionId, SubscriptionStatus.ACTIVE)
            override suspend fun leaveFeed(feedId: String, removeMyPosts: Boolean) {}
        }
        val vm = FeedDetailViewModel(api, "fd1", { "me" }, live)
        val s = vm.state.value
        assertEquals(listOf("Owner", "Ann", "Zed"), s.members.map { it.displayName })
        assertEquals(1, s.pendingSubscriptions.size)
        assertEquals(1, s.requests.size)
        assertTrue("admins see invites", invitesLoaded)

        vm.openReceipts("p1")
        assertEquals(4, (vm.state.value.receipts as Load.Ready).value.summary.seen)
        vm.closeReceipts()
        assertNull(vm.state.value.receipts)

        live.emit(LiveEvent.FeedPostUpdated("fd1", "p1", FeedReceiptSummary(frames = 5, seen = 5)))
        assertEquals(5, (vm.state.value.posts as Load.Ready).value.first { it.id == "p1" }.receipts!!.seen)
        live.emit(LiveEvent.FeedPostUpdated("other", "p1", FeedReceiptSummary(frames = 9)))
        assertEquals(5, (vm.state.value.posts as Load.Ready).value.first { it.id == "p1" }.receipts!!.frames)

        vm.removePostItem((vm.state.value.posts as Load.Ready).value.first { it.id == "p2" }, "a")
        assertEquals(listOf("b"), (vm.state.value.posts as Load.Ready).value.first { it.id == "p2" }.items.map { it.id })
        vm.removePost((vm.state.value.posts as Load.Ready).value.first { it.id == "p1" })
        assertEquals(listOf("p2/a", "p1"), removed)
        assertEquals(listOf("p2"), (vm.state.value.posts as Load.Ready).value.map { it.id })

        vm.approve(vm.state.value.pendingSubscriptions.single())
        assertTrue(vm.state.value.pendingSubscriptions.isEmpty())
        assertEquals(2, vm.state.value.liveSubscriptions.size)

        val events = mutableListOf<FeedNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.leave(removeMyPosts = true)
        assertTrue(FeedNotice.Gone in events)
        job.cancel()
    }

    @Test fun posterDoesNotLoadAdminLists() = runTest {
        var asked = false
        val vm = FeedDetailViewModel(object : FakeApi() {
            override suspend fun feed(feedId: String) = feed("fd1", "K", FeedRole.POSTER)
            override suspend fun feedPosts(feedId: String, cursor: String?, limit: Int, posterId: String?) = FeedPostsPage()
            override suspend fun feedMembers(feedId: String) = emptyList<FeedMember>()
            override suspend fun feedSubscriptions(feedId: String, status: String?) = emptyList<Subscription>()
            override suspend fun feedJoinRequests(feedId: String): List<JoinRequest> { asked = true; return emptyList() }
            override suspend fun feedInvites(feedId: String): List<FeedInvite> { asked = true; return emptyList() }
        }, "fd1", { "me" }, emptyFlow())
        assertTrue(vm.state.value.feed is Load.Ready)
        assertFalse(asked)
    }

    @Test fun frameFeedsPauseResumeUnsubscribeAndFeedMode() = runTest {
        var lastPatch: JsonObject? = null
        var subs = listOf(sub("s1"), sub("s2", SubscriptionStatus.REMOVED, feedId = "fd2"))
        val requested = mutableListOf<SubscriptionRequest>()
        val api = object : FakeApi() {
            override suspend fun frameSubscriptions(frameId: String, status: String?) = subs
            override suspend fun feeds() = listOf(feed("fd1", "Peeters kids"), feed("fd3", "Club"))
            override suspend fun settings(frameId: String) = SettingsDocument(KinJson.parseToJsonElement("""{"version":3,"slideshow":{"feed_id":"fd1"}}""").jsonObject)
            override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument {
                assertEquals(3, version)
                lastPatch = patch
                return SettingsDocument(KinJson.parseToJsonElement("""{"version":4,"slideshow":{"feed_id":null}}""").jsonObject)
            }
            override suspend fun pauseSubscription(subscriptionId: String) = sub(subscriptionId, SubscriptionStatus.PAUSED)
            override suspend fun resumeSubscription(subscriptionId: String) = sub(subscriptionId, SubscriptionStatus.ACTIVE)
            override suspend fun removeSubscription(subscriptionId: String) { subs = subs.filterNot { it.id == subscriptionId } }
            override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription {
                requested += request
                return sub("s3", SubscriptionStatus.PENDING, feedId = feedId)
            }
        }
        val vm = FrameFeedsViewModel(api, "f1", manage = true, emptyFlow())
        val s = vm.state.value
        assertEquals(listOf("s1"), s.live.map { it.id })
        assertEquals(listOf("s2"), s.ended.map { it.id })
        assertEquals("fd1", s.playingFeed)
        assertEquals("feeds already followed are not offered again", listOf("fd3"), s.subscribable.map { it.id })

        vm.pause(s.live.single())
        assertEquals(SubscriptionStatus.PAUSED, vm.state.value.live.single().status)
        vm.resume(vm.state.value.live.single())
        assertEquals(SubscriptionStatus.ACTIVE, vm.state.value.live.single().status)

        vm.playOnly(null)
        assertEquals("""{"slideshow":{"feed_id":null}}""", lastPatch.toString())
        assertNull(vm.state.value.playingFeed)
        assertEquals("""{"slideshow":{"feed_id":"fd1","album_id":null}}""", FrameFeedsViewModel.feedModePatch("fd1").toString())

        vm.subscribe(feed("fd3", "Club"), SubscriptionHistory.LAST_N, 20)
        assertEquals(SubscriptionRequest("f1", SubscriptionHistory.LAST_N, 20), requested.single())

        vm.unsubscribe(vm.state.value.live.first { it.id == "s1" })
        assertTrue(vm.state.value.live.none { it.id == "s1" })
    }

    @Test fun subscribeInviteAcceptsChosenFramesWithHistory() = runTest {
        var accepted: FeedInviteAccept? = null
        val vm = FeedInviteViewModel(object : FakeApi() {
            override suspend fun previewFeedInvite(token: String) = FeedInvitePreview(token, purpose = "subscribe", feedName = "Peeters kids",
                frames = listOf(FeedInviteFrame("f1", "Opa"), FeedInviteFrame("f2", "Oma", SubscriptionStatus.ACTIVE)))
            override suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult {
                accepted = accept
                return FeedInviteResult("subscribed", feed("fd1", "Peeters kids"), subscriptions = listOf(sub("s1")))
            }
        }, "tok")
        assertTrue(vm.state.value.isSubscribe)
        assertEquals("the only frame that can still subscribe is preselected", setOf("f1"), vm.state.value.selected)
        vm.setHistory(SubscriptionHistory.LAST_N, 30)
        vm.accept()
        assertEquals(FeedInviteAccept(listOf("f1"), SubscriptionHistory.LAST_N, 30), accepted)
        assertEquals("subscribed", vm.state.value.result!!.status)
    }

    @Test fun joinInviteSendsNoBody() = runTest {
        var body: FeedInviteAccept? = FeedInviteAccept()
        val vm = FeedInviteViewModel(object : FakeApi() {
            override suspend fun previewFeedInvite(token: String) = FeedInvitePreview(token, feedName = "K", requiresApproval = true)
            override suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult {
                body = accept
                return FeedInviteResult("pending", feed("fd1", "K"))
            }
        }, "tok")
        vm.accept()
        assertNull(body)
        assertEquals("pending", vm.state.value.result!!.status)
    }

    // ---------------------------------------------------------------------------------------------- live and push

    @Test fun feedReceiptsInTheHistoryFollowLiveUpdates() {
        val send = app.kinframe.core.api.Send("s1", "2026-11-18T10:00:00Z",
            feeds = listOf(app.kinframe.core.api.SendFeed("fd1", "Peeters kids", "p1", summary = FeedReceiptSummary(frames = 5, seen = 1))))
        val updated = app.kinframe.family.history.Delivery.applyFeedSummary(send, "p1", FeedReceiptSummary(frames = 5, seen = 4))!!
        assertEquals(4, updated.feeds.single().summary!!.seen)
        assertNull(app.kinframe.family.history.Delivery.applyFeedSummary(send, "other", FeedReceiptSummary()))
    }

    @Test fun parsesFeedWebsocketEvents() {
        val subJson = """{"id":"s1","status":"pending","history":"none","feed":{"id":"fd1","name":"K"},"frame":{"id":"f1","name":"Oma"}}"""
        assertEquals(LiveEvent.FeedPostUpdated("fd1", "p1", FeedReceiptSummary(frames = 5, delivered = 5, seen = 4)),
            LiveEvent.parse("""{"type":"feed_post.updated","feed_id":"fd1","post_id":"p1","summary":{"frames":5,"delivered":5,"seen":4}}"""))
        assertEquals("s1", (LiveEvent.parse("""{"type":"subscription.request","feed_id":"fd1","subscription":$subJson}""") as LiveEvent.SubscriptionRequested).subscription.id)
        assertEquals("f1", (LiveEvent.parse("""{"type":"subscription.updated","feed_id":"fd1","frame_id":"f1","subscription":$subJson}""") as LiveEvent.SubscriptionUpdated).frameId)
        assertEquals(LiveEvent.FeedMembershipUpdated("fd1"), LiveEvent.parse("""{"type":"feed.membership_updated","feed_id":"fd1"}"""))
        assertTrue(LiveEvent.parse("""{"type":"feed.join_request","feed_id":"fd1","request":{"id":"r1"}}""") is LiveEvent.FeedJoinRequested)
        val r = LiveEvent.parse("""{"type":"reaction","placement_id":"pl","frame_id":"f1","reaction":"love","feed_id":"fd1","post_id":"p1"}""") as LiveEvent.Reaction
        assertEquals("p1", r.postId)
    }

    @Test fun feedPushesOpenTheRightScreen() {
        fun push(key: String, ids: Map<String, String>) = PushMessage("push", "", "", ids, key)
        assertEquals(Notifications.TARGET_FEED_FRAMES, Notifications.targetOf(push("subscription_request", mapOf("feed_id" to "fd1"))))
        assertEquals(Notifications.TARGET_FEED_PEOPLE, Notifications.targetOf(push("feed_join_request", mapOf("feed_id" to "fd1"))))
        assertEquals(Notifications.TARGET_FRAME_FEEDS, Notifications.targetOf(push("subscription_removed", mapOf("feed_id" to "fd1", "frame_id" to "f1"))))
        assertEquals(Notifications.TARGET_FEED, Notifications.targetOf(push("reaction", mapOf("feed_id" to "fd1", "post_id" to "p1"))))
        assertEquals(Notifications.TARGET_HISTORY, Notifications.targetOf(push("reaction", mapOf("send_id" to "s1"))))
        // Servers that only send `type` keep working.
        assertEquals(Notifications.TARGET_PEOPLE, Notifications.targetOf(PushMessage("join_request", "", "", emptyMap())))
    }
}
