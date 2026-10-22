package app.kinframe.family

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import app.kinframe.core.api.Frame
import app.kinframe.core.api.FrameHealth
import app.kinframe.core.api.Heartbeat
import app.kinframe.core.api.Invite
import app.kinframe.core.api.Item
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.JoinRequestUser
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Member
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementCounts
import app.kinframe.core.api.RedeemResult
import app.kinframe.core.api.Screen
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendFrame
import app.kinframe.core.api.SendPlacement
import app.kinframe.core.api.User
import app.kinframe.core.design.KinDensity
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.design.KinframeTheme
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.notify.PushStatus
import app.kinframe.family.send.MediaInfo
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxProgress
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.ui.FrameTab
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.LocalToaster
import app.kinframe.family.ui.Toaster
import app.kinframe.family.ui.frame.FrameActions
import app.kinframe.family.ui.frame.FrameDetailScreen
import app.kinframe.family.ui.frame.FrameDetailState
import app.kinframe.family.ui.frame.LibraryState
import app.kinframe.family.ui.frame.LibraryTab
import app.kinframe.family.ui.frames.AddFrameScreen
import app.kinframe.family.ui.frames.AddFrameState
import app.kinframe.family.ui.frames.FramesScreen
import app.kinframe.family.ui.frames.FramesState
import app.kinframe.family.ui.history.HistoryScreen
import app.kinframe.family.ui.history.HistoryState
import app.kinframe.family.ui.history.SendDetailScreen
import app.kinframe.family.ui.history.SendDetailState
import app.kinframe.family.ui.onboarding.OnboardingState
import app.kinframe.family.ui.onboarding.SignInReason
import app.kinframe.family.ui.onboarding.SignInScreen
import app.kinframe.family.ui.onboarding.WelcomeScreen
import app.kinframe.family.ui.send.ComposeScreen
import app.kinframe.family.ui.send.ComposerState
import app.kinframe.family.ui.send.DraftItem
import app.kinframe.family.ui.send.ItemEditor
import app.kinframe.family.ui.settings.SettingsScreen
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.DiscoveryAuth
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import kotlinx.coroutines.MainScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the main screens to PNGs in `family/build/screenshots/` (phone 411×891 dp, light and dark, plus a few
 * tablet and Dutch variants) for design review. Records, not golden comparisons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val now = Instant.now()
    private fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()

    private val frames = listOf(
        Frame("f-oma", "Oma’s frame", "Living room", "Jeroen", role = "owner", permissions = Permissions.ALL, online = true,
            screen = Screen(1280, 800, orientation = "landscape"), memberCount = 4, placementCount = 312, createdAt = ago(60 * 24 * 40),
            inviteRequiresApproval = true),
        Frame("f-opa", "Opa Jos", "Kitchen", "Lies", role = "member", permissions = Permissions(send = true), online = false, lastSeenAt = ago(180),
            screen = Screen(800, 1280, orientation = "portrait")),
        Frame("f-cabin", "Cabin in the Ardennes", null, "Jeroen", role = "admin", permissions = Permissions.ALL, online = true, needsUpdate = true),
    )

    private val server = ServerChoice(
        "https://kinframe.appmire.be/".toHttpUrl(),
        Discovery("Trappers family", "0.1.0", "https://kinframe.appmire.be/v1", auth = DiscoveryAuth("https://auth.kinframe.appmire.be/oidc", "app", "web", "x"),
            limits = Limits(), weather = true),
    )

    private fun item(id: String, caption: String? = null, kind: String = "photo", status: String = "ready") =
        Item(id, kind = kind, status = status, caption = caption, thumbUrl = "photo:$id", senderName = "Jeroen", createdAt = ago(5))

    private val sends = listOf(
        Send("s1", ago(8), items = listOf(item("a", "Sunday at the beach ☀️"), item("b"), item("c", kind = "video"), item("d"), item("e")),
            frames = listOf(
                SendFrame("f-oma", "Oma’s frame", true, listOf(SendPlacement("p1", "a", "seen", "love"), SendPlacement("p2", "b", "seen", "love", listOf("beautiful")), SendPlacement("p3", "c", "delivered"))),
                SendFrame("f-opa", "Opa Jos", false, listOf(SendPlacement("p4", "a", "ready"), SendPlacement("p5", "b", "ready"), SendPlacement("p6", "c", "pending"))),
            )),
        Send("s2", ago(60 * 26), items = listOf(item("f", "First steps!")),
            frames = listOf(SendFrame("f-oma", "Oma’s frame", true, listOf(SendPlacement("p7", "f", "seen", "wow", listOf("call_me")))))),
    )

    private val draft = listOf(
        DraftItem("d1", MediaInfo(Uri.parse("content://m/1"), "beach.jpg", "image/jpeg", 2_400_000, "photo", width = 4000, height = 3000), caption = "Sunday at the beach ☀️", focalX = 0.35f, focalY = 0.4f),
        DraftItem("d2", MediaInfo(Uri.parse("content://m/2"), "kids.jpg", "image/jpeg", 2_100_000, "photo", width = 3000, height = 4000)),
        DraftItem("d3", MediaInfo(Uri.parse("content://m/3"), "walk.mp4", "video/mp4", 48_000_000, "video", durationMs = 95_000, width = 1920, height = 1080), trim = 12_000L to 54_000L),
        DraftItem("d4", MediaInfo(Uri.parse("content://m/4"), "cake.jpg", "image/jpeg", 1_800_000, "photo", width = 4000, height = 3000), fill = false),
    )

    private val composer = ComposerState(items = draft, frames = Load.Ready(frames), selected = setOf("f-oma", "f-opa"), limits = Limits())

    private val noop = FrameActions(null, {}, { _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {}, {}, {})

    // ---------------------------------------------------------------- shots

    @Test fun welcome() = both("welcome") {
        WelcomeScreen(OnboardingState(defaultServer = server, defaultChecking = false), {}, {})
    }

    @Test fun signIn() = both("sign_in") {
        SignInScreen(server.copy(discovery = server.discovery.copy(devAuth = true)), OnboardingState(devEmail = "anna@example.org"), false, SignInReason.Frame, {}, {}, {}, {}, {})
    }

    @Test fun framesList() = both("frames_list") { FramesScreen(FramesState(Load.Ready(frames)), {}, {}, {}, {}) }

    @Test fun framesEmpty() = both("frames_empty") { FramesScreen(FramesState(Load.Ready(emptyList())), {}, {}, {}, {}) }

    @Test @Config(qualifiers = "w900dp-h1200dp-xhdpi")
    fun framesTablet() = shot("frames_tablet_light", dark = false) { FramesScreen(FramesState(Load.Ready(frames)), {}, {}, {}, {}) }

    @Test fun addFrameCode() = both("add_frame_code") {
        AddFrameScreen(AddFrameState(digits = "482139"), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun addFrameClaimed() = both("add_frame_claimed") {
        AddFrameScreen(AddFrameState(digits = "48213907", result = RedeemResult("claimed", frames[0]), name = "Oma’s frame", location = "Living room"),
            {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun sendEmpty() = both("send_empty") { ComposeScreen(ComposerState(frames = Load.Ready(frames)), {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun sendCompose() = both("send_compose") { ComposeScreen(composer, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun sendEditPhoto() = both("send_edit_photo") {
        ItemEditor(composer, 0, {}, {}, { _, _ -> }, {}, { _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, { _, _ -> }, {})
    }

    @Test fun sendEditVideo() = both("send_edit_video") {
        ItemEditor(composer, 2, {}, {}, { _, _ -> }, {}, { _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, { _, _ -> }, {})
    }

    @Test fun history() = both("history") {
        val out = OutboxSend("o1", "x", listOf("f-oma"), listOf("Oma’s frame"), listOf(
            OutboxItem("i1", "content://m/9", mime = "image/jpeg", name = "a.jpg", size = 3_000_000),
            OutboxItem("i2", "content://m/8", mime = "image/jpeg", name = "b.jpg", size = 3_000_000),
        ), createdAt = 0)
        HistoryScreen(HistoryState(sends = Load.Ready(sends)), listOf(out), mapOf("o1" to OutboxProgress("o1", 1, 2, 4_200_000, 6_000_000)), {}, {}, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "nl-w411dp-h891dp-xxhdpi")
    fun historyDutch() = shot("history_nl_light", dark = false) {
        HistoryScreen(HistoryState(sends = Load.Ready(sends)), emptyList(), emptyMap(), {}, {}, {}, {}, {}, {})
    }

    @Test fun historyDetail() = both("history_detail") {
        SendDetailScreen(SendDetailState(send = Load.Ready(sends[0])), {}, {}, {}, {}, {}, {}, {})
    }

    private val detail = FrameDetailState(
        frame = Load.Ready(frames[0]),
        health = FrameHealth(true, ago(1), Heartbeat("0.2.0", uptimeS = 3 * 86_400 + 500, wifiSsid = "Oma-wifi", wifiRssiDbm = -58,
            storageFreeBytes = 9_800_000_000, storageTotalBytes = 16_000_000_000, placementsCached = 312)),
        members = listOf(
            Member("u1", "Jeroen", role = "owner", lastSentAt = ago(8)),
            Member("u2", "Lies", role = "admin", lastSentAt = ago(60 * 30)),
            Member("u3", "Oma Maria", role = "member"),
        ),
        requests = listOf(JoinRequest("r1", JoinRequestUser("u9", "Cousin Lisa"), via = "invite", invitedByName = "Lies", createdAt = ago(20))),
        invites = listOf(Invite("i1", "tok", "https://kinframe.appmire.be/i/tok", "Jeroen", 1, 0, "For cousin Lisa", now.plusSeconds(6 * 86_400).toString())),
    )

    @Test fun frameOverview() = both("frame_overview") {
        FrameDetailScreen(detail, FrameTab.Overview, {}, {}, {}, {}, noop.copyMe("u1"), {}, {})
    }

    @Test fun framePeople() = both("frame_people") {
        FrameDetailScreen(detail, FrameTab.People, {}, {}, {}, {}, noop.copyMe("u1"), {}, {})
    }

    @Test fun frameLibrary() = both("frame_library") {
        val placements = (1..14).map { i -> Placement("pl$i", "f-oma", item("x$i", kind = if (i % 5 == 0) "video" else "photo"), receivedAt = ago(i * 60L)) }
        FrameDetailScreen(detail, FrameTab.Library, {}, {}, {}, {}, noop, {
            LibraryTab(frames[0], LibraryState(placements = Load.Ready(placements), counts = PlacementCounts(photos = 290, videos = 22, hidden = 3, trashed = 1),
                selected = setOf("pl2", "pl3")), 30, {}, {}, {}, {}, {}, {}, {})
        }, {})
    }

    @Test fun appSettings() = both("settings") {
        SettingsScreen(User("u1", "Jeroen", "jeroen@example.org"), server, false, PushStatus.Active("ntfy"), true, false, {}, {}, {}, {}, {})
    }

    // ---------------------------------------------------------------- rendering

    private fun FrameActions.copyMe(me: String) = FrameActions(me, command, rename, setFlag, leave, remove, decide, updateMember, removeMember, createInvite, shareInvite, copyInvite, revokeInvite)

    private fun both(name: String, content: @Composable () -> Unit) {
        shot("${name}_light", false, content)
        shot("${name}_dark", true, content)
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        val toaster = Toaster(SnackbarHostState(), MainScope())
        val photos = AsyncImagePreviewHandler { request -> fakePhoto(request.data.toString()).asImage() }
        activity.setContent {
            KinframeTheme(density = KinDensity.Phone, colors = if (dark) KinPalettes.dark else KinPalettes.light) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides photos, LocalToaster provides toaster) {
                    Box(Modifier.fillMaxSize().background(if (dark) KinPalettes.dark.paper else KinPalettes.light.paper)) { content() }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("build/screenshots/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
    }

    /** A soft landscape "photo" whose colours depend on [key], so thumbnails differ. */
    private fun fakePhoto(key: String): Bitmap {
        val h = key.hashCode()
        val palettes = listOf(
            intArrayOf(0xFF9CCBF0.toInt(), 0xFFF6EBD3.toInt(), 0xFFE2B47C.toInt()),
            intArrayOf(0xFFF7C59F.toInt(), 0xFFEF8354.toInt(), 0xFF4F5D75.toInt()),
            intArrayOf(0xFFB8E0D2.toInt(), 0xFF95B8D1.toInt(), 0xFF5C6F68.toInt()),
            intArrayOf(0xFFFFE5B4.toInt(), 0xFFE0A96D.toInt(), 0xFF7A5C3E.toInt()),
        )
        val p = palettes[Math.floorMod(h, palettes.size)]
        val portrait = key.endsWith("2")
        val w = if (portrait) 300 else 400
        val ht = if (portrait) 400 else 300
        val bmp = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, ht.toFloat(), p, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), ht.toFloat(), paint)
        paint.shader = null
        paint.color = 0xCCFFFFFF.toInt()
        c.drawCircle(w * 0.72f, ht * 0.28f, ht * 0.09f, paint)
        paint.color = 0x55203020
        c.drawOval(-w * 0.2f, ht * 0.7f, w * 0.7f, ht * 1.3f, paint)
        paint.color = 0x77203020
        c.drawOval(w * 0.35f, ht * 0.78f, w * 1.2f, ht * 1.35f, paint)
        return bmp
    }
}
