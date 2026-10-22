package app.kinframe.core.ws

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A websocket that stays connected: reconnects with [Backoff] (1 s → 60 s, ±20 % jitter) after failures,
 * fetches a fresh request (and token) for every attempt, and can be nudged to reconnect immediately
 * (e.g. when the network comes back).
 *
 * Close code 4401 means "credential rejected": the loop stops and [Listener.onUnauthorized] is called.
 */
class ReconnectingWebSocket(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val request: suspend () -> Request,
    private val listener: Listener,
    private val backoff: Backoff = Backoff(),
    private val tag: String = "KinWs",
) {
    interface Listener {
        fun onOpen() {}
        fun onMessage(text: String)
        fun onClosed() {}
        fun onUnauthorized() {}
        /** Errors before a socket exists (e.g. token refresh failed). Return false to stop retrying. */
        fun onConnectError(error: Throwable): Boolean = true
    }

    enum class Status { Idle, Connecting, Open, Waiting, Stopped }

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status

    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop(code: Int = 1000, reason: String = "bye") {
        job?.cancel()
        job = null
        socket?.close(code, reason)
        socket = null
        _status.value = Status.Stopped
    }

    /** Skip the remaining back-off and reconnect now (or no-op when already open). */
    fun reconnectNow() {
        backoff.reset()
        wake.trySend(Unit)
    }

    fun send(text: String): Boolean = socket?.send(text) ?: false

    val isOpen: Boolean get() = _status.value == Status.Open

    private suspend fun loop() {
        while (scope.isActive) {
            _status.value = Status.Connecting
            val req = try {
                request()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(tag, "cannot build websocket request: $e")
                if (!listener.onConnectError(e)) { _status.value = Status.Stopped; return }
                waitBackoff()
                continue
            }
            val closed = Channel<Int>(Channel.CONFLATED)
            val ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff.reset()
                    _status.value = Status.Open
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { listener.onMessage(text) }.onFailure { Log.e(tag, "message handler failed", it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.trySend(code)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.trySend(code)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.i(tag, "websocket failure: ${t.message} (${response?.code})")
                    closed.trySend(if (response?.code == 401) CODE_UNAUTHORIZED else -1)
                }
            })
            socket = ws
            val code = try {
                closed.receive()
            } finally {
                if (!scope.isActive) ws.cancel()
            }
            socket = null
            if (_status.value == Status.Open) listener.onClosed()
            if (code == CODE_UNAUTHORIZED) {
                _status.value = Status.Stopped
                listener.onUnauthorized()
                return
            }
            waitBackoff()
        }
    }

    private suspend fun waitBackoff() {
        _status.value = Status.Waiting
        val ms = backoff.next()
        // Drain an old nudge, then wait for either the delay or a new nudge.
        wake.tryReceive()
        withTimeoutOrNull(ms) { wake.receive() }
    }

    companion object {
        const val CODE_UNAUTHORIZED = 4401
    }
}
