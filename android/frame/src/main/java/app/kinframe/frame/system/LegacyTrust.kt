package app.kinframe.frame.system

import android.content.Context
import android.os.Build
import android.util.Log
import app.kinframe.frame.R
import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts the bundled Let's Encrypt roots (ISRG Root X1 and X2) on Android 6, which has none of them and ignores
 * `network_security_config.xml` (honoured from Android 7.0 on, where that file adds the same roots).
 *
 * The trust manager is a regular platform one over a key store holding the system's trusted CAs plus the bundled
 * roots, so chain validation and OkHttp's hostname verification work exactly as before; only the anchors grow.
 */
object LegacyTrust {
    private const val TAG = "KinTls"
    private val BUNDLED_ROOTS = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

    /** Applies the extended trust to [builder] on Android < 7.0; a no-op on newer versions. */
    fun apply(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT >= 24) return builder
        val tm = runCatching {
            trustManager(platformTrustManager(), BUNDLED_ROOTS.map { context.resources.openRawResource(it) })
        }.onFailure { Log.w(TAG, "could not add the bundled roots; using the system trust store only", it) }
            .getOrNull() ?: return builder
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return builder.sslSocketFactory(ssl.socketFactory, tm)
    }

    /** The platform's default trust manager (the system CA store). */
    fun platformTrustManager(): X509TrustManager = trustManagerFor(null)

    /** A trust manager accepting everything [base] trusts plus the PEM certificates in [extraRoots] (each closed). */
    fun trustManager(base: X509TrustManager?, extraRoots: List<InputStream>): X509TrustManager =
        trustManagerFor(keyStore(base?.acceptedIssuers.orEmpty().toList() + extraRoots.flatMap { s -> s.use { parsePem(it) } }))

    /** An in-memory key store with [anchors] as trusted certificate entries. */
    fun keyStore(anchors: List<X509Certificate>): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        anchors.distinctBy { it.subjectX500Principal to it.publicKey }.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
    }

    fun parsePem(input: InputStream): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }.also {
            require(it.isNotEmpty()) { "no certificates" }
        }

    private fun trustManagerFor(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
