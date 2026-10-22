package app.kinframe.frame.sync

import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.core.api.Heartbeat
import app.kinframe.core.api.KinJson
import app.kinframe.core.ws.ReconnectingWebSocket
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.system.NetworkMonitor
import app.kinframe.frame.system.TrustedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/**
 * Everything that runs while the frame holds a device credential: the device websocket, change-feed pulls
 * (websocket hints plus a 5-minute fallback poll), downloads, acks, the outbox and the 5-minute heartbeat.
 */
class FrameSession(
    http: OkHttpClient,
    private val api: DeviceApi,
    private val db: FrameDatabase,
    media: MediaCache,
    private val clock: TrustedClock,
    private val network: NetworkMonitor,
    parent: CoroutineScope,
    private val events: Events,
    makeRoom: suspend (Long) -> Boolean = { false },
) {
    interface Events {
        fun onClaimed(ownerName: String?)
        fun onRevoked()
        /** Runs a remote command; returns null on success or an error code. */
        suspend fun onCommand(type: String, args: JsonObject): String?
        fun onJoinRequest(request: JsonObject) {}
        suspend fun heartbeat(): Heartbeat
        fun onPulled(result: SyncEngine.PullResult) {}
    }

    enum class Connection { Connecting, Online, ServerUnreachable, Offline }

    private val scope = CoroutineScope(SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
    val acks = AckQueue(db, clock::nowMs)
    val outbox = Outbox(db, clock::nowMs)
    val sync = SyncEngine(api, db, media, clock::nowMs)
    val downloader = Downloader(api, db, media, acks, clock::nowMs, makeRoom = makeRoom)
    private val pullRequests = Channel<Unit>(Channel.CONFLATED)

    private val _connection = MutableStateFlow(Connection.Connecting)
    val connection: StateFlow<Connection> = _connection
    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt

    @Volatile private var revoked = false
    private var lastStateJson: String? = null
    private var stateJob: Job? = null

    private val ws: ReconnectingWebSocket = ReconnectingWebSocket(http, scope, { api.webSocketRequest() }, object : ReconnectingWebSocket.Listener {
        override fun onOpen() {
            _connection.value = Connection.Online
            lastStateJson?.let { send(it) }
        }

        override fun onMessage(text: String) = handleMessage(text)

        override fun onClosed() { updateOffline() }

        override fun onUnauthorized() {
            scope.launch {
                try {
                    api.accessToken(forceRefresh = true)
                    ws().start()
                } catch (e: DeviceRevokedException) {
                    revoke()
                } catch (e: Exception) {
                    delay(5_000); ws().start()
                }
            }
        }

        override fun onConnectError(error: Throwable): Boolean {
            if (error is DeviceRevokedException) { revoke(); return false }
            updateOffline()
            return true
        }
    })

    private fun ws(): ReconnectingWebSocket = ws

    fun start() {
        outbox.onVersionConflict = { runCatching { sync.pull() } }
        outbox.onRevoked = ::revoke
        downloader.onRevoked = ::revoke
        scope.launch { pullLoop() }
        scope.launch { downloader.run() }
        scope.launch { acks.run(api) }
        scope.launch { outbox.run(api) }
        scope.launch { heartbeatLoop() }
        scope.launch {
            ws.status.collect { s ->
                if (s == ReconnectingWebSocket.Status.Waiting) updateOffline()
            }
        }
        ws.start()
        network.onAvailable {
            ws.reconnectNow()
            requestPull()
        }
        requestPull()
    }

    fun requestPull() { pullRequests.trySend(Unit) }

    fun reconnectNow() = ws.reconnectNow()

    private fun updateOffline() {
        if (ws.isOpen) return
        val n = network.status.value
        _connection.value = if (!n.connected) Connection.Offline else Connection.ServerUnreachable
    }

    private suspend fun pullLoop() {
        var failures = 0
        while (scope.isActive) {
            withTimeoutOrNull(POLL_MS) { pullRequests.receive() }
            try {
                val r = sync.pull()
                failures = 0
                _lastSyncAt.value = clock.nowMs()
                downloader.kick()
                events.onPulled(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceRevokedException) {
                revoke(); return
            } catch (e: Exception) {
                failures++
                Log.i(TAG, "pull failed: ${e.message}")
                // Retry soon (but not hot-looping) when a hint was missed because of an error.
                delay((5_000L * failures).coerceAtMost(60_000))
                if (failures < 6) requestPull()
            }
        }
    }

    private suspend fun heartbeatLoop() {
        delay(15_000)
        while (scope.isActive) {
            try {
                api.heartbeat(events.heartbeat())
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceRevokedException) {
                revoke(); return
            } catch (e: Exception) {
                Log.i(TAG, "heartbeat failed: ${e.message}")
            }
            delay(HEARTBEAT_MS)
        }
    }

    fun sendHeartbeatNow() = scope.launch { runCatching { api.heartbeat(events.heartbeat()) } }

    private fun handleMessage(text: String) {
        val msg = runCatching { KinJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (msg.str("type")) {
            "hello" -> {
                _connection.value = Connection.Online
                clock.onServerTime(msg.str("server_time"))
                requestPull()
            }
            "changes" -> requestPull()
            "command" -> {
                val id = msg.str("id") ?: return
                val type = msg.str("command_type") ?: msg.str("command") ?: return
                val args = (msg["args"] as? JsonObject) ?: JsonObject(emptyMap())
                scope.launch {
                    val err = try {
                        events.onCommand(type, args)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "command $type failed", e)
                        "failed"
                    }
                    send(
                        buildJsonObject {
                            put("type", JsonPrimitive("command_result"))
                            put("id", JsonPrimitive(id))
                            put("ok", JsonPrimitive(err == null))
                            put("error_code", err?.let { JsonPrimitive(it) } ?: JsonNull)
                        }.toString(),
                    )
                }
            }
            "claimed" -> events.onClaimed(msg.str("owner_name"))
            "join_request" -> (msg["request"] as? JsonObject)?.let(events::onJoinRequest)
            "revoked" -> revoke()
            else -> Unit // unknown types are ignored (README §7)
        }
    }

    private fun send(text: String): Boolean = ws.send(text)

    /** `state` message (README §6.1), sent on change and at most every 2 s. */
    fun reportState(screen: String, showingPlacementId: String?, brightness: Int, volume: Int) {
        val json = buildJsonObject {
            put("type", JsonPrimitive("state"))
            put("screen", JsonPrimitive(screen))
            put("showing_placement_id", showingPlacementId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("brightness", JsonPrimitive(brightness))
            put("volume", JsonPrimitive(volume))
        }.toString()
        if (json == lastStateJson) return
        lastStateJson = json
        if (stateJob?.isActive == true) return
        stateJob = scope.launch {
            send(lastStateJson ?: return@launch)
            delay(2_000)
            // Send the latest value if it changed during the quiet period.
            lastStateJson?.let { if (it != json) send(it) }
        }
    }

    private fun revoke() {
        if (revoked) return
        revoked = true
        events.onRevoked()
    }

    /** Power-off/restart: stop downloads, flush acks and the outbox briefly, close the websocket. */
    suspend fun shutdown(flushTimeoutMs: Long = 4_000) {
        withTimeoutOrNull(flushTimeoutMs) {
            runCatching { acks.flush(api) }
        }
        ws.stop(1001, "shutting down")
        scope.cancel()
    }

    fun stop() {
        ws.stop()
        scope.cancel()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TAG = "KinSession"
        const val POLL_MS = 5 * 60_000L
        const val HEARTBEAT_MS = 5 * 60_000L

        @Suppress("unused")
        fun uptimeS() = SystemClock.elapsedRealtime() / 1000

        @Suppress("unused")
        private fun JsonElement.longOrNullSafe() = (this as? JsonPrimitive)?.longOrNull
    }
}
