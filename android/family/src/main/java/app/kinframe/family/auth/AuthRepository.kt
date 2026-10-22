package app.kinframe.family.auth

import android.app.Activity
import android.app.Application
import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.DevAuthClient
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.family.data.SecureStore
import app.kinframe.family.data.ServerChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthMode { Logto, Dev }

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val mode: AuthMode) : AuthState
}

/**
 * Who is signed in, and their access tokens. Logto for real servers; `POST /dev/token` on development servers
 * (discovery `dev_auth: true`). Tokens belong to one server: switching servers signs out.
 */
class AuthRepository(
    private val application: Application,
    private val store: SecureStore,
    private val devAuth: DevAuthClient,
    private val server: () -> ServerChoice?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AccessTokenSource {

    private var logto: LogtoAuth? = null
    private var logtoFor: String? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** True after the server ended the session, so the sign-in screen can explain why. */
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    private fun initialState(): AuthState {
        val s = server() ?: return AuthState.SignedOut
        if (store.get(KEY_SERVER) != s.url.toString()) return AuthState.SignedOut
        return when (store.get(KEY_MODE)) {
            AuthMode.Dev.name -> if (store.get(KEY_DEV_TOKEN) != null) AuthState.SignedIn(AuthMode.Dev) else AuthState.SignedOut
            AuthMode.Logto.name -> if (logtoFor(s)?.isAuthenticated == true) AuthState.SignedIn(AuthMode.Logto) else AuthState.SignedOut
            else -> AuthState.SignedOut
        }
    }

    private fun logtoFor(s: ServerChoice): LogtoAuth? {
        val auth = s.discovery.auth ?: return null
        if (!s.hasLogto) return null
        val key = s.url.toString() + "|" + auth.mobileClientId
        if (logtoFor != key) {
            logto = LogtoAuth(application, auth, store)
            logtoFor = key
        }
        return logto
    }

    suspend fun signInWithLogto(activity: Activity) {
        val s = server() ?: error("no server")
        val l = logtoFor(s) ?: error("sign-in not configured")
        l.signIn(activity)
        store.put(KEY_SERVER, s.url.toString())
        store.put(KEY_MODE, AuthMode.Logto.name)
        _expired.value = false
        _state.value = AuthState.SignedIn(AuthMode.Logto)
    }

    suspend fun signInDev(email: String, name: String) {
        val s = server() ?: error("no server")
        val t = devAuth.token(s.url, email.trim(), name.trim())
        store.put(KEY_SERVER, s.url.toString())
        store.put(KEY_MODE, AuthMode.Dev.name)
        store.put(KEY_DEV_TOKEN, t.accessToken)
        store.put(KEY_DEV_EXP, (clock() + t.expiresIn * 1000).toString())
        _expired.value = false
        _state.value = AuthState.SignedIn(AuthMode.Dev)
    }

    /** Name from the Logto ID token, used to replace the server's placeholder name after the first sign-in. */
    suspend fun idTokenName(): String? = server()?.let { logtoFor(it) }?.takeIf { mode() == AuthMode.Logto }?.idTokenName()

    fun mode(): AuthMode? = (state.value as? AuthState.SignedIn)?.mode

    /** Signs out locally, and with an activity also ends the Logto browser session. */
    suspend fun signOut(activity: Activity?) {
        val s = server()
        if (mode() == AuthMode.Logto && s != null) runCatching { logtoFor(s)?.signOut(activity) }
        clearTokens()
        _state.value = AuthState.SignedOut
    }

    private fun clearTokens() {
        logto?.forget()
        store.put(KEY_MODE, null)
        store.put(KEY_DEV_TOKEN, null)
        store.put(KEY_DEV_EXP, null)
        store.put(KEY_SERVER, null)
    }

    /** Switching servers: tokens of the old server are useless there. */
    fun forgetServer() {
        clearTokens()
        logto = null
        logtoFor = null
        _state.value = AuthState.SignedOut
    }

    override suspend fun accessToken(forceRefresh: Boolean): String {
        val s = server() ?: throw SessionExpiredException()
        return when (mode()) {
            AuthMode.Dev -> {
                val exp = store.get(KEY_DEV_EXP)?.toLongOrNull() ?: 0
                val t = store.get(KEY_DEV_TOKEN)
                if (t == null || clock() > exp || forceRefresh) throw SessionExpiredException()
                t
            }
            AuthMode.Logto -> (logtoFor(s) ?: throw SessionExpiredException()).accessToken(forceRefresh)
            null -> throw SessionExpiredException()
        }
    }

    override fun onSessionExpired() {
        if (state.value is AuthState.SignedOut) return
        clearTokens()
        _expired.value = true
        _state.value = AuthState.SignedOut
    }

    companion object {
        private const val KEY_SERVER = "auth_server"
        private const val KEY_MODE = "auth_mode"
        private const val KEY_DEV_TOKEN = "dev_token"
        private const val KEY_DEV_EXP = "dev_token_expires_at"
    }
}
