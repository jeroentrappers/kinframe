package app.kinframe.frame.calendar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.EventInvitation
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Paper
import app.kinframe.frame.Screenshots
import app.kinframe.frame.slideshow.CornerWidgets
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hybrid sidebar, countdown widgets, invitation card and event details (`frame/build/screenshots/`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class CalendarWidgetsScreenshotTest {
    private val data = CalendarFixtures.data()
    private val weather = WeatherFixtures.gent()
    private val now = CalendarFixtures.nowMs
    private val zone = CalendarFixtures.zone

    @Test fun hybridLandscape() = Screenshots.shot("calendar_hybrid_land") {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                FakePhoto(0)
                CornerWidgets("14:20", "Thursday 22 October", null, now, "c", onPaper = false) {}
            }
            CalendarSidebar(data, weather, now, zone, h24 = true, unit = "c", landscape = true, onOpen = {}, onEvent = {}, modifier = Modifier.width(380.dp).fillMaxHeight())
        }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun hybridPortrait() = Screenshots.shot("calendar_hybrid_port") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FakePhoto(3)
                CornerWidgets("14:20", "Thursday 22 October", null, now, "c", onPaper = false) {}
            }
            CalendarSidebar(data, weather, now, zone, h24 = true, unit = "c", landscape = false, onOpen = {}, onEvent = {}, modifier = Modifier.height(360.dp).fillMaxWidth())
        }
    }

    @Test fun countdownsOnPhoto() = boxShot("calendar_countdowns_photo") {
        FakePhoto(0)
        CountdownWidgets(data.upcomingCountdowns(now), CalendarFixtures.today, onPaper = false, onClick = {}, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp))
        CornerWidgets("14:20", "Thursday 22 October", weather, now, "c", onPaper = false) {}
    }

    @Test fun countdownsOnDarkPhoto() = boxShot("calendar_countdowns_dark") {
        FakePhoto(1)
        val items = data.upcomingCountdowns(now).toMutableList().also { it[0] = it[0].copy(day = CalendarFixtures.today, title = "Emma's birthday") }
        CountdownWidgets(items, CalendarFixtures.today, onPaper = false, onClick = {}, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp))
    }

    @Test fun countdownsOnPaper() = boxShot("calendar_countdowns_paper") {
        Paper()
        CountdownWidgets(data.upcomingCountdowns(now), CalendarFixtures.today, onPaper = true, onClick = {}, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp))
    }

    private val invitation = EventInvitation(
        id = "inv1", senderName = "Oma", title = "Sunday lunch at Oma's", allDay = false,
        startAt = CalendarFixtures.today.plusDays(3).atTime(12, 30).atZone(zone).toInstant().toString(),
        endAt = CalendarFixtures.today.plusDays(3).atTime(15, 0).atZone(zone).toInstant().toString(),
        location = "Kerkstraat 12, Merelbeke", notes = "Soup, roast and the apple pie you like. Bring the kids' drawings!",
        hasPhoto = true, photoPlacement = "top",
    )

    @Test fun invitationPhotoTop() = Screenshots.shot("calendar_invitation_top") {
        FakePhoto(2)
        InvitationCard(invitation, photo(), zone, h24 = true, onAccept = {}, onDecline = {}, onLater = {})
    }

    @Test fun invitationPhotoBackground() = Screenshots.shot("calendar_invitation_background") {
        FakePhoto(2)
        InvitationCard(invitation.copy(photoPlacement = "background"), photo(), zone, h24 = true, onAccept = {}, onDecline = {}, onLater = {})
    }

    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi")
    fun invitationNoPhotoPortraitDutch() = Screenshots.shot("calendar_invitation_port_nl") {
        FakePhoto(3)
        InvitationCard(
            invitation.copy(hasPhoto = false, allDay = true, startAt = null, endAt = null, startDate = "2026-10-31", endDate = "2026-11-01", title = "Weekend aan zee"),
            null, zone, h24 = true, onAccept = {}, onDecline = {}, onLater = {},
        )
    }

    @Test fun eventDetail() = boxShot("calendar_event_detail") {
        Paper()
        val e = data.events.single { it.title == "Dinner with Anna & Tom" }.copy(countdown = true)
        Box(Modifier.align(Alignment.CenterEnd).width(560.dp).fillMaxHeight()) {
            EventDetailSheet(e, null, h24 = true, onCountdown = {}, onClose = {})
        }
    }

    @Test fun eventDetailReceivedWithPhoto() = boxShot("calendar_event_detail_received") {
        Paper()
        val e = data.events.single { it.title == "Lunch at Oma's" }.copy(notes = "Soup, roast and apple pie.", location = "Kerkstraat 12, Merelbeke")
        Box(Modifier.align(Alignment.CenterEnd).width(560.dp).fillMaxHeight()) {
            EventDetailSheet(e, photo(), h24 = true, onCountdown = {}, onClose = {})
        }
    }

    private fun boxShot(name: String, content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) =
        Screenshots.shot(name) { Box(Modifier.fillMaxSize()) { content() } }

    /** A warm table-scene stand-in for a sent photo. */
    private fun photo(): ImageBitmap {
        val b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 640f, 400f, intArrayOf(0xFFF3C98B.toInt(), 0xFFD9824F.toInt(), 0xFF7A3E2A.toInt()), null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 640f, 400f, p)
        val plate = Paint().apply { color = 0xFFFFF8EE.toInt(); isAntiAlias = true }
        c.drawCircle(220f, 250f, 110f, plate)
        c.drawCircle(450f, 230f, 80f, plate)
        val food = Paint().apply { color = 0xFFB5552E.toInt(); isAntiAlias = true }
        c.drawCircle(220f, 250f, 60f, food)
        val green = Paint().apply { color = 0xFF6E8B4E.toInt(); isAntiAlias = true }
        c.drawCircle(450f, 230f, 40f, green)
        return b.asImageBitmap()
    }
}
