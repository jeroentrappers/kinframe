package app.kinframe.frame.system

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 6 frames (GitHub build only; the Play build needs Android 7) trust the bundled Let's Encrypt roots. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [23])
class LegacyTrustMarshmallowTest {
    @Test fun marshmallowTrustsTheBundledRoots() {
        val client = LegacyTrust.apply(OkHttpClient.Builder(), RuntimeEnvironment.getApplication()).build()
        val names = client.x509TrustManager!!.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(names.any { "CN=ISRG Root X1" in it })
        assertTrue(names.any { "CN=ISRG Root X2" in it })
    }
}
