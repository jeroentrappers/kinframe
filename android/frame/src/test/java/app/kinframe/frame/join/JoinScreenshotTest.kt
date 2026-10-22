package app.kinframe.frame.join

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.JoinRequestUser
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class JoinScreenshotTest {
    private val now = 1_792_670_400_000L
    private val anna = JoinRequestUi("r1", "u1", "Anna Peeters", null, null, null, now - 3_600_000)
    private val tom = JoinRequestUi("r2", "u2", "Tom", null, "Jeroen", "Hi Oma, it's Tom from next door!", now - 2 * 86_400_000L)

    @Test fun cardByCode() = shot("join_card_landscape") {
        FakePhoto()
        JoinRequestCard(anna, null, "Oma's frame", {}, {}, {})
    }

    @Test fun cardInvitedWithNote() = shot("join_card_invited_landscape") {
        FakePhoto(1)
        JoinRequestCard(tom, null, "Oma's frame", {}, {}, {})
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun cardPortrait() = shot("join_card_portrait") {
        FakePhoto(2)
        JoinRequestCard(tom, null, "Oma's frame", {}, {}, {})
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun cardDutch() = shot("join_card_nl") {
        FakePhoto(3)
        JoinRequestCard(anna, null, "Oma's fotolijst", {}, {}, {})
    }

    @Test fun pillAndPanel() = shot("join_notifications_panel") {
        FakePhoto()
        Box(Modifier.fillMaxSize().padding(24.dp)) { JoinRequestsPill(listOf(anna, tom), Modifier) {} }
        NotificationsPanel(true, listOf(anna, tom), emptyMap(), now, {}, {}, {})
    }

    @Test fun emptyPanelPortrait() = shot("notifications_empty_portrait") {
        FakePhoto(2)
        Box(Modifier.fillMaxSize().padding(24.dp)) { JoinRequestsPill(listOf(anna), Modifier) {} }
    }

    @Test fun parsesTheEvent() {
        val r = JoinRequest("r9", JoinRequestUser("u9", "  ", null), via = "invite", invitedByName = "Jeroen", createdAt = "2026-10-22T10:00:00Z")
        val ui = JoinRequestUi.of(r, "Someone")
        assertEquals("Someone", ui.name)
        assertEquals("Jeroen", ui.invitedBy)
        assertEquals(1_792_663_200_000L, ui.createdAtMs)
        assertNull(JoinRequestUi.of(r.copy(via = "frame_code"), "x").invitedBy)
    }
}
