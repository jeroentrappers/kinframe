package app.kinframe.frame

import android.content.Context
import app.kinframe.core.crash.CrashReports

/**
 * The frame's crash reports (docs/spec/crash-reporting.md): always on in builds with a DSN (an owner-managed device,
 * nothing personal in the reports), tagged with the flavor since both share versions.
 */
object FrameCrashReports {
    val setup: CrashReports.Setup
        get() = CrashReports.Setup(
            BuildConfig.SENTRY_DSN, "frame-android", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            tags = mapOf("flavor" to BuildConfig.DISTRIBUTION),
        )

    /** At app start: whether reporting started. */
    fun start(context: Context): Boolean = CrashReports.start(context, setup)
}
