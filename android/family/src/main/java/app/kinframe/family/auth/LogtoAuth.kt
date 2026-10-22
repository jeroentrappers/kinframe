package app.kinframe.family.auth

import android.app.Activity
import android.app.Application
import app.kinframe.core.api.DiscoveryAuth
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.family.data.SecureStore
import io.logto.sdk.android.LogtoClient
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import io.logto.sdk.core.constant.UserScope
import io.logto.sdk.core.exception.ResponseException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sign-in with the server's Logto through the official Logto Android SDK: authorization code + PKCE in a Custom Tab,
 * the native app's client id, issuer and API resource from discovery, redirect `app.kinframe:/callback` and
 * post-logout `app.kinframe:/logout` (both registered by `bootstrap-logto`, docs/deploy.md).
 *
 * The SDK would keep tokens in plain SharedPreferences; we turn its storage off and keep the refresh and ID tokens
 * in [SecureStore] (EncryptedSharedPreferences) instead. Access tokens (1 h) live in memory only.
 */
class LogtoAuth(application: Application, private val auth: DiscoveryAuth, private val store: SecureStore) {

    private val client = KinLogtoClient(
        LogtoConfig(
            endpoint = endpointFromIssuer(auth.issuer),
            appId = auth.mobileClientId,
            scopes = listOf(UserScope.EMAIL),
            resources = listOf(auth.apiResource),
            usingPersistStorage = false,
        ),
        application,
        store,
    )
    private val lock = Mutex()

    val isAuthenticated: Boolean get() = client.isAuthenticated

    suspend fun signIn(activity: Activity) {
        suspendCancellableCoroutine { cont ->
            client.signIn(activity, REDIRECT_URI) { e ->
                if (e == null) cont.resume(Unit) else cont.resumeWithException(SignInException(e))
            }
        }
        client.persist()
    }

    /** Access token for the kinframe API resource; refreshed with the refresh token when expired. */
    suspend fun accessToken(forceRefresh: Boolean): String = lock.withLock {
        if (forceRefresh) client.dropAccessTokens()
        val token = suspendCancellableCoroutine { cont ->
            client.getAccessToken(auth.apiResource) { e, t ->
                when {
                    e == null && t != null -> cont.resume(t.token)
                    else -> cont.resumeWithException(mapError(e))
                }
            }
        }
        client.persist()
        token
    }

    /** Display name from the ID token (asked at sign-up by bootstrap-logto's sign-in experience). */
    suspend fun idTokenName(): String? = suspendCancellableCoroutine { cont ->
        client.getIdTokenClaims { _, claims -> cont.resume((claims?.name ?: claims?.username)?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    /** Clears tokens, revokes the refresh token and ends the browser session (post-logout back into the app). */
    suspend fun signOut(activity: Activity?) {
        if (activity == null) {
            suspendCancellableCoroutine { cont -> client.clearCredentials { cont.resume(Unit) } }
        } else {
            suspendCancellableCoroutine { cont -> client.signOut(activity, LOGOUT_URI) { cont.resume(Unit) } }
        }
        client.persist()
    }

    fun forget() {
        store.put(KEY_REFRESH, null)
        store.put(KEY_ID, null)
    }

    private fun mapError(e: LogtoException?): Exception {
        if (e == null) return IOException("no token")
        val cause = e.cause
        return when {
            e.message == LogtoException.Type.NOT_AUTHENTICATED.name -> SessionExpiredException()
            cause is IOException -> cause
            // invalid_grant: the refresh token was revoked or expired (14 days without use by default in Logto).
            cause is ResponseException && (cause.responseCode ?: 0) in 400..499 -> SessionExpiredException()
            else -> IOException(e.message, e)
        }
    }

    class SignInException(val logto: LogtoException) : Exception(logto.message, logto) {
        val cancelled: Boolean get() = logto.message == LogtoException.Type.USER_CANCELED.name
    }

    /** Logto client whose tokens live in [SecureStore]. */
    private class KinLogtoClient(config: LogtoConfig, app: Application, private val store: SecureStore) : LogtoClient(config, app) {
        init {
            refreshToken = store.get(KEY_REFRESH)
            idToken = store.get(KEY_ID)
        }

        fun persist() {
            store.put(KEY_REFRESH, refreshToken)
            store.put(KEY_ID, idToken)
        }

        fun dropAccessTokens() = accessTokenMap.clear()
    }

    companion object {
        const val REDIRECT_URI = "app.kinframe:/callback"
        const val LOGOUT_URI = "app.kinframe:/logout"
        private const val KEY_REFRESH = "logto_refresh_token"
        private const val KEY_ID = "logto_id_token"

        /** Discovery gives the OIDC issuer (`https://auth.example.org/oidc`); the SDK wants the Logto endpoint. */
        fun endpointFromIssuer(issuer: String): String = issuer.trimEnd('/').removeSuffix("/oidc")
    }
}
