package app.kinframe.core.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The API 1 view validator against the shared fixtures in docs/api/plugin-views and the limits of docs/plugins.md §4. */
class PluginViewParserTest {
    private val dir = File("../../docs/api/plugin-views")

    private fun valid(json: String): PluginView = when (val r = PluginViewParser.parse(json)) {
        is ViewResult.Valid -> r.view
        is ViewResult.Invalid -> { fail("expected valid, got $r"); throw AssertionError() }
    }

    private fun invalid(json: String): ViewResult.Invalid = when (val r = PluginViewParser.parse(json)) {
        is ViewResult.Invalid -> r
        is ViewResult.Valid -> { fail("expected invalid: $json"); throw AssertionError() }
    }

    @Test fun everyExampleIsValid() {
        val files = dir.resolve("examples").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("examples present", files.size >= 10)
        files.forEach { f ->
            val r = PluginViewParser.parse(f.readText())
            assertTrue("${f.name}: $r", r is ViewResult.Valid)
        }
    }

    @Test fun everyInvalidFixtureFailsAtItsPath() {
        val files = dir.resolve("invalid").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue(files.size >= 10)
        files.forEach { f ->
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            val r = PluginViewParser.parse(o["view"]!!)
            assertTrue("${f.name} should be invalid", r is ViewResult.Invalid)
            assertEquals(f.name, o["\$error_path"]!!.jsonPrimitive.content, (r as ViewResult.Invalid).path)
        }
    }

    @Test fun parsesAValue() {
        val v = valid("""{"type":"value","icon":"bolt","value":"1,2 kW","label":"Using","trend":"up","tone":"warn"}""") as ValueView
        assertEquals(ValueView("1,2 kW", null, "Using", "bolt", Trend.Up, Tone.Warn), v)
    }

    @Test fun parsesAFlowWithEdgeIndexes() {
        val f = valid(dir.resolve("examples/flow-solar-home-grid.json").readText()) as FlowView
        assertEquals(listOf("solar", "home", "grid"), f.nodes.map { it.id })
        assertEquals(FlowEdge(0, 1, 1200f), f.edges[0])
        assertEquals(FlowEdge(0, 2, 2200f), f.edges[1])
    }

    @Test fun sparklineNullsAreGaps() {
        val s = valid("""{"type":"sparkline","points":[1,null,3]}""") as SparklineView
        assertEquals(3, s.points.size)
        assertTrue(s.points[1].isNaN())
        invalid("""{"type":"sparkline","points":[null,null]}""")
        invalid("""{"type":"sparkline","points":[1,2],"min":5,"max":5}""")
    }

    @Test fun depthLimit() {
        valid("""{"type":"stack","items":[{"type":"stack","direction":"horizontal","items":[{"type":"value","value":"1"}]}]}""")
        valid("""{"type":"stack","items":[{"type":"rows","items":[{"type":"value","value":"1"}]}]}""")
        // rows inside a nested stack puts its values at depth 4.
        assertEquals(
            "/items/0/items/0/items/0",
            invalid("""{"type":"stack","items":[{"type":"stack","items":[{"type":"rows","items":[{"type":"value","value":"1"}]}]}]}""").path,
        )
    }

    @Test fun componentCountLimit() {
        fun rows(n: Int) = """{"type":"rows","items":[${(1..n).joinToString(",") { """{"type":"value","value":"$it"}""" }}]}"""
        // stack(1) + 4 × rows(1 + 4) = 21 components: fine.
        valid("""{"type":"stack","items":[${List(4) { rows(4) }.joinToString(",")}]}""")
        // stack(1) + 4 × stack(1 + 4 × rows(1+... too deep, so build width instead: stack › 4 × stack › 4 values = 21.
        val inner = """{"type":"stack","items":[${List(4) { """{"type":"value","value":"1"}""" }.joinToString(",")}]}"""
        valid("""{"type":"stack","items":[${List(4) { inner }.joinToString(",")}]}""")
        assertEquals(24, ViewLimits.MAX_COMPONENTS)
    }

    @Test fun textLimits() {
        valid("""{"type":"text","lines":["${"x".repeat(60)}","${"y".repeat(60)}"]}""")
        invalid("""{"type":"text","lines":["${"x".repeat(61)}"]}""")
        invalid("""{"type":"text","lines":["a","b","c"]}""")
        invalid("""{"type":"text","lines":[]}""")
        invalid("""{"type":"text","lines":["  "]}""")
        // Code points, not UTF-16 units: 60 emoji are fine.
        valid("""{"type":"text","lines":["${"😀".repeat(60)}"]}""")
        invalid("""{"type":"value","value":"${"9".repeat(17)}"}""")
        invalid("""{"type":"gauge","value":5,"label":"${"x".repeat(13)}"}""")
    }

    @Test fun pointLimits() {
        valid("""{"type":"sparkline","points":[${(1..96).joinToString(",")}]}""")
        invalid("""{"type":"sparkline","points":[${(1..97).joinToString(",")}]}""")
        invalid("""{"type":"sparkline","points":[1]}""")
        invalid("""{"type":"sparkline","points":[1,"2"]}""")
    }

    @Test fun flowLimits() {
        fun nodes(n: Int) = (1..n).joinToString(",") { """{"id":"n$it","icon":"home"}""" }
        valid("""{"type":"flow","nodes":[${nodes(5)}]}""")
        invalid("""{"type":"flow","nodes":[${nodes(6)}]}""")
        invalid("""{"type":"flow","nodes":[${nodes(1)}]}""")
        assertEquals("/nodes/1/id", invalid("""{"type":"flow","nodes":[{"id":"a","icon":"home"},{"id":"a","icon":"sun"}]}""").path)
        assertEquals("/nodes/0/id", invalid("""{"type":"flow","nodes":[{"id":"Solar","icon":"home"},{"id":"a","icon":"sun"}]}""").path)
        invalid("""{"type":"flow","nodes":[${nodes(2)}],"edges":[{"from":"n1","to":"n1","power":1}]}""")
        val nine = (1..9).joinToString(",") { """{"from":"n1","to":"n2","power":$it}""" }
        invalid("""{"type":"flow","nodes":[${nodes(2)}],"edges":[$nine]}""")
    }

    @Test fun typesAreStrict() {
        invalid("""{"type":"value","value":12}""")
        invalid("""{"type":"gauge","value":"68"}""")
        invalid("""{"type":"value","value":"1","tone":"red"}""")
        invalid("""{"type":"stack","items":[{"type":"value","value":"1"}],"direction":"diagonal"}""")
        invalid("""[]""")
        invalid("""not json""")
        invalid("""{"value":"1"}""")
    }

    @Test fun sizeLimit() {
        val big = """{"type":"text","lines":["a"],"pad":"${"x".repeat(ViewLimits.MAX_JSON_BYTES)}"}"""
        assertEquals("", invalid(big).path)
    }

    @Test fun builtWithViewJsonRoundTrips() {
        val j = ViewJson.stack(
            listOf(
                ViewJson.gauge(68f, icon = "car", tone = Tone.Good),
                ViewJson.text(listOf("Charging", "Full at 23:10")),
                ViewJson.flow(listOf(ViewJson.node("a", "sun"), ViewJson.node("b", "home")), listOf(ViewJson.edge("a", "b", 3f))),
                ViewJson.sparkline(listOf(1f, null, 2f), showRange = true),
            ),
            StackDirection.Horizontal,
        )
        val v = PluginViewParser.parse(j)
        assertTrue(v.toString(), v is ViewResult.Valid)
        assertTrue((v as ViewResult.Valid).view.animates())
    }

    /** The schema in docs/api must list exactly the icons and tones the frame knows. */
    @Test fun schemaMatchesTheKotlinLists() {
        val schema = Json.parseToJsonElement(dir.resolve("plugin-view.schema.json").readText()).jsonObject
        val defs = schema["\$defs"]!!.jsonObject
        val icons = defs["icon"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(PluginIconNames.all, icons)
        val tones = defs["tone"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Tone.entries.map { it.json }, tones)
        val dts = dir.resolve("plugin-view.d.ts").readText()
        PluginIconNames.all.forEach { assertTrue("d.ts lacks $it", dts.contains("'$it'")) }
        // Limits quoted in the schema.
        fun maxItems(def: String, prop: String) = (defs[def]!!.jsonObject["properties"]!!.jsonObject[prop]!!.jsonObject["maxItems"] as JsonPrimitive).content.toInt()
        assertEquals(ViewLimits.MAX_ROWS, maxItems("rows", "items"))
        assertEquals(ViewLimits.MAX_STACK_ITEMS, maxItems("stack", "items"))
        assertEquals(ViewLimits.MAX_FLOW_NODES, maxItems("flow", "nodes"))
        assertEquals(ViewLimits.MAX_FLOW_EDGES, maxItems("flow", "edges"))
        assertEquals(ViewLimits.MAX_POINTS, maxItems("sparkline", "points"))
        assertEquals(ViewLimits.MAX_TEXT_LINES, maxItems("text", "lines"))
        @Suppress("UNUSED_VARIABLE") val unused = JsonArray(emptyList()) to JsonObject(emptyMap())
    }
}
