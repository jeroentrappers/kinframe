package app.kinframe.family.data

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Send
import app.kinframe.core.ws.ReconnectingWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Messages on the user websocket (README §6.2). Unknown types are ignored. */
sealed interface LiveEvent {
    data class SendUpdated(val send: Send) : LiveEvent
    data class Reaction(val placementId: String, val frameId: String, val reaction: String?, val byFrameName: String?) : LiveEvent
    data class Reply(val placementId: String, val frameId: String, val preset: String) : LiveEvent
    data class FrameStatus(val frameId: String, val online: Boolean, val lastSeenAt: String?) : LiveEvent
    data class JoinRequested(val frameId: String, val request: JoinRequest?) : LiveEvent
    data class MembershipUpdated(val frameId: String) : LiveEvent

    companion object {
        fun parse(text: String): LiveEvent? = runCatching {
            val o = KinJson.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            when (s("type")) {
                "send.updated" -> SendUpdated(KinJson.decodeFromJsonElement(Send.serializer(), o["send"]!!))
                "reaction" -> Reaction(s("placement_id")!!, s("frame_id")!!, s("reaction"), s("by_frame_name"))
                "reply" -> Reply(s("placement_id")!!, s("frame_id")!!, s("preset")!!)
                "frame.status" -> FrameStatus(s("frame_id")!!, o["online"]?.jsonPrimitive?.booleanOrNull ?: false, s("last_seen_at"))
                "join_request" -> JoinRequested(
                    s("frame_id")!!,
                    (o["request"] as? JsonObject)?.let { KinJson.decodeFromJsonElement(JoinRequest.serializer(), it) },
                )
                "membership.updated" -> MembershipUpdated(s("frame_id")!!)
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * The user websocket while the app is in the foreground. When it is closed the server sends push notifications
 * instead (README §6.3), so it is only open while someone looks at the app.
 */
class LiveEvents(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val tokens: AccessTokenSource,
) {
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private var socket: ReconnectingWebSocket? = null
    private var api: KinApi? = null

    val status: StateFlow<ReconnectingWebSocket.Status>? get() = socket?.status

    @Synchronized
    fun start(api: KinApi) {
        if (this.api === api && socket != null) return
        stop()
        this.api = api
        val ws = ReconnectingWebSocket(http, scope, { api.webSocketRequest() }, object : ReconnectingWebSocket.Listener {
            override fun onMessage(text: String) {
                LiveEvent.parse(text)?.let { _events.tryEmit(it) }
            }

            override fun onUnauthorized() {
                // 4401 at token expiry: reconnect with a fresh token.
                scope.launch {
                    if (runCatching { tokens.accessToken(forceRefresh = true) }.isSuccess) socket?.start()
                }
            }
        }, tag = "KinUserWs")
        socket = ws
        ws.start()
    }

    @Synchronized
    fun stop() {
        socket?.stop()
        socket = null
        api = null
    }

    fun reconnectNow() = socket?.reconnectNow()

    /** For tests and previews. */
    fun emit(event: LiveEvent) = _events.tryEmit(event)
}
