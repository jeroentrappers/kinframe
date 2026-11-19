package app.kinframe.frame.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date

class LegacyTrustTest {
    private val bundled = listOf("isrg_root_x1.pem", "isrg_root_x2.pem").map { File("src/main/res/raw/$it") }
    private fun fixture(name: String) = LegacyTrust.parsePem(javaClass.getResourceAsStream("/tls/$name")!!)
    private fun bundledOnly() = LegacyTrust.trustManager(null, bundled.map { it.inputStream() })

    /** The chain kinframe.appmire.be served on 2026-11-18: leaf <- YE2 <- Root YE <- ISRG Root X2 (cross-signed by X1). */
    private val chain = fixture("kinframe-appmire-be-chain.pem")
    private val capturedAt = Date.from(java.time.Instant.parse("2026-11-18T12:00:00Z"))

    @Test fun acceptsBothBundledRoots() {
        val names = bundledOnly().acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(names.any { "CN=ISRG Root X1" in it })
        assertTrue(names.any { "CN=ISRG Root X2" in it })
    }

    @Test fun keepsThePlatformRoots() {
        val platform = LegacyTrust.platformTrustManager()
        val combined = LegacyTrust.trustManager(platform, bundled.map { it.inputStream() })
        val subjects = combined.acceptedIssuers.map { it.subjectX500Principal }.toSet()
        assertTrue(platform.acceptedIssuers.all { it.subjectX500Principal in subjects })
        assertTrue(combined.acceptedIssuers.size >= platform.acceptedIssuers.size)
    }

    @Test fun bundledRootsValidateTheServerChain() {
        assertEquals(4, chain.size)
        validate(chain)                    // up to ISRG Root X1 via the cross-signed X2
        validate(chain.take(3))            // or directly to the bundled ISRG Root X2
    }

    @Test(expected = CertificateException::class)
    fun stillRejectsUntrustedCertificates() {
        bundledOnly().checkServerTrusted(fixture("self-signed.pem").toTypedArray(), "ECDHE_ECDSA")
    }

    private fun validate(certs: List<X509Certificate>) {
        val ks = LegacyTrust.keyStore(bundled.flatMap { f -> f.inputStream().use { LegacyTrust.parsePem(it) } })
        val params = PKIXParameters(ks).apply { isRevocationEnabled = false; date = capturedAt }
        CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(certs), params)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LegacyTrustApplyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Config(sdk = [23])
    @Test fun marshmallowTrustsTheBundledRoots() {
        val client = LegacyTrust.apply(OkHttpClient.Builder(), app).build()
        val names = client.x509TrustManager!!.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(names.any { "CN=ISRG Root X1" in it })
        assertTrue(names.any { "CN=ISRG Root X2" in it })
    }

    @Config(sdk = [24])
    @Test fun nougatLeavesItToTheNetworkSecurityConfig() {
        val builder = OkHttpClient.Builder()
        assertSame(builder, LegacyTrust.apply(builder, app))
        val names = builder.build().x509TrustManager!!.acceptedIssuers.map { it.subjectX500Principal.name }
        assertEquals(LegacyTrust.platformTrustManager().acceptedIssuers.size, names.size)
    }
}
