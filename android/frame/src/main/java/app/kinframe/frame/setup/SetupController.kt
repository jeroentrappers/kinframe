package app.kinframe.frame.setup

import android.util.Log
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.FrameCode
import app.kinframe.core.api.ServerUrl
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import app.kinframe.frame.system.DeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant

enum class SetupStep { Language, Wifi, Server, Enrolling, Code, Celebrate }

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data class Ok(val url: HttpUrl, val discovery: Discovery) : ServerCheck
    data class Failed(val reason: Reason) : ServerCheck
    enum class Reason { InvalidUrl, Unreachable, NotKinframe }
}

sealed interface CodeState {
    data object Loading : CodeState
    data class Ready(val code: FrameCode, val expiresAtMs: Long) : CodeState
    data class Error(val offline: Boolean) : CodeState
}

data class SetupState(
    val step: SetupStep = SetupStep.Language,
    val useCustomServer: Boolean = false,
    val customUrl: String = "https://",
    val check: ServerCheck = ServerCheck.Idle,
    val enrollError: String? = null,
    val code: CodeState = CodeState.Loading,
    val ownerName: String? = null,
)

/**
 * The first-run wizard (product spec 4.8): language → Wi-Fi → server → enroll → frame code → "Connected".
 * Progress is persisted so a power cut during setup resumes at the same step.
 */
class SetupController(private val c: FrameController) {
    private val g get() = c.g
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state
    private var codeJob: Job? = null

    val steps = listOf(SetupStep.Language, SetupStep.Wifi, SetupStep.Server, SetupStep.Code)

    fun resume() {
        val enrolled = g.credentials.load() != null
        val saved = SetupStep.entries.getOrElse(g.prefs.setupStep) { SetupStep.Language }
        val step = when {
            enrolled -> SetupStep.Code
            saved.ordinal >= SetupStep.Enrolling.ordinal -> SetupStep.Server
            else -> saved
        }
        val custom = g.prefs.serverUrl?.let { it.trimEnd('/') != BuildConfig.DEFAULT_SERVER } ?: false
        _state.value = SetupState(step = step, useCustomServer = custom, customUrl = if (custom) g.prefs.serverUrl!! else "https://")
        if (step == SetupStep.Code) startCodeLoop()
    }

    fun restart() {
        codeJob?.cancel()
        _state.value = SetupState(step = SetupStep.Language)
    }

    fun goTo(step: SetupStep) {
        _state.update { it.copy(step = step) }
        if (step.ordinal <= SetupStep.Server.ordinal) g.prefs.setupStep = step.ordinal
    }

    fun back() {
        when (_state.value.step) {
            SetupStep.Wifi -> goTo(SetupStep.Language)
            SetupStep.Server -> goTo(SetupStep.Wifi)
            else -> Unit
        }
    }

    fun chooseLanguage(lang: String) { g.prefs.language = lang }

    fun setUseCustom(custom: Boolean) = _state.update { it.copy(useCustomServer = custom, check = ServerCheck.Idle) }
    fun setCustomUrl(url: String) = _state.update { it.copy(customUrl = url, check = ServerCheck.Idle) }

    /** Validates the chosen server via `/.well-known/kinframe` and shows its name. */
    fun checkServer() {
        val s = _state.value
        val input = if (s.useCustomServer) s.customUrl else BuildConfig.DEFAULT_SERVER
        val url = ServerUrl.normalize(input)
        if (url == null) {
            _state.update { it.copy(check = ServerCheck.Failed(ServerCheck.Reason.InvalidUrl)) }
            return
        }
        _state.update { it.copy(check = ServerCheck.Checking) }
        c.scope.launch {
            val result = try {
                ServerCheck.Ok(url, g.discovery.fetch(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.i(TAG, "server check failed: $e")
                ServerCheck.Failed(if (e is ApiException) ServerCheck.Reason.NotKinframe else ServerCheck.Reason.Unreachable)
            } catch (e: Exception) {
                Log.i(TAG, "server check failed: $e")
                ServerCheck.Failed(ServerCheck.Reason.NotKinframe)
            }
            _state.update { it.copy(check = result) }
        }
    }

    /** "Continue" after a successful check: store the server, enroll, then show the frame code. */
    fun confirmServer() {
        val ok = _state.value.check as? ServerCheck.Ok ?: return
        val apiBase = ServerUrl.apiBase(ok.url, ok.discovery)
        val changed = g.prefs.apiBase != apiBase.toString()
        g.prefs.serverUrl = ok.url.toString().trimEnd('/')
        g.prefs.apiBase = apiBase.toString()
        g.prefs.serverName = ok.discovery.name
        g.prefs.webUrl = ok.discovery.web?.takeIf { it.isNotBlank() }
        g.api.apiBase = apiBase
        if (changed && g.credentials.load() != null) {
            // A credential from another server is useless here.
            c.stopSession()
            g.credentials.clear()
            g.api.forgetToken()
        }
        enroll()
    }

    fun enroll() {
        goTo(SetupStep.Enrolling)
        _state.update { it.copy(enrollError = null) }
        c.scope.launch {
            try {
                if (g.credentials.load() == null) {
                    val root = withTimeoutOrNull(5_000) { g.root.isAvailable() } ?: false
                    val req = withContext(Dispatchers.IO) { DeviceInfo.enrollRequest(g.app, root) }
                    val res = g.api.enroll(req)
                    withContext(Dispatchers.IO) { g.credentials.save(DeviceCredential(res.deviceId, res.deviceSecret)) }
                    Log.i(TAG, "enrolled as ${res.deviceId}")
                }
                g.api.accessToken(forceRefresh = true)
                c.startSession()
                goTo(SetupStep.Code)
                startCodeLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "enroll failed", e)
                if (e is DeviceRevokedException) g.credentials.clear()
                _state.update { it.copy(enrollError = (e as? ApiException)?.problem?.title ?: e.message ?: "error") }
            }
        }
    }

    /** Shows the code, refreshes it 5 minutes before expiry, and watches for the claim (websocket or polling). */
    private fun startCodeLoop() {
        codeJob?.cancel()
        codeJob = c.scope.launch {
            var failures = 0
            while (_state.value.step == SetupStep.Code) {
                try {
                    val st = g.api.state()
                    if (st.claimed) { onClaimed(st.ownerName); return@launch }
                    val current = _state.value.code as? CodeState.Ready
                    val now = g.clock.nowMs()
                    if (current == null || current.expiresAtMs - now < REFRESH_BEFORE_MS) {
                        val code = g.api.code()
                        if (code.claimed == true) { onClaimed(null); return@launch }
                        val exp = runCatching { Instant.parse(code.expiresAt).toEpochMilli() }.getOrDefault(now + 12 * 3600_000L)
                        _state.update { it.copy(code = CodeState.Ready(code, exp)) }
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DeviceRevokedException) {
                    c.revokeAndReset(); return@launch
                } catch (e: Exception) {
                    failures++
                    Log.i(TAG, "code refresh failed: ${e.message}")
                    if (_state.value.code !is CodeState.Ready || failures > 3) {
                        _state.update { it.copy(code = CodeState.Error(offline = !g.network.status.value.connected)) }
                    }
                }
                delay(if (failures == 0) POLL_MS else 10_000)
            }
        }
    }

    fun retryCode() {
        _state.update { it.copy(code = CodeState.Loading) }
        startCodeLoop()
    }

    suspend fun onClaimed(ownerName: String?) {
        if (c.screen.value == app.kinframe.frame.AppScreen.Slideshow) {
            g.prefs.claimed = true
            return
        }
        if (_state.value.step == SetupStep.Celebrate) return
        g.prefs.claimed = true
        _state.update { it.copy(step = SetupStep.Celebrate, ownerName = ownerName ?: g.prefs.ownerName) }
        // Own coroutine: this may be called from the code-polling job, which is cancelled here.
        c.scope.launch {
            val owner = ownerName ?: runCatching { g.api.state().ownerName }.getOrNull()
            g.prefs.ownerName = owner
            _state.update { it.copy(ownerName = owner) }
            delay(CELEBRATE_MS)
            c.finishSetup()
        }
        codeJob?.cancel()
    }

    companion object {
        private const val TAG = "KinSetup"
        const val REFRESH_BEFORE_MS = 5 * 60_000L
        const val POLL_MS = 20_000L
        const val CELEBRATE_MS = 5_000L
    }
}
