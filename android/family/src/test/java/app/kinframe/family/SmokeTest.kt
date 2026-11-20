package app.kinframe.family

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.SendsPage
import app.kinframe.core.api.User
import app.kinframe.core.design.KinDensity
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.design.KinframeTheme
import app.kinframe.family.accounts.AccountStore
import app.kinframe.family.data.MemorySecureStore
import app.kinframe.family.data.SecureStore
import app.kinframe.family.notify.Notifications
import app.kinframe.family.ui.AppViewModel
import app.kinframe.family.ui.FamilyRoot
import app.kinframe.family.ui.Phase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * Starts the real app root ([FamilyRoot], with the content `MainActivity` sets) signed in to a fake kinframe server
 * with every optional feature on, taps every bottom tab, then opens a frame and a family and taps each of their
 * sections. Any exception while composing, in an effect or in a view model's coroutine fails the test.
 *
 * Guards against crashes no screen-level test sees, like 1.2 crashing on every start after sign-in with
 * `IllegalStateException: no toaster` (a composable of the home screen read `LocalToaster` outside its provider).
 * Runs before every Play upload (fastlane `internal`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val secures = mutableMapOf<String, SecureStore>()
    /** Requests the fake server answered with an error (printed, to keep the fake complete). */
    private val unanswered = Collections.synchronizedSet(sortedSetOf<String>())
    /** When set, everything but the profile, frames and families fails: screens must show errors, not crash. */
    @Volatile private var failing = false

    private val me = User("u-me", "Jeroen", email = "jeroen@example.org", timezone = ZoneId.systemDefault().id)
    private val frames = listOf(
        Frame("f-oma", "Oma’s frame", "Living room", "Jeroen", role = "owner", permissions = Permissions.ALL, online = true),
        Frame("f-opa", "Opa Jos", "Kitchen", "Lies", role = "member", permissions = Permissions(send = true), online = false),
    )
    private val feeds = listOf(Feed("feed-1", "Trappers family", role = FeedRole.OWNER, memberCount = 3))

    private fun <T> json(s: KSerializer<T>, v: T) = KinJson.encodeToString(s, v)

    /** Every optional feature on, so every optional tab and section renders. */
    private fun discovery() = Discovery(
        name = "Smoke", version = "test", api = server.url("/v1").toString(), devAuth = true, limits = Limits(),
        weather = true, calendar = true, feeds = true, birthdays = true,
        photoSources = true, photoSourceProviders = PhotoSourceProvider.all, photoSourcesPrivateNetworks = true, googlePhotosImport = true,
        emailInbox = true, emailInboxDomain = "in.example.org",
        telegram = true, telegramBotUsername = "kinframe_bot", telegramMaxFileBytes = 20_000_000,
        social = true, chats = true, voiceMessages = true, linkPreviews = true, engagement = true, emailDigest = true,
        serverId = "s-smoke", serverName = "Smoke", baseUrl = server.url("/").toString(),
        leaving = true, remoteFamilies = true,
    )

    /** What the screens need to get past loading. */
    private fun essential(path: String): String? = when (path) {
        "/v1/me" -> json(User.serializer(), me)
        "/v1/frames" -> json(ListSerializer(Frame.serializer()), frames)
        "/v1/feeds" -> json(ListSerializer(Feed.serializer()), feeds)
        else -> frames.firstOrNull { path == "/v1/frames/${it.id}" }?.let { json(Frame.serializer(), it) }
            ?: feeds.firstOrNull { path == "/v1/feeds/${it.id}" }?.let { json(Feed.serializer(), it) }
    }

    /** Lists the screens load (empty is enough: the point is that every screen composes). */
    private val lists = Regex(
        "/v1/(conversations|google-imports|photo-sources|me/milestones|" +
            "frames/[^/]+/(albums|calendars|events|invites|join-requests|members|remote-subscriptions|subscriptions)|" +
            "feeds/[^/]+/(birthdays|blocked-servers|invites|join-requests|members|photo-sources|prompts|reports|subscriptions))",
    )

    /** Documents whose fields all have defaults. */
    private val documents = Regex(
        "/v1/(me/badge|me/keep-requests|me/reminders|timeline|frames/[^/]+/(calendar|health|placements|plugins|settings|taken-away)|" +
            "feeds/[^/]+/(posts|birthday-greetings|taken-away))",
    )

    private fun answer(path: String): String? = essential(path) ?: if (failing) null else when {
        path == "/v1/sends" -> json(SendsPage.serializer(), SendsPage())
        path == "/v1/me/on-this-day" -> """{"date":"${LocalDate.now()}"}"""
        path.startsWith("/v1/me/weekly-summaries/") -> """{"week_of":"${path.substringAfterLast('/')}"}"""
        lists.matches(path) -> "[]"
        documents.matches(path) -> "{}"
        // Not linked to Telegram.
        else -> null
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    path == "/.well-known/kinframe" -> json(Discovery.serializer(), discovery())
                    request.method == "GET" -> answer(path)
                    else -> null
                }
                if (body != null) return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
                unanswered += "${request.method} $path"
                val status = if (failing) 500 else 404
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/problem+json")
                    .body("""{"type":"about:blank","title":"Smoke","status":$status,"code":"smoke"}""").build()
            }
        }
        server.start()
        seedSignedIn()
    }

    @After fun tearDown() = server.close()

    /** What `ServerRepository` and a dev sign-in store for a signed-in account (as in ServersTest). */
    private fun seedSignedIn() {
        val url = server.url("/").toString()
        app.getSharedPreferences("kinframe", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        app.getSharedPreferences(AccountStore.prefsName(AccountStore.MAIN), Context.MODE_PRIVATE).edit(commit = true) {
            clear()
            putString("server_url", url)
            putString("server_discovery", json(Discovery.serializer(), discovery()))
        }
        secures.getOrPut(AccountStore.MAIN) { MemorySecureStore() }.apply {
            put("auth_server", url); put("auth_mode", "Dev"); put("dev_token", "t-smoke")
            put("dev_token_expires_at", (System.currentTimeMillis() + 3_600_000).toString())
        }
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun settle() {
        // Answers arrive on OkHttp's threads: let them land, then let the UI settle again.
        repeat(3) {
            compose.waitForIdle()
            Thread.sleep(30)
        }
        compose.waitForIdle()
    }

    /** A bottom tab; History and Settings sit in Home's top bar when the bottom bar is full. */
    private fun openTab(label: Int) {
        val l = app.getString(label)
        if (compose.onAllNodes(hasText(l) and isTab).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(hasText(l) and isTab).onFirst().performClick()
        } else {
            openTab(R.string.social_nav_home)
            compose.onAllNodes(hasContentDescription(l) and hasClickAction()).onFirst().performClick()
        }
        settle()
    }

    /** Every section tab of the open detail screen. */
    private fun openEverySection(): Int {
        val count = compose.onAllNodes(isTab).fetchSemanticsNodes().size
        repeat(count) { i ->
            val tab = compose.onAllNodes(isTab)[i]
            runCatching { tab.performScrollTo() }
            tab.performClick()
            settle()
        }
        return count
    }

    private fun tapThroughTheApp() {
        val graph = AppGraph(app) { id -> secures.getOrPut(id) { MemorySecureStore() } }
        val vm = AppViewModel(graph)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            KinframeTheme(density = KinDensity.Phone, colors = KinPalettes.light) { FamilyRoot(graph, vm) }
        }
        settle()
        compose.waitUntil(10_000) { vm.phase.value is Phase.Ready }
        settle()

        listOf(
            R.string.social_nav_home, R.string.nav_frames, R.string.nav_send, R.string.chat_nav,
            R.string.nav_feeds, R.string.nav_history, R.string.nav_settings,
        ).forEach(::openTab)

        // Pushed screens, the way a notification tap opens them.
        graph.openTarget.value = OpenTarget(Notifications.TARGET_FRAME, frameId = "f-oma")
        settle()
        val frameSections = openEverySection()
        graph.openTarget.value = OpenTarget(Notifications.TARGET_FEED, feedId = "feed-1")
        settle()
        val feedSections = openEverySection()
        graph.openTarget.value = OpenTarget(Notifications.TARGET_PHOTO_SOURCES)
        settle()

        assertTrue("a frame you own has sections ($frameSections)", frameSections >= 5)
        assertTrue("a family you own has sections ($feedSections)", feedSections >= 5)
        assertTrue("still signed in: ${vm.phase.value}", vm.phase.value is Phase.Ready)
        println("SmokeTest (failing=$failing): requests answered with an error: $unanswered")
    }

    @Test
    fun theSignedInAppStartsAndEveryTabOpens() = tapThroughTheApp()

    /** Offline, a server error or a feature switched off meanwhile: error states, no crash. */
    @Test
    fun everyTabSurvivesFailingRequests() {
        failing = true
        tapThroughTheApp()
    }
}
