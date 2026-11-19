package app.kinframe.frame.social

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FramePrompt
import app.kinframe.core.api.PlacementComment
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots.shot
import app.kinframe.frame.slideshow.QuickMenuContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design records of the family social cards and the quick menu's "Ask for new photos" (frame/build/screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class SocialScreenshotTest {
    private val longComment = PlacementComment(
        authorName = "Anna",
        body = "Wat een prachtige dag was dat aan zee! Weet je nog hoe koud het water was en hoe we daarna warme chocolademelk " +
            "dronken in dat kleine café op de dijk? Volgend jaar weer!",
    )

    @Test fun cardsOnAPhoto() = shot("social_cards") {
        FakePhoto()
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NewPhotosNoteContent(listOf("Ben", "Oma", "Jan"))
            PromptCardContent(FramePrompt(promptId = "q", feedName = "Peeters", title = "Your view right now"))
            CommentCardContent(longComment)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) { AskConfirmationContent(AskConfirmation.Asked) }
        }
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun quickMenuAskDutch() = shot("social_quick_menu_nl") {
        FakePhoto(1)
        Box(Modifier.fillMaxSize()) {
            QuickMenuContent(
                visible = true, compact = false, frameName = "Oma's fotolijst", status = "Online", wifiIcon = KinIcons.Wifi,
                hasPhoto = true, reaction = null, onTouch = {}, onReact = {}, onHide = {}, onDelete = {}, onPower = {}, onSettings = {},
                ask = AskState.Ready,
            )
        }
    }

    @Test fun quickMenuAskedToday() = shot("social_quick_menu_asked") {
        FakePhoto(2)
        QuickMenuContent(
            visible = true, compact = false, frameName = "Oma's frame", status = "Online", wifiIcon = KinIcons.Wifi,
            hasPhoto = true, reaction = null, onTouch = {}, onReact = {}, onHide = {}, onDelete = {}, onPower = {}, onSettings = {},
            ask = AskState.Answered("Ben"),
        )
    }
}
