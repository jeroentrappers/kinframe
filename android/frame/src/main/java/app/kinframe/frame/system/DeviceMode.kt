package app.kinframe.frame.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Frame or tablet. A converted frame has kinframe as its home app (`pm set-home-activity`, or the only home app left on
 * Android 6); there it owns the device: it starts at boot, shows over the lock screen and Back never leaves it.
 * Anywhere else, such as a tablet that is still used as a tablet, it is an app among others and does none of that.
 * Picking kinframe as the home app ("Always") turns a tablet into a frame.
 */
object DeviceMode {
    fun isFrame(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching { context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        // With several home apps and no choice made yet this is Android's chooser, not us: a tablet.
        return resolved?.activityInfo?.packageName == context.packageName
    }
}
