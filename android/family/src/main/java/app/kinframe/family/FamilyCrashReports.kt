package app.kinframe.family

import android.content.Context
import androidx.core.content.edit
import app.kinframe.core.crash.CrashReports

/**
 * The family app's crash reports (docs/spec/crash-reporting.md): on in builds with a DSN, unless the person turned
 * "Send crash reports" off in Settings. Stored on the device only; turning it on takes effect at the next start.
 */
object FamilyCrashReports {
    private const val PREFS = "kinframe_crash_reports"
    private const val KEY = "send"

    val setup: CrashReports.Setup
        get() = CrashReports.Setup(BuildConfig.SENTRY_DSN, "family-android", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    /** Off stops reporting right away; on starts at the next app start. */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY, on) }
        if (!on) CrashReports.stop()
    }

    /** At app start: whether reporting started. */
    fun start(context: Context): Boolean = enabled(context) && CrashReports.start(context, setup)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
