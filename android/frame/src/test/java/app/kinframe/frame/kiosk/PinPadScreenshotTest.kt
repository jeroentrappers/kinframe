package app.kinframe.frame.kiosk

import android.app.Application
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class PinPadScreenshotTest {
    private val hint = "Forgot the PIN? The frame's owner can remove it in the kinframe app, under this frame's settings."

    @Test fun enterLandscape() = shot("pin_enter_landscape") {
        FakePhoto()
        PinPad("Enter the owner PIN", "to open settings", 2, PinMessage.None, hint, {}, {}, {}, {})
    }

    @Test fun wrongLandscape() = shot("pin_wrong_landscape") {
        FakePhoto(1)
        PinPad("Enter the owner PIN", "to turn the frame off", 0, PinMessage.Wrong(3), hint, {}, {}, {}, {})
    }

    @Test fun lockedLandscape() = shot("pin_locked_landscape") {
        FakePhoto(2)
        PinPad("Enter the owner PIN", "to change Wi-Fi", 0, PinMessage.Locked(57), hint, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun enterPortrait() = shot("pin_enter_portrait") {
        FakePhoto(3)
        PinPad("Enter the owner PIN", "to leave kinframe", 5, PinMessage.None, hint, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun choosePinDutch() = shot("pin_choose_nl") {
        SetPinFlow(onDone = {}, onCancel = {})
    }
}
