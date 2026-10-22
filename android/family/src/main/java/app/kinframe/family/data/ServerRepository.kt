package app.kinframe.family.data

import android.content.SharedPreferences
import androidx.core.content.edit
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** The server this app talks to and its discovery document (product spec 4.1, architecture "Server discovery"). */
data class ServerChoice(val url: HttpUrl, val discovery: Discovery) {
    val apiBase: HttpUrl get() = ServerUrl.apiBase(url, discovery)
    val limits: Limits get() = discovery.limits ?: Limits()
    val display: String get() = ServerUrl.display(url)
    val hasLogto: Boolean get() = discovery.auth?.let { it.issuer.isNotBlank() && it.mobileClientId.isNotBlank() } == true
}

sealed class ServerCheckException(message: String) : Exception(message) {
    class Invalid : ServerCheckException("not a server address")
    class Unreachable(cause: Throwable?) : ServerCheckException("no kinframe server there: ${cause?.message}")
}

class ServerRepository(
    private val prefs: SharedPreferences,
    private val discovery: DiscoveryClient,
) {
    private val _current = MutableStateFlow(load())
    val current: StateFlow<ServerChoice?> = _current.asStateFlow()

    private fun load(): ServerChoice? {
        val url = prefs.getString(KEY_URL, null)?.toHttpUrlOrNull() ?: return null
        val doc = prefs.getString(KEY_DISCOVERY, null)?.let { runCatching { KinJson.decodeFromString(Discovery.serializer(), it) }.getOrNull() }
            ?: return null
        return ServerChoice(url, doc)
    }

    /** Validates what someone typed by fetching `/.well-known/kinframe`. */
    suspend fun check(input: String): ServerChoice {
        val url = ServerUrl.normalize(input) ?: throw ServerCheckException.Invalid()
        return check(url)
    }

    suspend fun check(url: HttpUrl): ServerChoice = try {
        ServerChoice(url, discovery.fetch(url))
    } catch (e: IOException) {
        throw ServerCheckException.Unreachable(e)
    } catch (e: ApiException) {
        throw ServerCheckException.Unreachable(e)
    } catch (e: IllegalArgumentException) {
        // Not JSON, or not a kinframe discovery document.
        throw ServerCheckException.Unreachable(e)
    }

    fun choose(choice: ServerChoice) {
        prefs.edit(commit = true) {
            putString(KEY_URL, choice.url.toString())
            putString(KEY_DISCOVERY, KinJson.encodeToString(Discovery.serializer(), choice.discovery))
        }
        _current.value = choice
    }

    fun clear() {
        prefs.edit(commit = true) { remove(KEY_URL); remove(KEY_DISCOVERY) }
        _current.value = null
    }

    /** Re-reads discovery (limits or client ids may change); keeps the stored copy when offline. */
    suspend fun refresh() {
        val cur = _current.value ?: return
        runCatching { discovery.fetch(cur.url) }.onSuccess { d -> if (_current.value?.url == cur.url) choose(cur.copy(discovery = d)) }
    }

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_DISCOVERY = "server_discovery"
    }
}
