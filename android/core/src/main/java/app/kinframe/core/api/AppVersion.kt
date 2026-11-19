package app.kinframe.core.api

import okhttp3.Interceptor
import okhttp3.Response

/** How the family apps identify themselves to the server (README §5.15). */
object FamilyAppHeader {
    const val NAME = "X-Kinframe-App"

    /** `kinframe-family-android/<version name> (<version code>)`. */
    fun value(versionName: String, versionCode: Int): String = "kinframe-family-android/$versionName ($versionCode)"
}

/**
 * Adds `X-Kinframe-App` to every request through the client (API calls, discovery, tus uploads, the websocket
 * handshake, thumbnails) and reports `426 app_update_required` answers to [onUpdateRequired], whichever call got them.
 */
class AppVersionInterceptor(
    private val appHeader: String,
    private val onUpdateRequired: (Problem?) -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val out = if (req.header(FamilyAppHeader.NAME) == null) req.newBuilder().header(FamilyAppHeader.NAME, appHeader).build() else req
        val r = chain.proceed(out)
        if (r.code == 426) {
            val problem = runCatching {
                val text = r.peekBody(64 * 1024).string()
                if (text.isBlank()) null else KinJson.decodeFromString(Problem.serializer(), text)
            }.getOrNull()
            runCatching { onUpdateRequired(problem) }
        }
        return r
    }
}
