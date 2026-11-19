package app.kinframe.frame.setup

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The "use this tablet as a photo frame" step: when it shows, and how it opens Android's home-app chooser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeAppTest {
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

    @Test fun theStepShowsOnlyWhileKinframeIsNotTheHomeApp() {
        assertTrue(HomeAppPolicy.offerInSetup(isHome = false))
        // A converted frame (GitHub build) already has kinframe as its home app: it never sees the step.
        assertFalse(HomeAppPolicy.offerInSetup(isHome = true))
    }

    @Test fun roleRequestOnAndroid10AndNewerElseTheHomeSettings() {
        assertEquals(HomeAppPolicy.Route.Role, HomeAppPolicy.route(sdk = 29, roleAvailable = true, roleHeld = false, homeSettings = true))
        assertEquals(HomeAppPolicy.Route.Role, HomeAppPolicy.route(sdk = 36, roleAvailable = true, roleHeld = false, homeSettings = false))
        // Already held (Android hasn't refreshed yet), or a firmware without the role: the setting.
        assertEquals(HomeAppPolicy.Route.HomeSettings, HomeAppPolicy.route(sdk = 34, roleAvailable = true, roleHeld = true, homeSettings = true))
        assertEquals(HomeAppPolicy.Route.HomeSettings, HomeAppPolicy.route(sdk = 34, roleAvailable = false, roleHeld = false, homeSettings = true))
        // Android 6 to 9 have no RoleManager.
        assertEquals(HomeAppPolicy.Route.HomeSettings, HomeAppPolicy.route(sdk = 23, roleAvailable = true, roleHeld = false, homeSettings = true))
        assertEquals(HomeAppPolicy.Route.Settings, HomeAppPolicy.route(sdk = 23, roleAvailable = false, roleHeld = false, homeSettings = false))
    }

    @Suppress("DEPRECATION")
    @Test fun followsTheHomeAppChoice() {
        homeApp(us)
        homeApp(launcher)
        prefer(launcher)
        val home = HomeApp(app)
        assertFalse(home.isHome.value)
        // The person picked kinframe in the chooser: the next refresh (on resume) sees it.
        app.packageManager.clearPackagePreferredActivities("com.android.launcher3")
        prefer(us)
        assertTrue(home.refresh())
        assertTrue(home.isHome.value)
    }

    @Suppress("DEPRECATION")
    private fun prefer(c: ComponentName) =
        app.packageManager.addPreferredActivity(homeFilter, IntentFilter.MATCH_CATEGORY_EMPTY, arrayOf(us, launcher), c)

    @Test fun chooserFallsBackToTheHomeSettings() {
        val homeSettings = ComponentName("com.android.settings", "com.android.settings.HomeSettings")
        pm.addActivityIfNotPresent(homeSettings)
        pm.addIntentFilterForActivity(homeSettings, IntentFilter(Settings.ACTION_HOME_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        val home = HomeApp(app)
        // Robolectric has no ROLE_HOME, as on a firmware without it.
        val intent = home.chooserIntent()
        assertTrue(intent.action == Settings.ACTION_HOME_SETTINGS || intent.action == "android.app.role.action.REQUEST_ROLE")
    }
}
