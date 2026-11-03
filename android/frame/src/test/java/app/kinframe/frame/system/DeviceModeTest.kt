package app.kinframe.frame.system

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** kinframe owns the device only where it is the home app: a converted frame, not a tablet with its own launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceModeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val pm = shadowOf(app.packageManager)
    private val us = ComponentName(app, "app.kinframe.frame.MainActivity")
    private val launcher = ComponentName("com.android.launcher3", "com.android.launcher3.Launcher")
    private val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private fun homeApp(c: ComponentName) {
        pm.addActivityIfNotPresent(c)
        pm.addIntentFilterForActivity(c, homeFilter)
    }

    @Test fun theOnlyHomeAppIsAFrame() {
        homeApp(us)
        assertTrue(DeviceMode.isFrame(app))
    }

    // Robolectric resolves several home apps without a choice to the first one, not to Android's chooser, so each
    // case below registers the expected loser first: a passing test can't be an accident of the order.

    @Test fun nextToTheTabletsOwnLauncherItIsATablet() {
        homeApp(us)
        homeApp(launcher)
        prefer(launcher)
        assertFalse(DeviceMode.isFrame(app))
    }

    @Test fun chosenAsTheHomeAppItBecomesAFrame() {
        homeApp(launcher)
        homeApp(us)
        prefer(us)
        assertTrue(DeviceMode.isFrame(app))
    }

    @Suppress("DEPRECATION")
    private fun prefer(c: ComponentName) =
        app.packageManager.addPreferredActivity(homeFilter, IntentFilter.MATCH_CATEGORY_EMPTY, arrayOf(us, launcher), c)
}
