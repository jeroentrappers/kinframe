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

    @Test fun parsesAnIconRotation() {
        val v = valid("""{"type":"value","icon":"direction","icon_rotation":73,"value":"2,3 m/s"}""") as ValueView
        assertEquals(73f, v.iconRotation)
        assertEquals(null, (valid("""{"type":"value","value":"1"}""") as ValueView).iconRotation)
        assertEquals("/icon_rotation", invalid("""{"type":"value","value":"1","icon_rotation":-10}""").path)
        assertEquals("/icon_rotation", invalid("""{"type":"value","value":"1","icon_rotation":"90"}""").path)
        assertEquals("/items/0/icon_rotation", invalid("""{"type":"rows","items":[{"type":"value","value":"1","icon_rotation":400}]}""").path)
        // Only values turn their icon.
        assertEquals("/icon_rotation", invalid("""{"type":"text","lines":["a"],"icon":"direction","icon_rotation":10}""").path)
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
        valid("""{"type":"sparkline","points":[${(1..100).joinToString(",")}]}""")
        invalid("""{"type":"sparkline","points":[${(1..101).joinToString(",")}]}""")
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

    @Test fun dialIsTwoSided() {
        val d = PluginViewParser.parse(ViewJson.gauge(-40f, label = "1.600 W", style = GaugeStyle.Dial, tone = Tone.Info, negativeTone = Tone.Good))
        val g = (d as ViewResult.Valid).view as GaugeView
        assertEquals(-40f, g.percent)
        assertEquals(Tone.Good, g.negativeTone)
        assertEquals("/value", invalid("""{"type":"gauge","value":-101,"style":"dial"}""").path)
        assertEquals("/negative_tone", invalid("""{"type":"gauge","value":10,"style":"bar","negative_tone":"good"}""").path)
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
        assertEquals(ViewLimits.MAX_LIST_ROWS, maxItems("list", "rows"))
        Tone.entries.forEach { assertTrue("d.ts lacks tone ${it.json}", dts.contains("'${it.json}'")) }
        @Suppress("UNUSED_VARIABLE") val unused = JsonArray(emptyList()) to JsonObject(emptyMap())
    }

    @Test fun parsesAList() {
        val l = valid(dir.resolve("examples/list-departures.json").readText()) as ListView
        assertEquals("Gent-Sint-Pieters", l.label)
        assertEquals(4, l.rows.size)
        assertEquals(ListRow("Brussels Airport - Zaventem", "22:23", "spoor 2", "+3", "train", Tone.Warn), l.rows[0])
        assertEquals(ListView(listOf(ListRow("Kortrijk"))), valid("""{"type":"list","rows":[{"title":"Kortrijk"}]}"""))
        assertEquals("/rows", invalid("""{"type":"list","rows":[]}""").path)
        assertEquals("/rows/0/title", invalid("""{"type":"list","rows":[{"time":"22:23"}]}""").path)
        assertEquals("/rows/0/time", invalid("""{"type":"list","rows":[{"title":"a","time":"${"x".repeat(11)}"}]}""").path)
        assertEquals("/rows/0/badge", invalid("""{"type":"list","rows":[{"title":"a","badge":"${"x".repeat(11)}"}]}""").path)
        assertEquals("/rows/0/detail", invalid("""{"type":"list","rows":[{"title":"a","detail":"${"x".repeat(17)}"}]}""").path)
        assertEquals("/rows/0/icon", invalid("""{"type":"list","rows":[{"title":"a","icon":"tram"}]}""").path)
        assertEquals("/label", invalid("""{"type":"list","label":"${"x".repeat(33)}","rows":[{"title":"a"}]}""").path)
        // A list is a component like any other: it can sit in a stack.
        val st = valid("""{"type":"stack","items":[{"type":"text","lines":["a"]},{"type":"list","rows":[{"title":"b"}]}]}""") as StackView
        assertTrue(st.items[1] is ListView)
    }

    @Test fun sparklineBarsHighlightAndMarker() {
        val s = valid(dir.resolve("examples/sparkline-prices-bars.json").readText()) as SparklineView
        assertEquals(SparklineStyle.Bars, s.style)
        assertEquals(100, s.points.size)
        assertEquals(53, s.highlight)
        assertEquals(Tone.Good, s.highlightTone)
        assertEquals(76, s.marker)
        assertEquals(s.points.indices.minBy { s.points[it] }, s.highlight)
        // Defaults: a line, nothing picked out; 100 points for lines too.
        val line = valid("""{"type":"sparkline","points":[${(1..100).joinToString(",")}]}""") as SparklineView
        assertEquals(SparklineStyle.Line, line.style)
        assertEquals(null, line.highlight)
        assertEquals(null, line.marker)
        assertEquals("/style", invalid("""{"type":"sparkline","points":[1,2],"style":"area"}""").path)
        assertEquals("/highlight", invalid("""{"type":"sparkline","points":[1,2],"highlight":-1}""").path)
        assertEquals("/highlight", invalid("""{"type":"sparkline","points":[1,2],"highlight":0.5}""").path)
        assertEquals("/highlight", invalid("""{"type":"sparkline","points":[1,2],"highlight":"0"}""").path)
        assertEquals("/marker", invalid("""{"type":"sparkline","points":[1,2],"marker":2}""").path)
        assertEquals("/highlight_tone", invalid("""{"type":"sparkline","points":[1,2],"highlight_tone":"good"}""").path)
        assertEquals("/highlight_tone", invalid("""{"type":"sparkline","points":[1,2],"highlight":0,"highlight_tone":"green"}""").path)
        assertEquals("/points", invalid("""{"type":"sparkline","points":[${(1..101).joinToString(",")}]}""").path)
    }

    @Test fun quantityTones() {
        val r = valid(dir.resolve("examples/rows-utilities.json").readText()) as RowsView
        assertEquals(listOf(Tone.Info, Tone.Water, Tone.Gas, Tone.Heat), r.items.map { it.tone })
        assertEquals(Tone.Water, (valid("""{"type":"gauge","value":5,"tone":"water"}""") as GaugeView).tone)
        assertEquals(Tone.Heat, (valid("""{"type":"flow","nodes":[{"id":"a","icon":"fan","tone":"heat"},{"id":"b","icon":"home"}]}""") as FlowView).nodes[0].tone)
        assertEquals("/tone", invalid("""{"type":"value","value":"1","tone":"electricity"}""").path)
        val bad = invalid("""{"type":"value","value":"1","tone":"steam"}""")
        assertTrue(bad.reason, bad.reason.contains("water, gas or heat"))
    }

    @Test fun viewJsonBuildsTheNewComponents() {
        val l = PluginViewParser.parse(ViewJson.list(listOf(ViewJson.listRow("Kortrijk", time = "22:31", badge = "+1", tone = Tone.Warn)), label = "Gent")) as ViewResult.Valid
        assertEquals(ListView(listOf(ListRow("Kortrijk", "22:31", badge = "+1", tone = Tone.Warn)), "Gent"), l.view)
        val s = PluginViewParser.parse(ViewJson.sparkline(listOf(3f, 1f, 2f), style = SparklineStyle.Bars, highlight = 1, highlightTone = Tone.Good, marker = 2)) as ViewResult.Valid
        assertEquals(SparklineView(listOf(3f, 1f, 2f), style = SparklineStyle.Bars, highlight = 1, highlightTone = Tone.Good, marker = 2), s.view)
    }
}
