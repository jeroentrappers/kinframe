package app.kinframe.family

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
import app.kinframe.core.api.Album
import app.kinframe.core.api.AlbumSchedule
import app.kinframe.core.api.CalDavCalendar
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarSource
import app.kinframe.core.api.CalendarSourceCreate
import app.kinframe.core.api.CalendarSourceInfo
import app.kinframe.core.api.CalendarTestResult
import app.kinframe.core.api.CalendarView
import app.kinframe.core.api.CalendarWindow
import app.kinframe.core.api.Countdown
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.Frame
import app.kinframe.core.api.Item
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.Member
import app.kinframe.core.api.Permissions
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementCounts
import app.kinframe.core.api.SettingsDocument
import app.kinframe.core.api.TvLink
import app.kinframe.core.api.User
import app.kinframe.core.api.Weather
import app.kinframe.core.api.WeatherDay
import app.kinframe.core.design.KinDensity
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.design.KinframeTheme
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.notify.PushStatus
import app.kinframe.family.ui.FrameTab
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.LocalToaster
import app.kinframe.family.ui.Toaster
import app.kinframe.family.ui.calendar.CalendarActions
import app.kinframe.family.ui.calendar.CalendarState
import app.kinframe.family.ui.calendar.CalendarTab
import app.kinframe.family.ui.calendar.CalendarViewModel
import app.kinframe.family.ui.frame.AlbumPickerDialog
import app.kinframe.family.ui.frame.AlbumScheduleDialog
import app.kinframe.family.ui.frame.FrameActions
import app.kinframe.family.ui.frame.FrameDetailScreen
import app.kinframe.family.ui.frame.FrameDetailState
import app.kinframe.family.ui.frame.FrameSettingsViewModel
import app.kinframe.family.ui.frame.LibraryAlbumActions
import app.kinframe.family.ui.frame.LibraryState
import app.kinframe.family.ui.frame.LibraryTab
import app.kinframe.family.ui.frame.PlacementViewer
import app.kinframe.family.ui.frame.ReplaceDeviceDialog
import app.kinframe.family.ui.frame.SettingsTab
import app.kinframe.family.ui.frame.TransferDialog
import app.kinframe.family.ui.frame.ViewerActions
import app.kinframe.family.ui.settings.SettingsScreen
import app.kinframe.family.ui.tv.TvDialog
import app.kinframe.family.ui.tv.TvDialogActions
import app.kinframe.family.ui.tv.TvMode
import app.kinframe.family.ui.tv.TvPresenter
import app.kinframe.family.ui.tv.TvScope
import app.kinframe.family.ui.tv.TvState
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * The screens added to match the web app (docs/client-parity.md): calendars, Show on TV, albums, the photo viewer,
 * ownership and the account and frame settings that were missing. PNGs in `family/build/screenshots/parity/`,
 * dialogs drawn over their screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ParityScreenshotTest {
    private val now = Instant.now()
    private val zone = ZoneId.of("Europe/Brussels")
    private val today = LocalDate.now(zone)
    private fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()
    private fun at(day: Long, hour: Int, min: Int = 0) = today.plusDays(day).atTime(hour, min).atZone(zone).toInstant().toString()

    private val owner = Frame("f-oma", "Oma’s frame", "Living room", "Jeroen", role = "owner", permissions = Permissions.ALL, online = true,
        memberCount = 3, placementCount = 312, createdAt = ago(60 * 24 * 40))
    private val noop = FrameActions("u1", {}, { _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {}, {}, {})
    private val members = listOf(Member("u1", "Jeroen", role = "owner"), Member("u2", "Lies", role = "admin"), Member("u3", "Oma Maria"))

    // ---------------------------------------------------------------- calendar

    private val calSources = listOf(
        CalendarSource("loc", kind = "local", color = "rose", status = "ok", lastSyncedAt = ago(3), eventCount = 2),
        CalendarSource("fam", kind = "ics", name = "Family", color = "coral", status = "ok", lastSyncedAt = ago(12), eventCount = 24, urlHint = "https://calendar.google.com/…/basic.ics"),
        CalendarSource("work", kind = "caldav", name = "Lies’ work", color = "ocean", status = "auth_failed", username = "lies@example.org", hasPassword = true),
    )

    private val calView = CalendarView(
        timezone = zone.id, window = CalendarWindow(today.minusDays(30).toString(), today.plusDays(60).toString()),
        sources = calSources.map { CalendarSourceInfo(it.id, it.kind, it.name, it.color) },
        events = listOf(
            CalendarEvent("e1", "fam", "Lunch at Oma’s", startAt = at(0, 12, 30), endAt = at(0, 14), location = "Gent"),
            CalendarEvent("e2", "fam", "School trip", allDay = true, startDate = today.plusDays(1).toString(), endDate = today.plusDays(3).toString()),
            CalendarEvent("e3", "loc", "Piano recital", startAt = at(2, 19), endAt = at(2, 20, 30), senderName = "Lies"),
            CalendarEvent("e4", "fam", "Tom’s birthday", allDay = true, startDate = today.plusDays(6).toString(), countdown = true),
            CalendarEvent("e5", "work", "Dentist", startAt = at(6, 9), endAt = at(6, 9, 30)),
        ),
        countdowns = listOf(Countdown("e4", "fam", "Tom’s birthday", allDay = true, startDate = today.plusDays(6).toString(), color = "coral")),
        weather = Weather(daily = listOf(
            WeatherDay(today.toString(), 2, 14.0, 6.0, 10), WeatherDay(today.plusDays(1).toString(), 61, 11.0, 7.0, 80),
            WeatherDay(today.plusDays(2).toString(), 0, 16.0, 5.0, 0),
        )),
    )

    private val invitations = listOf(
        EventInvitation("i1", senderId = "u1", senderName = "Jeroen", status = "pending", title = "Sunday lunch at ours", allDay = false, startAt = at(4, 12), endAt = at(4, 15)),
        EventInvitation("i2", senderId = "u2", senderName = "Lies", status = "accepted", title = "Piano recital", startAt = at(2, 19), endAt = at(2, 20, 30)),
    )

    private val settingsDoc = SettingsDocument(KinJson.parseToJsonElement("""{"version":5,
        "slideshow":{"interval_s":600,"fill":false,"background":"palette","album_id":"a1","on_this_day":true},
        "locale":{"language":"nl","timezone":"Europe/Brussels","first_day_of_week":"mon"},
        "calendar":{"view":"week","timed_views":[{"id":"t1","from":"07:00","to":"09:00","days":["mon","tue","wed","thu","fri"],"enabled":true}]},
        "widgets":{"countdowns":true,"calendar_sidebar":true}}""").jsonObject)

    private val albums = listOf(
        Album("a1", "Christmas", version = 2, placementCount = 6, placementIds = (1..6).map { "pl$it" }, coverPlacementId = "pl2", schedule = AlbumSchedule("12-01", "01-06")),
        Album("a2", "Summer in Italy", placementCount = 48),
    )

    private inner class Api : FakeApi() {
        override suspend fun settings(frameId: String) = settingsDoc
        override suspend fun albums(frameId: String) = albums
        override suspend fun calendars(frameId: String) = calSources
        override suspend fun frameCalendar(frameId: String) = calView
        override suspend fun eventInvitations(frameId: String) = invitations
        override suspend fun testCalendar(frameId: String, request: CalendarSourceCreate) = CalendarTestResult(
            ok = true, eventCount = 31, calendars = listOf(CalDavCalendar("https://c/1", "Home", "#4FB28A"), CalDavCalendar("https://c/2", "Work", "#4767C8"), CalDavCalendar("https://c/3", "Birthdays")),
        )
    }

    private fun settingsVm() = FrameSettingsViewModel(Api(), "f-oma") { "en" }.also { idle() }

    @Composable
    private fun calendarTab(s: FrameSettingsViewModel, vm: CalendarViewModel? = null, sendOpen: Boolean = false) {
        CalendarTab(owner, true, vm?.state?.value ?: CalendarState(calSources, calView, invitations), vm?.form?.value, s.state.value,
            CalendarActions(vm, s, "u1", "Jeroen"), sendOpenAtStart = sendOpen)
    }

    @Test fun calendarTab() {
        val s = settingsVm()
        both("calendar_tab") {
            FrameDetailScreen(FrameDetailState(Load.Ready(owner)), FrameTab.Calendar, {}, {}, {}, {}, noop, {}, {}, calendar = { calendarTab(s) })
        }
    }

    @Test fun calendarPreview() {
        val s = settingsVm()
        both("calendar_preview") { app.kinframe.family.ui.PageColumn { app.kinframe.family.ui.calendar.CalendarPreview(calView, s.state.value) {} } }
    }

    @Test fun calendarAddCaldav() {
        val s = settingsVm()
        val vm = CalendarViewModel(Api(), "f-oma", manage = true, mayPut = true, live = emptyFlow()) { _, _ -> "" }
        idle()
        vm.openAdd()
        vm.editForm { it.copy(kind = "caldav", url = "https://cloud.example.org", username = "jeroen", password = "app-password") }
        vm.test()
        idle()
        vm.togglePick("https://c/2")
        both("calendar_add_caldav") { calendarTab(s, vm) }
    }

    @Test fun calendarSendEvent() {
        val s = settingsVm()
        val vm = CalendarViewModel(Api(), "f-oma", manage = true, mayPut = true, live = emptyFlow()) { _, _ -> "" }
        idle()
        both("calendar_send_event") { calendarTab(s, vm, sendOpen = true) }
    }

    // ---------------------------------------------------------------- TV

    private fun tvLink(id: String, purpose: String = "tv", by: String = "Jeroen") =
        TvLink(id, "f-oma", "t$id", "https://kinframe.appmire.be/tv/f-oma?t=Zm9vYmFyYmF6$id", purpose, expiresAt = now.plusSeconds(12 * 3600).toString(), createdByName = by)

    private fun tvActions(p: TvPresenter.State = TvPresenter.State()) = TvDialogActions(null, p, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test fun tvShow() = both("tv_show") {
        TvDialog("Oma’s frame", TvScope.All, true, true, "https://kinframe.appmire.be", TvState(link = tvLink("n1"), active = listOf(tvLink("old", by = "Lies"))), tvActions())
    }

    @Test fun tvMirror() = both("tv_mirror") {
        TvDialog("Oma’s frame", TvScope.Album("a1", "Christmas"), true, true, "https://kinframe.appmire.be", TvState(mode = TvMode.Mirror), tvActions())
    }

    @Test fun tvFrameCode() = both("tv_frame_code") {
        TvDialog("Oma’s frame", TvScope.All, true, true, "https://kinframe.appmire.be", TvState(mode = TvMode.Frame, code = "4821"), tvActions())
    }

    @Test fun tvPresenting() = both("tv_presenting") {
        TvDialog("Oma’s frame", TvScope.Selection(listOf("a", "b", "c")), true, true, null, TvState(),
            tvActions(TvPresenter.State(TvPresenter.Status.Live, device = "Living room TV", frameId = "f-oma", frameName = "Oma’s frame")))
    }

    // ---------------------------------------------------------------- albums and viewer

    private fun item(i: Int) = Item("x$i", kind = if (i % 5 == 0) "video" else "photo", thumbUrl = "photo:x$i", senderName = "Jeroen", caption = if (i == 2) "Christmas Eve" else null,
        capturedAt = ago(60L * 24 * 365 * (i % 3 + 1)), width = 4000, height = 3000)

    private val placements = (1..14).map { i -> Placement("pl$i", "f-oma", item(i), receivedAt = ago(i * 60L), albumIds = if (i <= 6) listOf("a1") else emptyList()) }

    private val albumActions = LibraryAlbumActions({}, {}, { _, _ -> }, { _, _, _ -> }, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})

    @Test fun libraryAlbumsAndMemories() = both("library_albums_memories") {
        FrameDetailScreen(FrameDetailState(Load.Ready(owner)), FrameTab.Library, {}, {}, {}, {}, noop, {
            LibraryTab(owner, LibraryState(placements = Load.Ready(placements), counts = PlacementCounts(photos = 290, videos = 22, hidden = 3),
                albums = albums, playingAlbumId = "a1", memories = placements.take(4)), 30, {}, {}, {}, {}, {}, {}, {}, onShare = {}, albums = albumActions)
        }, {})
    }

    @Test fun libraryAlbumOpen() = both("library_album_open") {
        FrameDetailScreen(FrameDetailState(Load.Ready(owner)), FrameTab.Library, {}, {}, {}, {}, noop, {
            LibraryTab(owner, LibraryState(placements = Load.Ready(placements.take(6)), albums = albums, albumId = "a1", playingAlbumId = "a1"),
                30, {}, {}, {}, {}, {}, {}, {}, onShare = {}, albums = albumActions)
        }, {})
    }

    @Test fun libraryAlbumArrange() = both("library_album_arrange") {
        FrameDetailScreen(FrameDetailState(Load.Ready(owner)), FrameTab.Library, {}, {}, {}, {}, noop, {
            LibraryTab(owner, LibraryState(placements = Load.Ready(placements.take(6)), albums = albums, albumId = "a1", arranging = placements.take(6).map { it.id }.reversed()),
                30, {}, {}, {}, {}, {}, {}, {}, albums = albumActions)
        }, {})
    }

    @Test fun photoViewer() = both("photo_viewer") {
        PlacementViewer(placements[1], ViewerActions({}, {}, null, null, {}, listOf("hide", "trash"), {})) {}
    }

    @Test fun albumSchedule() = both("album_schedule") { AlbumScheduleDialog(albums[0], {}) { _, _ -> } }

    @Test fun albumPicker() = both("album_picker") { AlbumPickerDialog(3, albums, {}) { _, _ -> } }

    // ---------------------------------------------------------------- ownership

    @Test fun overviewOwner() = both("overview_owner") {
        FrameDetailScreen(FrameDetailState(Load.Ready(owner), members = members), FrameTab.Overview, {}, {}, {}, {}, noop, {}, {})
    }

    @Test fun transferOwnership() = both("transfer_ownership") {
        TransferDialog(owner, members, "u1", leaving = true, preselect = null, {}) { _, _, _ -> }
    }

    @Test fun replaceDevice() = both("replace_device") {
        ReplaceDeviceDialog(owner, FrameDetailState(Load.Ready(owner)), {}) {}
    }

    // ---------------------------------------------------------------- settings

    private val server = ServerChoice("https://kinframe.appmire.be/".toHttpUrl(), Discovery("Trappers family", "0.1.0", "https://kinframe.appmire.be/v1", limits = Limits(), weather = true, calendar = true))

    @Test fun accountSettings() = both("account_settings") {
        SettingsScreen(User("u1", "Jeroen", "jeroen@example.org", avatarUrl = "photo:me", locale = "nl"), server, false, PushStatus.Active("ntfy"), true, false,
            {}, {}, {}, {}, {}, onChangePhoto = {}, onRemovePhoto = {}, onLanguage = {}, onOpenWeb = {})
    }

    @Test fun frameSettingsMore() {
        val vm = settingsVm()
        both("frame_settings_more") { SettingsTab(owner, vm.state.value, true, vm) }
    }

    // ---------------------------------------------------------------- rendering

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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
        idle()
        val main = activity.window.decorView
        val bmp = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        main.draw(canvas)
        // Dialogs are windows of their own: draw them over the screen with the scrim the system would add.
        for (v in rootViews().filter { it !== main && it.isShown }) {
            canvas.drawColor(0x99000000.toInt())
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            val x = if (loc[0] > 0) loc[0] else (main.width - v.width) / 2
            val y = if (loc[1] > 0) loc[1] else (main.height - v.height) / 2
            canvas.save(); canvas.translate(x.toFloat(), y.toFloat()); v.draw(canvas); canvas.restore()
        }
        val out = File("build/screenshots/parity/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
        controller.pause().stop().destroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val f = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(wmg) as List<View>).toList()
    }

    private fun fakePhoto(key: String): Bitmap {
        val h = key.hashCode()
        val palettes = listOf(
            intArrayOf(0xFF9CCBF0.toInt(), 0xFFF6EBD3.toInt(), 0xFFE2B47C.toInt()),
            intArrayOf(0xFFF7C59F.toInt(), 0xFFEF8354.toInt(), 0xFF4F5D75.toInt()),
            intArrayOf(0xFFB8E0D2.toInt(), 0xFF95B8D1.toInt(), 0xFF5C6F68.toInt()),
            intArrayOf(0xFFFFE5B4.toInt(), 0xFFE0A96D.toInt(), 0xFF7A5C3E.toInt()),
        )
        val p = palettes[Math.floorMod(h, palettes.size)]
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 300f, p, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 400f, 300f, paint)
        paint.shader = null
        paint.color = 0xCCFFFFFF.toInt()
        c.drawCircle(290f, 84f, 27f, paint)
        paint.color = 0x55203020
        c.drawOval(-80f, 210f, 280f, 390f, paint)
        return bmp
    }
}
