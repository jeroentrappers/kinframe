package app.kinframe.frame

import app.kinframe.core.api.KinJson
import app.kinframe.frame.slideshow.Stage
import app.kinframe.frame.slideshow.TransitionKind
import app.kinframe.frame.sync.NowShowing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `now_showing` message a mirroring TV follows (README §6.1). */
class NowShowingTest {
    private fun parse(s: String): JsonObject = KinJson.parseToJsonElement(s).jsonObject

    @Test
    fun slideReportCarriesTimingTransitionVideoAndNext() {
        val n = NowShowing(
            placementIds = listOf("a", "b"), startedAtMs = 1_700_000_000_000, transition = "slide", transitionMs = 450,
            direction = -1, kenBurns = false, holdMs = 30_450, video = NowShowing.Video(1_234, playing = true, live = false),
            nextPlacementId = "c", screen = "on",
        )
        val j = parse(n.toJson(1_700_000_002_500))
        assertEquals("now_showing", j["type"]!!.jsonPrimitive.content)
        assertEquals("a", j["placement_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), (j["placement_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("2023-11-14T22:13:20Z", j["started_at"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:22.500Z", j["sent_at"]!!.jsonPrimitive.content)
        assertEquals("slide", j["transition"]!!.jsonPrimitive.content)
        assertEquals("-1", j["direction"]!!.jsonPrimitive.content)
        assertEquals("1234", (j["video"] as JsonObject)["position_ms"]!!.jsonPrimitive.content)
        assertEquals("true", (j["video"] as JsonObject)["playing"]!!.jsonPrimitive.content)
        assertEquals("c", j["next_placement_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyScreenReportsNoPlacementButItsSleepState() {
        val n = NowShowing.of(Stage(seq = 3, kind = TransitionKind.Cut, durationMs = 0), 5, null, { null }, next = "x", screen = "sleeping")
        val j = parse(n.toJson(10))
        assertTrue(j["placement_id"] is JsonNull)
        assertEquals(0, (j["placement_ids"] as JsonArray).size)
        assertEquals("cut", j["transition"]!!.jsonPrimitive.content)
        assertEquals("sleeping", j["screen"]!!.jsonPrimitive.content)
        assertTrue(j["video"] is JsonNull)
        assertEquals("x", j["next_placement_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun collagesAreCappedAtSixIds() {
        val n = NowShowing((1..9).map { "p$it" }, 0, "crossfade", 800, 1, false, 1000, null, null, "on")
        assertEquals(6, (parse(n.toJson(0))["placement_ids"] as JsonArray).size)
    }
}
