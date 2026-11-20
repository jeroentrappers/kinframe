package app.kinframe.frame

import android.app.Application
import io.sentry.Sentry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Crash reports (docs/spec/crash-reporting.md): off without a DSN; release and flavor of both flavors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReportsTest {
    @Test
    fun aBuildWithoutDsnStartsNothing() {
        assertEquals("", BuildConfig.SENTRY_DSN)
        assertFalse(FrameCrashReports.start(RuntimeEnvironment.getApplication()))
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun reportsNameTheReleaseAndFlavor() {
        val s = FrameCrashReports.setup
        assertEquals("frame-android@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}", s.release)
        assertEquals(mapOf("flavor" to BuildConfig.DISTRIBUTION), s.tags)
    }
}
