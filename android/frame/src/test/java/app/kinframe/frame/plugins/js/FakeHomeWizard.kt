package app.kinframe.frame.plugins.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A HomeWizard device's local API v1, enough for the kinframe.homewizard-* plugins: `GET /api`, `GET /api/v1/data`
 * from a fixture (change it with [data]), and for an energy socket `GET`/`PUT /api/v1/state`, which refuses to switch
 * while `switch_lock` is on, like the real socket.
 */
class FakeHomeWizard(fixtures: File, dataFixture: String, stateFixture: String? = null) {
    val server = MockWebServer()
    @Volatile var data: String = fixtures.resolve(dataFixture).readText()
    @Volatile var state: JsonObject? = stateFixture?.let { Json.parseToJsonElement(fixtures.resolve(it).readText()).jsonObject }
    private val device = fixtures.resolve("device.json").readText()
    /** "GET /api/v1/data", "PUT /api/v1/state {…}", … in order. */
    val requests = CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = request.body?.utf8().orEmpty()
                requests += "${request.method} $path" + if (body.isNotEmpty()) " $body" else ""
                return when {
                    request.method == "GET" && path == "/api" -> ok(device)
                    request.method == "GET" && path == "/api/v1/data" -> ok(data)
                    path == "/api/v1/state" && state != null -> when (request.method) {
                        "GET" -> ok(state.toString())
                        "PUT" -> put(Json.parseToJsonElement(body).jsonObject)
                        else -> MockResponse.Builder().code(405).build()
                    }
                    else -> MockResponse.Builder().code(404).body("""{"error":{"id":0,"description":"Not found"}}""").build()
                }
            }
        }
        server.start()
    }

    private fun ok(body: String) = MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build()

    private fun put(patch: JsonObject): MockResponse {
        val s = state!!
        val locked = (s["switch_lock"] as? JsonPrimitive)?.booleanOrNull == true
        if (locked && "power_on" in patch) return MockResponse.Builder().code(400).body("""{"error":{"id":201,"description":"switch_lock is on"}}""").build()
        state = JsonObject(s + patch)
        return ok(patch.toString())
    }

    /** The address as an owner would type it. */
    val host: String get() = "${server.hostName}:${server.port}"

    fun close() = server.close()
}
