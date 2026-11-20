package app.kinframe.family

import android.app.Application
import io.sentry.Sentry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Crash reports (docs/spec/crash-reporting.md): off without a DSN, and the person's "Send crash reports" switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReportsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun aBuildWithoutDsnStartsNothing() {
        assertEquals("", BuildConfig.SENTRY_DSN)
        assertFalse(FamilyCrashReports.start(app))
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun releaseNamesTheProjectVersionAndBuild() {
        assertEquals("family-android@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}", FamilyCrashReports.setup.release)
    }

    @Test
    fun sendCrashReportsIsOnUntilTurnedOff() {
        assertTrue(FamilyCrashReports.enabled(app))
        FamilyCrashReports.setEnabled(app, false)
        assertFalse(FamilyCrashReports.enabled(app))
        assertFalse(FamilyCrashReports.start(app))
        FamilyCrashReports.setEnabled(app, true)
        assertTrue(FamilyCrashReports.enabled(app))
    }
}
