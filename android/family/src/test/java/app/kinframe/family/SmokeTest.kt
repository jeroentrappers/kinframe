package app.kinframe.family

import app.kinframe.family.ui.family.FamilyPrefs
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
 * with every optional feature on, taps every bottom tab, `+`, the family's segments and settings, then opens a frame
 * and taps through its rows. Any exception while composing, in an effect or in a view model's coroutine fails the test.
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
    private var frames = listOf(
        Frame("f-oma", "Oma’s frame", "Living room", "Jeroen", role = "owner", permissions = Permissions.ALL, online = true),
        Frame("f-opa", "Opa Jos", "Kitchen", "Lies", role = "member", permissions = Permissions(send = true), online = false),
    )
    private var feeds = listOf(Feed("feed-1", "Trappers family", role = FeedRole.OWNER, memberCount = 3))

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
            "feeds/[^/]+/(birthdays|blocked-servers|circles|invites|join-requests|members|photo-sources|prompts|reports|subscriptions))",
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

    /** A tab of the bottom bar: Home, Frames, Chats, Family. */
    private fun openTab(label: Int) {
        compose.onAllNodes(hasText(app.getString(label)) and isTab).onFirst().performClick()
        settle()
    }

    private fun click(label: Int) {
        val l = app.getString(label)
        val byText = compose.onAllNodes(hasText(l) and hasClickAction()).fetchSemanticsNodes()
        val node = if (byText.isNotEmpty()) compose.onAllNodes(hasText(l) and hasClickAction()).onFirst()
        else compose.onAllNodes(hasContentDescription(l) and hasClickAction()).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    /** Opens a row of the open screen and comes back. */
    private fun openAndBack(activity: ComponentActivity, label: Int, inside: () -> Unit = {}) {
        click(label)
        inside()
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun start(): Triple<AppGraph, AppViewModel, ComponentActivity> {
        val graph = AppGraph(app) { id -> secures.getOrPut(id) { MemorySecureStore() } }
        val vm = AppViewModel(graph)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            KinframeTheme(density = KinDensity.Phone, colors = KinPalettes.light) { FamilyRoot(graph, vm) }
        }
        settle()
        compose.waitUntil(10_000) { vm.phase.value is Phase.Ready }
        settle()
        return Triple(graph, vm, activity)
    }

    private fun tapThroughTheApp() {
        val (graph, vm, activity) = start()

        listOf(R.string.social_nav_home, R.string.nav_frames, R.string.chat_nav, R.string.nav_family).forEach(::openTab)
        // The family's segments.
        listOf(R.string.family_seg_people, R.string.family_seg_frames, R.string.family_seg_photos).forEach(::click)
        // `+`: the composer, then back.
        openAndBack(activity, R.string.nav_new_post)
        // Family › gear: Settings with This family and App.
        openTab(R.string.nav_family)
        click(R.string.nav_settings)
        listOf(R.string.settings_family_name_cover, R.string.settings_family_circles, R.string.settings_family_birthdays,
            R.string.settings_family_more_ways, R.string.settings_sent_history).forEach { openAndBack(activity, it) }
        // "Send crash reports" (on by default) turns off.
        assertTrue(FamilyCrashReports.enabled(app))
        compose.onAllNodes(hasText(app.getString(R.string.settings_crash_reports))).onFirst().performScrollTo().performClick()
        settle()
        assertTrue("Send crash reports turned off", !FamilyCrashReports.enabled(app))

        // A frame, the way a notification tap opens it: its screen is one list.
        graph.openTarget.value = OpenTarget(Notifications.TARGET_FRAME, frameId = "f-oma")
        settle()
        var rows = 0
        openAndBack(activity, R.string.frame_row_photos).also { rows++ }
        openAndBack(activity, R.string.frame_row_extras) { openAndBack(activity, R.string.frame_extras_calendar) }.also { rows++ }
        openAndBack(activity, R.string.frame_row_settings) {
            listOf(R.string.frame_settings_people, R.string.frame_settings_display, R.string.frame_settings_device).forEach { openAndBack(activity, it); rows++ }
            openAndBack(activity, R.string.frames_shows_none).also { rows++ }
        }.also { rows++ }
        graph.openTarget.value = OpenTarget(Notifications.TARGET_FEED_PEOPLE, feedId = "feed-1")
        settle()
        graph.openTarget.value = OpenTarget(Notifications.TARGET_PHOTO_SOURCES)
        settle()

        assertTrue("a frame you own has its rows ($rows)", rows >= 7)
        assertTrue("still signed in: ${vm.phase.value}", vm.phase.value is Phase.Ready)
        println("SmokeTest (failing=$failing): requests answered with an error: $unanswered")
    }

    /**
     * Neither a family nor a frame: only getting started shows, but sent history and settings still open; starting a
     * family asks for its name.
     */
    @Test
    fun withoutAFamilyOrAFrameOnlyGettingStartedShows() {
        feeds = emptyList(); frames = emptyList()
        val (_, _, activity) = start()
        assertTrue(compose.onAllNodes(isTab).fetchSemanticsNodes().isEmpty())
        assertTrue("no way back to an app that isn't there", compose.onAllNodes(hasText(app.getString(R.string.common_cancel))).fetchSemanticsNodes().isEmpty())
        openAndBack(activity, R.string.settings_sent_history)
        openAndBack(activity, R.string.nav_settings)
        click(R.string.start_family)
        compose.onAllNodes(hasText(app.getString(R.string.start_name_title))).onFirst().assertExists()
    }

    /** On a frame but in no family: the normal app; Home offers Start a family, and Cancel goes back to the app. */
    @Test
    fun onAFrameWithoutAFamilyTheAppShows() {
        feeds = emptyList()
        val (_, _, activity) = start()
        assertTrue("the tabs", compose.onAllNodes(isTab).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(hasText(app.getString(R.string.home_start_body))).onFirst().assertExists()
        click(R.string.start_family)
        compose.onAllNodes(hasText(app.getString(R.string.start_name_title))).onFirst().assertExists()
        assertTrue("getting started hides the tabs", compose.onAllNodes(isTab).fetchSemanticsNodes().isEmpty())
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        // Back from the name step: the app again (getting started's choices only when there is nothing else).
        assertTrue("back in the app", compose.onAllNodes(isTab).fetchSemanticsNodes().isNotEmpty())
        openTab(R.string.nav_frames)
        compose.onAllNodes(hasText("Oma’s frame")).onFirst().assertExists()
    }

    /** The simple view: no tab bar, a big "Send a heart", and "Normal view" in its settings sheet. */
    @Test
    fun theSimpleViewOpensWithoutTabs() {
        app.getSharedPreferences(FamilyPrefs.NAME, Context.MODE_PRIVATE).edit(commit = true) { putBoolean("simple_view", true) }
        start()
        assertTrue(compose.onAllNodes(isTab).fetchSemanticsNodes().isEmpty())
        click(R.string.nav_settings)
        click(R.string.simple_normal_view)
        assertTrue(compose.onAllNodes(isTab).fetchSemanticsNodes().isNotEmpty())
        app.getSharedPreferences(FamilyPrefs.NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() }
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
