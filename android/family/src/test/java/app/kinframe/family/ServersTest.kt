package app.kinframe.family

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import org.robolectric.RuntimeEnvironment
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import app.kinframe.core.api.Album
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.Feed
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.User
import app.kinframe.core.social.SocialPush
import app.kinframe.core.social.TimelinePage
import app.kinframe.core.social.WeeklySummary
import app.kinframe.family.accounts.AccountDeps
import app.kinframe.family.accounts.AccountManager
import app.kinframe.family.accounts.AccountStore
import app.kinframe.family.accounts.AlreadySignedInException
import app.kinframe.family.accounts.ServerChips
import app.kinframe.family.data.MemorySecureStore
import app.kinframe.family.data.SecureStore
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.notify.PushRegistrar
import app.kinframe.family.notify.SocialNotifications
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.ServerSplit
import app.kinframe.family.social.SocialGraph
import app.kinframe.family.ui.BlockedPeopleRoute
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.MilestonesRoute
import app.kinframe.family.ui.Navigator
import app.kinframe.family.ui.PostRoute
import app.kinframe.family.ui.RemindersRoute
import app.kinframe.family.ui.ReportsRoute
import app.kinframe.family.ui.SocialFlags
import app.kinframe.family.ui.SocialTargets
import app.kinframe.family.ui.WeeklySummaryRoute
import app.kinframe.family.ui.home.HomeSummary
import app.kinframe.family.ui.home.HomeViewModel
import app.kinframe.family.ui.isAccountScoped
import app.kinframe.family.ui.openSocialTarget
import app.kinframe.family.ui.send.ComposerState
import app.kinframe.family.ui.send.DraftItem
import app.kinframe.family.ui.send.ItemIssue
import app.kinframe.family.ui.send.Recipients
import app.kinframe.family.ui.send.ServerLimits
import app.kinframe.family.ui.socialRouteAccount
import app.kinframe.family.ui.sources.syncChoices
import kotlinx.coroutines.flow.first
import app.kinframe.family.send.MediaInfo
import app.kinframe.core.api.MediaKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Several servers in one app (docs/spec/multi-server.md §2): account storage, migration, routing, split sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val secures = mutableMapOf<String, SecureStore>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        listOf("kinframe", "kinframe_account_b").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun prefs(id: String) = app.getSharedPreferences(AccountStore.prefsName(id), Context.MODE_PRIVATE)

    private fun disco(name: String, serverId: String, base: String) =
        Discovery(name = name, serverId = serverId, serverName = name, baseUrl = base, devAuth = true, feeds = true)

    /** What `ServerRepository` and `AuthRepository` (dev sign-in) store for a signed-in account. */
    private fun seed(id: String, url: String, d: Discovery) {
        prefs(id).edit(commit = true) {
            putString("server_url", url)
            putString("server_discovery", KinJson.encodeToString(Discovery.serializer(), d))
        }
        secures.getOrPut(id) { MemorySecureStore() }.apply {
            put("auth_server", url); put("auth_mode", "Dev"); put("dev_token", "t-$id"); put("dev_token_expires_at", (System.currentTimeMillis() + 3_600_000).toString())
        }
    }

    private fun manager() = AccountManager(
        AccountDeps(app, OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.Main), "test", "test", ::prefs) { secures.getOrPut(it) { MemorySecureStore() } },
        app.getSharedPreferences("kinframe", Context.MODE_PRIVATE),
    )

    @Test
    fun aSingleServerInstallBecomesTheFirstAccountWithoutSigningInAgain() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        assertEquals(listOf(AccountStore.MAIN), m.store.ids())
        val a = m.primary.value
        assertEquals(AccountStore.MAIN, a.id)
        assertTrue(a.signedIn)
        assertEquals("appmire", a.label)
        assertFalse(m.several)
        assertNotNull(m.routes.primary)
    }

    @Test
    fun aFreshInstallHasNoAccountsUntilAServerIsChosen() {
        val m = manager()
        assertTrue(m.store.ids().isEmpty())
        assertTrue(m.accounts.value.isEmpty())
        assertNull(m.primary.value.server)
    }

    @Test
    fun accountsAddDedupeAndPrimary() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        // "Add a server": a draft joins the list once committed.
        val draft = m.newDraft()
        assertNull(draft.server)
        draft.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        m.commit(draft)
        assertEquals(listOf(AccountStore.MAIN, draft.id), m.store.ids())
        assertEquals(2, m.accounts.value.size)
        // The same server twice (same server_id, another address) is refused and forgotten.
        val dup = m.newDraft()
        dup.servers.choose(ServerChoice("https://janssens.example/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        try {
            m.commit(dup); fail()
        } catch (e: AlreadySignedInException) {
            assertEquals(draft.id, e.account.id)
        }
        assertFalse(dup.id in m.store.ids())
        // After a restart, with both signed in: server chips and push routing by the payload's server.
        seed(draft.id, "https://kinframe.janssens.be/", disco("Janssens", "s-a", "https://kinframe.janssens.be/"))
        val m4 = manager()
        assertTrue(m4.several)
        m4.index.put("feed-j", draft.id)
        assertEquals("Janssens", m4.chipFor("feed-j"))
        assertEquals(draft.id, m4.forKey("feed-j").id)
        assertEquals(AccountStore.MAIN, m4.forKey("unknown").id)
        assertEquals(draft.id, m4.forServer("https://kinframe.janssens.be")?.id)
        assertEquals(AccountStore.MAIN, m4.forServer("https://kinframe.appmire.be/")?.id)
        assertNull(m4.forServer("https://elsewhere.example/"))
        assertEquals(2, m4.routes.all().size)
        // Primary and signing out of one.
        m4.setPrimary(draft.id)
        assertEquals(draft.id, m4.primary.value.id)
        assertEquals(draft.id, m4.accounts.value.first().id)
        m4.remove(m4.byId(draft.id)!!)
        assertEquals(AccountStore.MAIN, m4.primary.value.id)
        assertEquals(listOf(AccountStore.MAIN), m4.store.ids())
        // The first account's file keeps the account list.
        assertTrue(prefs(AccountStore.MAIN).contains("accounts"))
    }

    @Test
    fun theIndexSurvivesARestart() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        m.index.put("frame-1", AccountStore.MAIN)
        m.store.saveIndex(m.index)
        assertEquals(AccountStore.MAIN, manager().index.get("frame-1"))
    }

    @Test
    fun pushPayloadsCarryTheServerAndInstancesMapToAccounts() {
        val msg = PushMessage.parse("""{"type":"x","key":"keep_request","title":"t","body":"b","ids":{"request_id":"k1"},"server":"https://kinframe.janssens.be/"}""".toByteArray())!!
        assertEquals("https://kinframe.janssens.be/", msg.server)
        assertEquals("", PushMessage.parse("""{"type":"x","title":"t","body":"b","ids":{}}""".toByteArray())!!.server)
        assertEquals(AccountStore.MAIN, PushRegistrar.accountOf("default"))
        assertEquals(AccountStore.MAIN, PushRegistrar.accountOf(null))
        assertEquals("abc", PushRegistrar.accountOf("abc"))
        assertEquals("default", PushRegistrar.instanceOf(AccountStore.MAIN))
    }

    @Test
    fun aSendToTwoServersIsTwoSends() {
        val item = OutboxItem(id = "i1", uri = "content://x/1", mime = "image/jpeg", name = "a.jpg", size = 10, uploadId = "up1")
        val send = OutboxSend(
            id = "s1", server = "https://b/", frameIds = listOf("fb", "fa"), frameNames = listOf("Oma", "Kitchen"),
            items = listOf(item), createdAt = 0, feedIds = listOf("feedA"), feedNames = listOf("Janssens"),
        )
        val accountOf = mapOf("fa" to "A", "feedA" to "A", "fb" to "B")
        var n = 0
        val parts = ServerSplit.split(send, "B", { accountOf[it] }, { "https://$it/" }) { "new${n++}" }
        assertEquals(2, parts.size)
        assertEquals("s1", parts[0].id)
        assertEquals("https://B/", parts[0].server)
        assertEquals(listOf("fb"), parts[0].frameIds)
        assertTrue(parts[0].feedIds.isEmpty())
        assertEquals(listOf("fa"), parts[1].frameIds)
        assertEquals(listOf("Janssens"), parts[1].feedNames)
        assertEquals("up1", parts[0].items.single().uploadId)
        assertNull(parts[1].items.single().uploadId)
        // One server: unchanged apart from the server.
        assertEquals(1, ServerSplit.split(send.copy(frameIds = listOf("fb"), frameNames = listOf("Oma"), feedIds = emptyList(), feedNames = emptyList()), "B", { accountOf[it] }, { "https://$it/" }).size)
    }

    @Test
    fun detailScreensUseTheirOwnAccountsProfileAndFlags() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        val d = m.newDraft()
        d.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/").copy(social = true, leaving = true)))
        m.commit(d)
        val b = m.accounts.value.first { it.id != AccountStore.MAIN }
        m.primary.value.me.value = User(id = "me-a", displayName = "An")
        b.me.value = User(id = "me-b", displayName = "An", isOperator = true)
        m.index.put("post-b", b.id)
        val ctx = m.contextFor("post-b")
        assertEquals(b.id, ctx.accountId)
        assertEquals("me-b", ctx.meId)
        assertTrue(ctx.isOperator)
        assertTrue(ctx.discovery!!.leaving)
        assertEquals("me-a", m.contextFor("post-a").meId)
        assertFalse(m.contextFor("post-a").discovery!!.leaving)
        assertEquals("me-b", m.meIdFor("post-b"))
        // Merged lists: either id is the person.
        assertTrue(m.isMe("me-a")); assertTrue(m.isMe("me-b")); assertFalse(m.isMe("someone")); assertFalse(m.isMe(null))
        assertTrue(m.sameAccount("post-b", "post-b"))
        assertFalse(m.sameAccount("post-b", "frame-a"))
    }

    @Test
    fun stripNamesTheServerOnlyWhenANameRepeatsAcrossServers() {
        val items = listOf("f1" to "Oma", "f2" to "oma ", "f3" to "Kitchen", "f4" to "Kitchen")
        val account = mapOf("f1" to "A", "f2" to "B", "f3" to "A", "f4" to "A")
        assertEquals(setOf("f1", "f2"), ServerChips.repeatedAcrossServers(items, { it.first }, { it.second }) { account[it] })
        val groups = ServerChips.byAccount(items, { it.first }, { account[it] }, listOf("B", "A"))
        assertEquals(listOf("B", "A"), groups.map { it.first })
        assertEquals(listOf("f2"), groups[0].second.map { it.first })
    }

    @Test
    fun theComposerGroupsRecipientsPerServer() {
        val fa = Frame(id = "fa", name = "Kitchen", permissions = Permissions(send = true))
        val fb = Frame(id = "fb", name = "Oma", permissions = Permissions(send = true))
        val s = ComposerState(frames = Load.Ready(listOf(fa, fb)))
        assertEquals(listOf(null), s.recipientGroups.map { it.server })
        val g = s.copy(serverOf = mapOf("fa" to "appmire", "fb" to "Janssens")).recipientGroups
        assertEquals(listOf("appmire", "Janssens"), g.map { it.server })
        assertEquals(listOf("fb"), g[1].frames.map { it.id })
        assertEquals(listOf("c", "b"), Recipients.familiesToPreselect(setOf("b", "c"), listOf("a", "c", "b")))
    }

    /** Two signed-in accounts on two fake servers: MAIN on [a], a second one on [b]. Returns the manager and B's id. */
    private fun twoServers(a: MockWebServer, b: MockWebServer, d: (Discovery) -> Discovery = { it }): Pair<AccountManager, String> {
        val urlA = a.url("/").toString(); val urlB = b.url("/").toString()
        seed(AccountStore.MAIN, urlA, d(disco("appmire", "s-a", urlA)))
        val m0 = manager()
        val draft = m0.newDraft()
        draft.servers.choose(ServerChoice(urlB.toHttpUrl(), d(disco("Janssens", "s-b", urlB))))
        m0.commit(draft)
        seed(draft.id, urlB, d(disco("Janssens", "s-b", urlB)))
        val m = manager()
        assertTrue(m.several)
        return m to draft.id
    }

    @Test
    fun aRealisticFcmPayloadCarriesTheServerAndPicksItsAccount() {
        // What server/internal/notify/fcm.go sends: every field a string, ids at the top level, actions comma-separated.
        val data = mapOf(
            "type" to "reminder_quiet", "key" to "reminder_quiet", "title" to "Oma's frame", "body" to "No new photos for two weeks",
            "server" to "https://kinframe.janssens.be/", "tag" to "reminder_quiet:f1", "actions" to "snooze_month,fewer_reminders",
            "badge" to "3", "frame_id" to "f1",
        )
        val msg = PushMessage.fromData(data)
        assertEquals("https://kinframe.janssens.be/", msg.server)
        assertEquals(mapOf("frame_id" to "f1"), msg.ids)
        assertEquals(listOf("snooze_month", "fewer_reminders"), msg.actions)
        assertEquals("reminder_quiet", msg.kind)
        assertEquals("", msg.account)
        // Older servers: the notification block's title and body.
        assertEquals("T", PushMessage.fromData(mapOf("type" to "x"), "T", "B").title)

        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m0 = manager()
        val d = m0.newDraft()
        d.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        m0.commit(d)
        seed(d.id, "https://kinframe.janssens.be/", disco("Janssens", "s-a", "https://kinframe.janssens.be/"))
        val m = manager()
        // The index thinks the frame is the primary account's: the payload's `server` wins and corrects it.
        m.index.put("f1", AccountStore.MAIN)
        assertEquals(d.id, m.accountForPush(msg.server, msg.ids, null).id)
        assertEquals(d.id, m.index.get("f1"))
        // FCM has no instance: without `server` the index answers.
        m.index.put("f2", AccountStore.MAIN)
        assertEquals(AccountStore.MAIN, m.accountForPush("", mapOf("frame_id" to "f2"), null).id)
    }

    @Test
    fun reminderNotificationsCarryTheirAccountToTheButtonsAndTheTap() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        SocialNotifications.createChannels(app)
        val msg = PushMessage("reminder_streak", "Peeters", "Two photos to go", mapOf("feed_id" to "fd"), key = SocialPush.REMINDER_STREAK, account = "acc-b")
        SocialNotifications.show(app, msg)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val n = org.robolectric.Shadows.shadowOf(nm).activeNotifications.single { it.tag == "reminder_streak:fd" }.notification
        val button = org.robolectric.Shadows.shadowOf(n.actions.first().actionIntent).savedIntent
        assertEquals("acc-b", button.getStringExtra(SocialNotifications.EXTRA_ACCOUNT))
        val tap = org.robolectric.Shadows.shadowOf(n.contentIntent).savedIntent
        assertEquals("acc-b", tap.getStringExtra(Notifications.EXTRA_ACCOUNT))
    }

    @Test
    fun reminderButtonsChangeTheRemindersOnThePushesOwnServer() = kotlinx.coroutines.runBlocking {
        val a = MockWebServer(); val b = MockWebServer()
        a.start(); b.start()
        try {
            val (m, bId) = twoServers(a, b) { it.copy(engagement = true) }
            b.enqueue(MockResponse.Builder().body("""{"level":"normal","snoozed_until":"2026-12-19T00:00:00Z"}""").build())
            // UnifiedPush: B's endpoint got a push without `server`; its account is B.
            val routed = m.accountForPush("", mapOf("frame_id" to "fx"), PushRegistrar.accountOf(bId))
            assertEquals(bId, routed.id)
            assertTrue(SocialGraph.reminderAction(m, SocialPush.ACTION_SNOOZE_MONTH, routed.id))
            val req = b.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("PATCH", req.method)
            assertEquals("/v1/me/reminders", req.url.encodedPath)
            assertTrue(req.body!!.utf8().contains("\"snooze_days\":30"))
            assertEquals(0, a.requestCount)
            // A notification from before the account was known: the primary account.
            a.enqueue(MockResponse.Builder().body("""{"level":"fewer"}""").build())
            assertTrue(SocialGraph.reminderAction(m, SocialPush.ACTION_FEWER_REMINDERS, null))
            assertEquals("/v1/me/reminders", a.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.url.encodedPath)
            assertEquals(1, b.requestCount)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aPushWithoutServerUsesWhatTheIndexKnowsAndNeverOverwritesIt() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        val d = m.newDraft()
        d.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        m.commit(d)
        m.index.put("post-b", d.id)
        // FCM, no `server`: the post is known to be the Janssens account's.
        assertEquals(d.id, m.accountForPush("", mapOf("post_id" to "post-b", "comment_id" to "c1"), null).id)
        assertEquals(d.id, m.index.get("post-b"))
        assertNull(m.index.get("c1"))
        // Nothing known: the primary account, and the guess isn't recorded.
        assertEquals(AccountStore.MAIN, m.accountForPush(null, mapOf("post_id" to "post-x"), null).id)
        assertNull(m.index.get("post-x"))
        // With `server`: recorded for that account.
        assertEquals(d.id, m.accountForPush("https://kinframe.janssens.be/", mapOf("post_id" to "post-y"), null).id)
        assertEquals(d.id, m.index.get("post-y"))
    }

    private fun twoAccounts(): Pair<AccountManager, String> {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/").copy(engagement = true, chats = true))
        val m0 = manager()
        val d = m0.newDraft()
        d.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        m0.commit(d)
        seed(d.id, "https://kinframe.janssens.be/", disco("Janssens", "s-a", "https://kinframe.janssens.be/").copy(engagement = true, chats = true))
        return manager() to d.id
    }

    @Test
    fun aScreenOpenedBeforeItsAccountWasKnownFollowsTheIndex() = kotlinx.coroutines.runBlocking {
        val (m, b) = twoAccounts()
        // A tap on an older server's push: the post isn't known yet, so the primary account answers.
        assertNull(m.accountIdOf("post-late").first())
        assertEquals(AccountStore.MAIN, m.contextFor("post-late").accountId)
        val before = m.learned.value
        // The merged timeline then teaches the index that the post is B's.
        m.index.put("post-late", b)
        assertTrue(m.learned.value > before)
        assertEquals(b, m.accountIdOf("post-late").first())
        assertEquals(b, m.contextFor("post-late").accountId)
        // Per-server settings name their account; unknown or null is the primary one.
        assertEquals(b, m.contextOf(b).accountId)
        assertEquals(AccountStore.MAIN, m.contextOf(null).accountId)
        assertEquals(AccountStore.MAIN, m.contextOf("gone").accountId)
        assertEquals("Janssens", m.labelIfSeveral(b))
    }

    @Test
    fun perServerSettingsRoutesNameTheirAccount() {
        assertTrue(isAccountScoped(RemindersRoute("b")))
        assertTrue(isAccountScoped(ReportsRoute(null, "b")))
        assertFalse(isAccountScoped(ReportsRoute("feed-1")))
        assertFalse(isAccountScoped(PostRoute("p")))
        assertEquals("b", socialRouteAccount(BlockedPeopleRoute("b")))
        assertEquals("b", socialRouteAccount(WeeklySummaryRoute("2026-11-09", "b")))
        assertEquals("b", socialRouteAccount(MilestonesRoute("b")))
        // A family's reports are found by the family, not named.
        assertNull(socialRouteAccount(ReportsRoute("feed-1", "b")))
    }

    @Test
    fun aWeeklySummaryPushOpensTheSummaryOfItsOwnServer() = kotlinx.coroutines.runBlocking {
        val (m, b) = twoAccounts()
        val nav = Navigator()
        val targets = SocialTargets(
            tabs = SocialFlags(social = true, chats = true, engagement = true, voice = false, linkPreviews = false),
            account = { m.contextFor(it) }, canSendTo = { true }, sendTo = { _, _ -> }, byAccount = { m.contextOf(it) },
        )
        val t = OpenTarget(SocialNotifications.TARGET_WEEKLY_SUMMARY, ids = mapOf("week_of" to "2026-11-09"), accountId = b)
        assertTrue(openSocialTarget(t, nav, targets))
        assertEquals(WeeklySummaryRoute("2026-11-09", b), nav.current)
        // The operator's reports of the server that sent the push.
        m.byId(b)!!.me.value = User(id = "me-b", displayName = "An", isOperator = true)
        assertTrue(openSocialTarget(OpenTarget(SocialNotifications.TARGET_REPORTS, accountId = b), nav, targets))
        assertEquals(ReportsRoute(null, b), nav.current)
    }

    @Test
    fun homeShowsOneWeeklySummaryPerServerAndEachOpensItsOwn() {
        val social = object : FakeSocial() {
            override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?) = TimelinePage()
        }
        val kin = object : FakeApi() {
            override suspend fun frames() = emptyList<Frame>()
            override suspend fun feeds() = emptyList<Feed>()
        }
        val weeks = mutableListOf<String>()
        val vm = HomeViewModel(social, kin, { "me" }, kotlinx.coroutines.flow.emptyFlow(), engagement = true,
            today = { java.time.LocalDate.parse("2026-11-19") },
            summaries = { week ->
                weeks += week
                listOf(
                    HomeSummary(WeeklySummary(week, seenCount = 3), "A"),
                    HomeSummary(WeeklySummary(week, seenCount = 5), "B"),
                    // Nothing seen: no card.
                    HomeSummary(WeeklySummary(week, seenCount = 0), "C"),
                )
            })
        assertEquals(listOf("2026-11-09"), weeks)
        val cards = vm.state.value.summaries
        assertEquals(listOf("A", "B"), cards.map { it.accountId })
        vm.dismissSummary(cards[0])
        assertEquals(listOf("B"), vm.state.value.summaries.map { it.accountId })
    }

    @Test
    fun theStrictestChosenServersLimitsApplyAndNameTheServer() {
        val fa = Frame(id = "fa", name = "Kitchen", permissions = Permissions(send = true))
        val fb = Frame(id = "fb", name = "Oma", permissions = Permissions(send = true))
        val base = Limits(maxItemsPerSend = 50, maxVideoSeconds = 300, maxUploadBytes = 1_000_000)
        val strict = Limits(maxItemsPerSend = 2, maxVideoSeconds = 30, maxUploadBytes = 1_000_000)
        val item = { id: String, size: Long, video: Long? ->
            DraftItem(id, MediaInfo(uri = android.net.Uri.parse("content://x/$id"), name = "$id.jpg", mime = if (video != null) "video/mp4" else "image/jpeg", size = size,
                kind = if (video != null) MediaKind.VIDEO else MediaKind.PHOTO, durationMs = video))
        }
        val s = ComposerState(
            items = listOf(item("1", 10, null), item("2", 10, null), item("3", 10, 60_000)), frames = Load.Ready(listOf(fa, fb)), limits = base,
            serverOf = mapOf("fa" to "appmire", "fb" to "Janssens"),
            limitsOf = mapOf("fa" to ServerLimits("appmire", base), "fb" to ServerLimits("Janssens", strict)),
        )
        // Only appmire chosen: its limits.
        val a = s.copy(selected = setOf("fa"))
        assertEquals(50, a.sendLimits.limits.maxItemsPerSend)
        assertFalse(a.tooMany)
        assertTrue(a.canSend)
        // Both: Janssens is stricter on items and video length, and is named; the upload limit is the same, so not named.
        val both = s.copy(selected = setOf("fa", "fb"))
        assertEquals(2, both.sendLimits.limits.maxItemsPerSend)
        assertEquals("Janssens", both.sendLimits.itemsServer)
        assertEquals("Janssens", both.sendLimits.videoServer)
        assertNull(both.sendLimits.uploadServer)
        assertTrue(both.tooMany)
        assertEquals(ItemIssue.TooLong, both.issue(both.items[2]))
        assertFalse(both.canSend)
        // Nothing chosen yet: the primary account's limits.
        assertEquals(50, s.sendLimits.limits.maxItemsPerSend)
        assertNull(s.sendLimits.itemsServer)
    }

    @Test
    fun syncThisAlbumOffersOnlyTargetsWhoseServerSyncsTheProvider() = kotlinx.coroutines.runBlocking {
        val kin = object : FakeApi() {
            override suspend fun frames() = listOf(
                Frame(id = "fa", name = "Kitchen", permissions = Permissions(manageLibrary = true)),
                Frame(id = "fb", name = "Oma", permissions = Permissions(manageLibrary = true)),
            )
            override suspend fun albums(frameId: String) = listOf(Album(id = "al-$frameId", name = "Summer"))
            override suspend fun feeds() = listOf(
                Feed(id = "da", name = "Peeters", role = "owner"),
                Feed(id = "db", name = "Janssens", role = "owner"),
            )
        }
        // Immich is on appmire only (fa, da); Janssens (fb, db) doesn't sync it.
        val providers = mapOf("fa" to listOf("immich"), "da" to listOf("immich"), "fb" to listOf("icloud"), "db" to listOf("icloud"))
        val servers = mapOf("fa" to "appmire", "da" to "appmire", "fb" to "Janssens", "db" to "Janssens")
        val choices = syncChoices(kin, true, { it }, { a, f -> "$a · $f" },
            supports = { id -> "immich" in providers[id].orEmpty() }, server = { servers[it] })
        assertEquals(listOf("Summer · Kitchen (appmire)", "Peeters (appmire)"), choices.map { it.label })
        assertEquals(listOf("fa", null), choices.map { it.target.frameId })
    }
}
