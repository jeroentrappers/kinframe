package app.kinframe.frame.setup

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import app.kinframe.frame.system.DeviceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Use an old tablet as a frame" (docs/play-frame.md): kinframe only behaves as a frame (starts at boot, shows over the
 * lock screen, Back stays, see [DeviceMode]) when it is the home app. A converted frame already has it as its home app;
 * on a tablet the setup wizard ends with a step that opens Android's home-app chooser, and settings offer it again.
 */
class HomeApp(private val context: Context) {
    private val _isHome = MutableStateFlow(DeviceMode.isFrame(context))
    /** kinframe is the default home app. */
    val isHome: StateFlow<Boolean> = _isHome

    /** Looks again (after the chooser, on resume). Returns the new value. */
    fun refresh(): Boolean = DeviceMode.isFrame(context).also { _isHome.value = it }

    /** How [chooserIntent] asks: the role request where Android has one, else the home settings. */
    fun route(): HomeAppPolicy.Route {
        val (available, held) = if (Build.VERSION.SDK_INT >= 29) Roles.home(context) else false to false
        return HomeAppPolicy.route(Build.VERSION.SDK_INT, available, held, homeSettings = resolves(Intent(Settings.ACTION_HOME_SETTINGS)))
    }

    /** The intent that lets the person pick kinframe as the home app (start it for a result: the role dialog needs that). */
    fun chooserIntent(): Intent = when (route()) {
        HomeAppPolicy.Route.Role -> if (Build.VERSION.SDK_INT >= 29) Roles.request(context) else Intent(Settings.ACTION_HOME_SETTINGS)
        HomeAppPolicy.Route.HomeSettings -> Intent(Settings.ACTION_HOME_SETTINGS)
        HomeAppPolicy.Route.Settings -> Intent(Settings.ACTION_SETTINGS)
    }

    /** `RoleManager` (Android 10+). */
    @RequiresApi(29)
    private object Roles {
        /** Whether `ROLE_HOME` exists on this device and whether kinframe holds it. */
        fun home(context: Context): Pair<Boolean, Boolean> = runCatching {
            val rm = context.getSystemService(RoleManager::class.java)
            rm.isRoleAvailable(RoleManager.ROLE_HOME) to rm.isRoleHeld(RoleManager.ROLE_HOME)
        }.getOrDefault(false to false)

        fun request(context: Context): Intent = context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    private fun resolves(intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
}

/** The decisions of [HomeApp], free of Android so they can be unit-tested. */
object HomeAppPolicy {
    enum class Route {
        /** `RoleManager.createRequestRoleIntent(ROLE_HOME)` (Android 10+): a dialog listing the home apps. */
        Role,
        /** `Settings.ACTION_HOME_SETTINGS`: Android's "Home app" setting. */
        HomeSettings,
        /** Android's settings, for firmwares with neither (the person looks for "Home app" there). */
        Settings,
    }

    fun route(sdk: Int, roleAvailable: Boolean, roleHeld: Boolean, homeSettings: Boolean): Route = when {
        sdk >= 29 && roleAvailable && !roleHeld -> Route.Role
        homeSettings -> Route.HomeSettings
        else -> Route.Settings
    }

    /**
     * Whether the setup wizard ends with the home-app step: only when kinframe isn't the home app yet. Converted frames
     * (kinframe already is) never see it, so it is harmless in the GitHub build.
     */
    fun offerInSetup(isHome: Boolean): Boolean = !isHome
}
