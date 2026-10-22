package app.kinframe.frame.system

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Captive-portal detection without third parties (hotel, care-home and guest Wi-Fi): a plain-HTTP request to the
 * frame's own server (`http://<server>/.well-known/kinframe`), redirects not followed. The real server answers with
 * its discovery JSON or a redirect to itself (HTTPS upgrade); a portal answers with a redirect elsewhere or its own
 * page. Android's own detection (`NET_CAPABILITY_CAPTIVE_PORTAL`) counts too when the OS reports it.
 */
class CaptivePortal(
    http: OkHttpClient,
    private val probeUrl: () -> HttpUrl?,
    private val network: NetworkMonitor,
) {
    sealed interface Result {
        data object Open : Result
        data class Portal(val loginUrl: String) : Result
        /** Nothing answered (offline, DNS failure): not a portal as far as we can tell. */
        data object Unknown : Result
    }

    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    /** Login URL while the network needs a sign-in, else null. */
    private val _loginUrl = MutableStateFlow<String?>(null)
    val loginUrl: StateFlow<String?> = _loginUrl

    suspend fun check(): Result {
        val url = probeUrl() ?: return Result.Unknown
        val result = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-cache").build()).execute().use { r ->
                    val body = if (r.code == 200) r.peekBody(4096).string() else ""
                    classify(url, r.code, r.header("Location"), r.header("Content-Type"), body)
                }
            }.getOrElse { Result.Unknown }
        }
        val systemSaysPortal = network.status.value.captivePortal
        _loginUrl.value = when {
            result is Result.Portal -> result.loginUrl
            systemSaysPortal && result != Result.Open -> url.toString()
            else -> null
        }
        return result
    }

    /** Re-checks every [everyMs] while the network is up but not validated or the server is unreachable. */
    fun start(scope: CoroutineScope, suspicious: () -> Boolean, everyMs: Long = 60_000) {
        scope.launch {
            while (true) {
                val n = network.status.value
                if (n.connected && (suspicious() || !n.validated || n.captivePortal || _loginUrl.value != null)) {
                    val r = check()
                    if (r is Result.Portal) Log.i(TAG, "captive portal detected")
                } else if (!n.connected) {
                    _loginUrl.value = null
                }
                delay(if (_loginUrl.value != null) 15_000 else everyMs)
            }
        }
    }

    companion object {
        private const val TAG = "KinPortal"

        /** The probe URL for a server: plain HTTP on the same host (portals intercept only unencrypted traffic). */
        fun probeFor(server: String?): HttpUrl? {
            val u = server?.toHttpUrlOrNull() ?: return null
            return u.newBuilder().scheme("http").port(if (u.scheme == "http") u.port else 80)
                .encodedPath("/.well-known/kinframe").query(null).build()
        }

        fun classify(probe: HttpUrl, code: Int, location: String?, contentType: String?, body: String): Result = when {
            code in 300..399 -> {
                val target = location?.let { probe.resolve(it) }
                when {
                    target == null -> Result.Portal(probe.toString())
                    // The server upgrading to HTTPS (or moving the path) on its own host is fine.
                    target.host.equals(probe.host, ignoreCase = true) -> Result.Open
                    else -> Result.Portal(target.toString())
                }
            }
            code == 200 && contentType?.contains("json", ignoreCase = true) == true && body.contains("\"name\"") -> Result.Open
            code == 200 || code == 511 -> Result.Portal(probe.toString())
            // Any other answer from something that is not a portal page (e.g. 404 from a proxy without a body).
            else -> Result.Unknown
        }
    }
}
