package app.kinframe.family

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedMember
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.FeedPoster
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Frame
import app.kinframe.core.api.Item
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementsPage
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionFeed
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.core.api.SubscriptionHistory
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionStatus
import app.kinframe.core.social.Circle
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.ThreadSummary
import app.kinframe.core.social.TimelinePage
import app.kinframe.core.social.TimelinePost
import app.kinframe.core.api.Limits
import app.kinframe.family.ui.ChatsRoute
import app.kinframe.family.ui.FeedTab
import app.kinframe.family.ui.FrameTab
import app.kinframe.family.ui.HomeRoute
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Navigator
import app.kinframe.family.ui.Route
import app.kinframe.family.ui.family.Families
import app.kinframe.family.ui.family.FamilyFrames
import app.kinframe.family.ui.family.FamilyPrefs
import app.kinframe.family.ui.family.onlyMine
import app.kinframe.family.ui.family.simplePosts
import app.kinframe.family.ui.feeds.FeedDetailViewModel
import app.kinframe.family.ui.feeds.FeedNotice
import app.kinframe.family.ui.frames.FrameAdded
import app.kinframe.family.ui.frames.FramesOrder
import app.kinframe.family.ui.frames.FramesViewModel
import app.kinframe.family.ui.home.HomeRow
import app.kinframe.family.ui.home.HomeViewModel
import app.kinframe.family.ui.send.ComposerState
import app.kinframe.family.ui.send.ComposerViewModel
import app.kinframe.family.ui.send.canChangeDestination
import app.kinframe.family.ui.tabs
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun family(id: String, name: String, frames: Int = 0, role: String = FeedRole.POSTER, circles: Int = 0) =
    Feed(id, name, role = role, subscriptionCount = frames, circleCount = circles)

private fun managed(id: String, name: String) = Frame(id, name, role = "owner", permissions = Permissions.ALL)

private fun subOf(frame: String, feed: String = "fd1", status: String = SubscriptionStatus.ACTIVE) =
    Subscription("s-$frame-$feed", status, feed = SubscriptionFeed(feed, "Peeters family"), frame = SubscriptionFrame(frame, "Frame $frame"))

private fun tpost(id: String, poster: String = "u1", unread: Int = 0, mentions: Int = 0, items: Int = 1) = TimelinePost(
    id, poster = PersonRef(poster, poster), createdAt = "2026-11-19T09:00:00Z", feedId = "fd1", feedName = "Peeters",
    items = (1..items).map { Item("i$id$it", thumbUrl = "t") }, thread = ThreadSummary(id, unreadCount = unread, unreadMentions = mentions),
)

/** The simple family app (docs/spec/simple-app.md). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SimpleAppTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------------------------------------- navigation

    @Test fun theTabsAreHomeFramesChatsFamily() {
        assertEquals(listOf(HomeRoute, Route.Frames, ChatsRoute, Route.Family), tabs(chats = true))
        assertEquals("Chats only when the server has chats", listOf(HomeRoute, Route.Frames, Route.Family), tabs(chats = false))
        assertTrue(Route.Family.isTab)
        assertFalse("the composer is pushed, not a tab", Route.Send.isTab)
        assertFalse(Route.History.isTab)
        assertFalse(Route.Settings().isTab)
    }

    @Test fun aFrameOpensItsScreenThenThePage() {
        val nav = Navigator()
        nav.openFrame("f1")
        assertEquals(listOf(Route.Frames, Route.FrameDetail("f1")), nav.stack.toList())
        nav.openFrame("f1", FrameTab.People)
        assertEquals("people sit under Frame settings and help",
            listOf(Route.Frames, Route.FrameDetail("f1"), Route.FrameDetail("f1", FrameTab.Settings), Route.FrameDetail("f1", FrameTab.People)), nav.stack.toList())
        nav.openFrame("f1", FrameTab.Calendar)
        assertEquals(Route.FrameDetail("f1", FrameTab.Extras), nav.stack[2])
        nav.openFrame("f1", FrameTab.Library)
        assertEquals(listOf(Route.Frames, Route.FrameDetail("f1"), Route.FrameDetail("f1", FrameTab.Library)), nav.stack.toList())
    }

    @Test fun aFamilyOpensItsProfileOrItsSettings() {
        val nav = Navigator()
        // One family: the Family tab is its profile.
        nav.openFamily("fd1", FeedTab.People)
        assertEquals(listOf<Route>(Route.Family), nav.stack.toList())
        assertEquals(FeedTab.People, nav.familySegment)
        nav.openFamily("fd1", FeedTab.Birthdays)
        assertEquals(listOf(Route.Family, Route.Settings("fd1"), Route.FeedDetail("fd1", FeedTab.Birthdays)), nav.stack.toList())
        // Several: the list, with the family's profile on top.
        nav.singleFamily = false
        nav.openFamily("fd2", FeedTab.Frames)
        assertEquals(listOf(Route.Family, Route.FeedDetail("fd2", FeedTab.Frames)), nav.stack.toList())
        nav.compose()
        assertEquals(Route.Send, nav.current)
    }

    // ---------------------------------------------------------------------------------------------- the current family

    @Test fun theHomeFamilyIsTheOneYouPostToMost() {
        val trappers = family("a", "Trappers"); val opa = family("b", "Opa & Oma", role = FeedRole.OWNER)
        val sister = Feed("c", "Sister", createdAt = "2026-01-01T00:00:00Z"); val brother = Feed("d", "Brother", createdAt = "2025-01-01T00:00:00Z")
        assertEquals(trappers, Families.home(listOf(trappers, opa), mapOf("a" to 12, "b" to 3)))
        assertEquals("a tie: the one you created", opa, Families.home(listOf(trappers, opa)))
        assertEquals("else the oldest", brother, Families.home(listOf(sister, brother)))
        assertNull(Families.home(emptyList()))
        assertEquals(listOf("b", "d", "c"), Families.ordered(listOf(sister, opa, brother), opa).map { it.id })
    }

    @Test fun homeFiltersOnAFamilyAndShowsAPostOnce() {
        val a = family("a", "Trappers"); val b = family("b", "Opa & Oma")
        assertEquals("b", Families.filter(listOf(a, b), "b"))
        assertNull("left the filtered family: All", Families.filter(listOf(a), "b"))
        assertEquals("with All the row shows the home family", a, Families.rowFamily(listOf(a, b), null, a))
        assertEquals(b, Families.rowFamily(listOf(a, b), "b", a))
        val once = listOf(tpost("p1").copy(sendId = "s1"), tpost("p2").copy(sendId = "s1", feedId = "fd2"), tpost("p3"))
        assertEquals("one send to two families is one post", listOf("p1", "p3"), Families.once(once).map { it.id })
    }

    @Test fun theFamilyNameComesFromTheSurname() {
        assertEquals("Peeters", Families.surname("Jan Peeters"))
        assertEquals("van den Berg", Families.surname("Anna van den Berg"))
        assertNull(Families.surname("Oma"))
        assertNull(Families.surname(null))
    }

    @Test fun prefsArePerDevice() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = FamilyPrefs(ctx.getSharedPreferences(FamilyPrefs.NAME, Context.MODE_PRIVATE))
        assertNull(p.homeFilter.value)
        assertFalse(p.simpleView.value)
        assertFalse(p.viewAsked.value)
        p.setHomeFilter("fd2")
        p.setSimpleView(true)
        val again = FamilyPrefs(ctx.getSharedPreferences(FamilyPrefs.NAME, Context.MODE_PRIVATE))
        assertEquals("fd2", again.homeFilter.value)
        assertTrue(again.simpleView.value)
        assertTrue("answering is asking", again.viewAsked.value)
    }

    // ---------------------------------------------------------------------------------------------- Home

    @Test fun homeShowsTheCurrentFamilyOnly() = runTest {
        val asked = mutableListOf<String?>()
        val social = object : FakeSocial() {
            override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?): TimelinePage {
                asked += feedId; return TimelinePage(listOf(tpost("p1")))
            }
        }
        val kin = object : FakeApi() {
            override suspend fun frames() = listOf(managed("f1", "Oma"))
            override suspend fun feeds() = listOf(family("fd1", "Peeters"))
            override suspend fun feedMembers(feedId: String) = listOf(FeedMember("u2", "Anna"), FeedMember("me", "Jeroen"), FeedMember("u3", "Ben"))
            override suspend fun feedSubscriptions(feedId: String, status: String?) = listOf(subOf("f1"), subOf("f9"))
            override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int, albumId: String?, onThisDay: String?, sort: String?) =
                PlacementsPage(listOf(Placement("pl", frameId, Item("i", thumbUrl = "thumb-$frameId"))))
        }
        val vm = HomeViewModel(social, kin, { "me" }, emptyFlow(), engagement = false, feedId = "fd1")
        assertEquals(listOf<String?>("fd1"), asked)
        val s = vm.state.value
        assertEquals("the person first", listOf("me", "u2", "u3"), s.members.map { it.userId })
        assertEquals(listOf("f1", "f9"), s.familyFrames.map { it.id })
        assertEquals("only frames you can see the library of have a thumbnail", mapOf("f1" to "thumb-f1"), s.frameThumbs)
    }

    @Test fun homeInvitesWhenYouAreAlone() {
        assertTrue(HomeRow.alone(listOf(FeedMember("me", "Jeroen")), "me"))
        assertFalse(HomeRow.alone(listOf(FeedMember("me", "Jeroen"), FeedMember("u2", "Anna")), "me"))
        assertFalse("not loaded yet", HomeRow.alone(emptyList(), "me"))
    }

    @Test fun activityIsPostsWithSomethingNew() {
        val posts = listOf(tpost("p1"), tpost("p2", unread = 2), tpost("p3", mentions = 1))
        assertEquals(listOf("p2", "p3"), HomeRow.activity(posts).map { it.id })
    }

    @Test fun theSimpleViewShowsPostsWithPhotos() {
        assertEquals(listOf("p1"), simplePosts(listOf(tpost("p1"), tpost("p2", items = 0))).map { it.id })
    }

    // ---------------------------------------------------------------------------------------------- the composer

    @Test fun theDestinationIsTheFamilyOrOneOfItsCircles() = runTest {
        val circle = Circle("c1", "fd1", "The cousins", myRole = "member")
        val vm = ComposerViewModel(
            api = { object : FakeApi() {
                override suspend fun frames() = listOf(managed("f1", "Oma"))
                override suspend fun feeds() = listOf(family("fd1", "Peeters", frames = 3, circles = 1), family("fd2", "Janssens"))
            } },
            limits = { Limits() }, server = { "" }, inspect = { null }, enqueue = {},
            social = { object : FakeSocial() {
                override suspend fun circles(feedId: String) = if (feedId == "fd1") listOf(circle) else emptyList()
                override suspend fun feedPrompts(feedId: String) = emptyList<app.kinframe.core.social.FeedPrompt>()
            } },
        )
        vm.preselectFeed("fd1")
        var s = vm.state.value
        assertEquals("fd1", s.destinationFamily?.id)
        assertNull(s.destinationCircle)
        assertTrue("another family or a circle to change to", canChangeDestination(s))
        vm.setAudience("fd1", "c1")
        assertEquals("The cousins", vm.state.value.destinationCircle?.name)
        // Several families at once: one send.
        vm.toggleFamily("fd2")
        assertEquals(setOf("fd1", "fd2"), vm.state.value.destinationFamilies.map { it.id }.toSet())
        assertNull("several: no single family", vm.state.value.destinationFamily)
        vm.toggleFamily("fd1"); vm.toggleFamily("fd2")
        assertEquals("one stays ticked", listOf("fd2"), vm.state.value.destinationFamilies.map { it.id })
        vm.preselectFeed("fd2")
        s = vm.state.value
        assertEquals("fd2", s.destinationFamily?.id)
        assertTrue("another family's circle doesn't come along", s.audience.isEmpty())
        // From a frame's screen: that frame only, nothing to change.
        vm.preselectFrame("f1")
        s = vm.state.value
        assertTrue(s.frameOnly)
        assertNull(s.destinationFamily)
        assertFalse(canChangeDestination(s))
        assertTrue(s.selectedFeeds.isEmpty())
    }

    @Test fun nothingToChangeWithOneFamilyWithoutCircles() {
        val s = ComposerState(feeds = listOf(family("fd1", "Peeters")), selectedFeeds = setOf("fd1"))
        assertFalse(canChangeDestination(s))
    }

    // ---------------------------------------------------------------------------------------------- frames

    @Test fun addingAFrameAsksForTheLast50WithoutADialog() {
        assertEquals(SubscriptionRequest("f1", SubscriptionHistory.LAST_N, 50, true), FamilyFrames.request("f1"))
        val frames = listOf(managed("f1", "Oma"), managed("f2", "Opa"), managed("f3", "Cabin"))
        val subs = listOf(subOf("f1"), subOf("f2", status = SubscriptionStatus.PENDING), subOf("f3", status = SubscriptionStatus.REMOVED))
        assertEquals("shown or waiting frames aren't offered", listOf("f3"), FamilyFrames.candidates(frames, subs).map { it.id })
    }

    @Test fun addFrameSaysWhetherItShowsOrWaits() = runTest {
        var status = SubscriptionStatus.ACTIVE
        val requests = mutableListOf<SubscriptionRequest>()
        val api = object : FakeApi() {
            override suspend fun feed(feedId: String) = family("fd1", "Peeters family")
            override suspend fun feedPosts(feedId: String, cursor: String?, limit: Int, posterId: String?) = app.kinframe.core.api.FeedPostsPage()
            override suspend fun feedMembers(feedId: String) = emptyList<FeedMember>()
            override suspend fun feedSubscriptions(feedId: String, status: String?) = emptyList<Subscription>()
            override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription {
                requests += request; return subOf(request.frameId, status = status)
            }
        }
        val vm = FeedDetailViewModel(api, "fd1", { "me" }, emptyFlow())
        val notices = mutableListOf<FeedNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        vm.addFrame("f1")
        status = SubscriptionStatus.PENDING
        vm.addFrame("f2")
        job.cancel()
        assertEquals(listOf(50, 50), requests.map { it.historyCount })
        assertEquals(FeedNotice.Message(R.string.add_frame_added, listOf("Frame f1", "Peeters family")), notices[0])
        assertEquals(FeedNotice.Message(R.string.add_frame_pending), notices[1])
        assertEquals(2, vm.state.value.subscriptions.size)
    }

    @Test fun theFramesTabShowsYourFramesThenThoseOfYourFamilies() = runTest {
        val api = object : FakeApi() {
            override suspend fun frames() = listOf(managed("f1", "Alpha"), managed("f2", "Beta"))
            override suspend fun frameSubscriptions(frameId: String, status: String?) = if (frameId == "f2") listOf(subOf("f2")) else emptyList()
            override suspend fun feedSubscriptions(feedId: String, status: String?) = listOf(subOf("f2", feedId), subOf("f7", feedId))
            override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int, albumId: String?, onThisDay: String?, sort: String?) = PlacementsPage()
            override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String) = subOf(request.frameId, feedId)
        }
        val vm = FramesViewModel(api, emptyFlow())
        assertEquals(listOf("fd1"), vm.state.value.shows["f2"]?.map { it.id })
        vm.loadFamilyFrames(listOf("fd1", "fd2"))
        val ff = vm.state.value.familyFrames
        assertEquals("your own frames aren't repeated", listOf("f7"), ff.map { it.frame.id })
        assertEquals("one card for a frame showing two of your families", listOf("fd1", "fd2"), ff.single().shows.map { it.id })
        var result: FrameAdded? = null
        vm.addToFamily("f1", "fd1") { result = it }
        assertEquals(FrameAdded.Active("Frame f1", "Peeters family"), result)
        assertEquals(listOf("fd1"), vm.state.value.shows["f1"]?.map { it.id })
    }

    // ---------------------------------------------------------------------------------------------- the family profile

    @Test fun onlyMineReplacesHistory() {
        val posts = listOf(
            FeedPost("p1", "fd1", FeedPoster("me", "Jeroen"), createdAt = "2026-11-19T09:00:00Z"),
            FeedPost("p2", "fd1", FeedPoster("u2", "Anna"), createdAt = "2026-11-19T08:00:00Z"),
        )
        assertEquals(listOf("p1"), onlyMine(posts, "me", true).map { it.id })
        assertEquals(2, onlyMine(posts, "me", false).size)
        assertEquals(Pair(listOf("A", "B"), 1), Families.names(listOf("A", "B", "C"), max = 2))
    }
}
