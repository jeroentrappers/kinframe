package app.kinframe.core.leaving

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.FamilyAppHeader
import app.kinframe.core.api.JSON_MEDIA
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.core.api.await
import app.kinframe.core.api.problemOrNull
import app.kinframe.core.api.throwForStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The bearer-token plumbing the person-facing API clients share (one refresh on 401, then the session is over), for
 * the clients added with leaving and other servers ([HttpLeavingApi], [app.kinframe.core.servers.HttpRemoteApi]).
 */
class AuthedClient(
    private val http: OkHttpClient,
    @Volatile var apiBase: HttpUrl,
    private val tokens: AccessTokenSource,
    private val userAgent: String = "kinframe-family",
    private val appHeader: String? = null,
    private val acceptLanguage: () -> String? = { null },
) {
    fun ep(path: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        apiBase.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()

    fun json(obj: JsonElement): RequestBody = KinJson.encodeToString(JsonElement.serializer(), obj).toRequestBody(JSON_MEDIA)

    fun <T> json(value: T, ser: KSerializer<T>): RequestBody = KinJson.encodeToString(ser, value).toRequestBody(JSON_MEDIA)

    val empty: RequestBody get() = "{}".toRequestBody(JSON_MEDIA)

    suspend fun call(spec: Request.Builder.() -> Request.Builder): Response {
        var attempt = 0
        while (true) {
            val token = try {
                tokens.accessToken(forceRefresh = attempt > 0)
            } catch (e: SessionExpiredException) {
                tokens.onSessionExpired(); throw e
            }
            val req = Request.Builder()
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .apply { appHeader?.let { header(FamilyAppHeader.NAME, it) } }
                .apply { acceptLanguage()?.let { header("Accept-Language", it) } }
                .spec()
                .header("Authorization", "Bearer $token")
                .build()
            val r = http.newCall(req).await()
            if (r.code != 401) return r
            val p = r.use { it.problemOrNull() }
            if (attempt++ >= 1) {
                tokens.onSessionExpired()
                throw SessionExpiredException(p)
            }
        }
    }

    suspend fun <T> decode(r: Response, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        r.use {
            if (!it.isSuccessful) it.throwForStatus()
            KinJson.decodeFromString(ser, it.body.string())
        }
    }

    /** 200 with a body, or 204 (null). */
    suspend fun <T> decodeOrNull(r: Response, ser: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        r.use {
            if (!it.isSuccessful) it.throwForStatus()
            if (it.code == 204) null else it.body.string().takeIf { b -> b.isNotBlank() }?.let { b -> KinJson.decodeFromString(ser, b) }
        }
    }

    suspend fun expectOk(r: Response) = withContext(Dispatchers.IO) { r.use { if (!it.isSuccessful) it.throwForStatus() } }
}
