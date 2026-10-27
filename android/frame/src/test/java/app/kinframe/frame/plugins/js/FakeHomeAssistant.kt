package app.kinframe.frame.plugins.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Enough of Home Assistant for the kinframe.ha-* plugins: `GET /api/states/<id>` from a fixture, `POST
 * /api/services/...` recorded, a picture behind the media proxy, and the WebSocket API's auth and
 * `subscribe_entities` (the full states as one "a" event, later changes as "c" events via [change]).
 */
class FakeHomeAssistant(fixture: File, val token: String = "test-token") {
    val server = MockWebServer()
    val states = LinkedHashMap<String, JsonObject>()
    val serviceCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val restReads = CopyOnWriteArrayList<String>()
    @Volatile var templates = 0
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile var subscribed: List<String> = emptyList()

    init {
        Json.parseToJsonElement(fixture.readText()).let { it as JsonArray }.forEach { e ->
            val o = e.jsonObject
            states[(o["entity_id"] as JsonPrimitive).content] = o
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/api/websocket") return MockResponse.Builder().webSocketUpgrade(socket()).build()
                if (path.startsWith("/api/media_player_proxy/")) return MockResponse.Builder().body(okio.Buffer().write(PNG)).build()
                if (request.headers["Authorization"] != "Bearer $token") return MockResponse.Builder().code(401).body("{}").build()
                if (path.startsWith("/api/states/")) {
                    val id = path.removePrefix("/api/states/")
                    restReads += id
                    return states[id]?.let { MockResponse.Builder().body(it.toString()).build() } ?: MockResponse.Builder().code(404).body("{}").build()
                }
                if (path == "/api/template" && request.method == "POST") {
                    // Renders only the frame's entity listing (docs/plugins.md §6.2), trimmed as Home Assistant does.
                    val template = (Json.parseToJsonElement(request.body!!.utf8()).jsonObject["template"] as JsonPrimitive).content
                    if (template != app.kinframe.frame.plugins.PluginReports.ENTITY_TEMPLATE) return MockResponse.Builder().code(400).body("{}").build()
                    templates += 1
                    return MockResponse.Builder().body(states.values.joinToString("") { o ->
                        val a = o["attributes"] as? JsonObject ?: JsonObject(emptyMap())
                        fun attr(k: String) = (a[k] as? JsonPrimitive)?.content.orEmpty()
                        "${(o["entity_id"] as JsonPrimitive).content}\t${attr("friendly_name")}\t${attr("unit_of_measurement")}\t${attr("device_class")}\n"
                    }.trim()).build()
                }
                if (path.startsWith("/api/services/") && request.method == "POST") {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    serviceCalls += path.removePrefix("/api/services/") to body
                    val target = (body["entity_id"] as? JsonPrimitive)?.content
                    if (path.endsWith("/media_play_pause") && target != null && target in states) {
                        set(target, if ((states.getValue(target)["state"] as JsonPrimitive).content == "playing") "paused" else "playing")
                    }
                    return MockResponse.Builder().body("[]").build()
                }
                return MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    val baseUrl: String get() = "http://${server.hostName}:${server.port}"

    fun set(id: String, state: String) {
        states[id] = JsonObject(states.getValue(id) + ("state" to JsonPrimitive(state)))
    }

    /** Sends a state change to subscribed sockets, the way Home Assistant does ("c" with "+"). */
    fun change(id: String, state: String) {
        set(id, state)
        val msg = buildJsonObject {
            put("id", 1); put("type", "event")
            put("event", buildJsonObject { put("c", buildJsonObject { put(id, buildJsonObject { put("+", buildJsonObject { put("s", state); put("lu", System.currentTimeMillis() / 1000.0) }) }) }) })
        }
        sockets.forEach { it.send(msg.toString()) }
    }

    private fun compressed(o: JsonObject) = buildJsonObject {
        put("s", o["state"]!!)
        put("a", o["attributes"] ?: JsonObject(emptyMap()))
        val lu = (o["last_updated"] as? JsonPrimitive)?.contentOrNull?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() / 1000.0 } ?: 0.0
        put("lu", lu); put("lc", lu)
    }

    private fun socket() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text).jsonObject
            when ((m["type"] as JsonPrimitive).content) {
                "auth" -> webSocket.send(if ((m["access_token"] as JsonPrimitive).content == token) """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                "subscribe_entities" -> {
                    val id = (m["id"] as JsonPrimitive).content
                    val ids = (m["entity_ids"] as JsonArray).map { (it as JsonPrimitive).content }
                    subscribed = ids
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    val add = buildJsonObject { ids.forEach { e -> states[e]?.let { put(e, compressed(it)) } } }
                    webSocket.send(buildJsonObject { put("id", id.toInt()); put("type", "event"); put("event", buildJsonObject { put("a", add) }) }.toString())
                }
            }
        }
    }

    fun close() = server.close()

    companion object {
        /** A 4×4 red PNG (album art). */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg==",
        )
    }
}
