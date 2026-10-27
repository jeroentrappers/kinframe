package app.kinframe.core.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/** The outcome of checking a view a plugin returned. */
sealed interface ViewResult {
    data class Valid(val view: PluginView) : ViewResult
    /** [path] is a JSON pointer-like location ("/items/1/value") so the owner and the author see where it went wrong. */
    data class Invalid(val path: String, val reason: String) : ViewResult {
        override fun toString() = "invalid view at ${path.ifEmpty { "/" }}: $reason"
    }
}

/**
 * Parses and validates API 1 views (docs/plugins.md §4, `docs/api/plugin-view.schema.json`). Strict on purpose:
 * unknown components, unknown properties, wrong types, texts that are too long, too many items or too deep a tree all
 * reject the whole view, and the frame keeps showing the previous one. Nothing is truncated or guessed silently, so a
 * view that works in the plugin kit's preview works on every frame.
 */
object PluginViewParser {
    private val json = Json { isLenient = false }

    fun parse(text: String): ViewResult {
        if (text.length > ViewLimits.MAX_JSON_BYTES || text.toByteArray(Charsets.UTF_8).size > ViewLimits.MAX_JSON_BYTES) {
            return ViewResult.Invalid("", "view is larger than ${ViewLimits.MAX_JSON_BYTES} bytes")
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ViewResult.Invalid("", "not valid JSON")
        }
        return parse(element)
    }

    fun parse(element: JsonElement): ViewResult = try {
        val ctx = Ctx()
        ViewResult.Valid(ctx.component(element, "", depth = 1))
    } catch (e: ViewError) {
        ViewResult.Invalid(e.path, e.reason)
    }

    private class ViewError(val path: String, val reason: String) : Exception(reason)

    private class Ctx {
        var components = 0

        fun fail(path: String, reason: String): Nothing = throw ViewError(path, reason)

        fun obj(e: JsonElement, path: String): JsonObject = e as? JsonObject ?: fail(path, "expected an object")

        fun allowOnly(o: JsonObject, path: String, keys: Set<String>) {
            o.keys.firstOrNull { it !in keys }?.let { fail("$path/$it", "unknown property") }
        }

        fun component(e: JsonElement, path: String, depth: Int): PluginView {
            val o = obj(e, path)
            if (depth > ViewLimits.MAX_DEPTH) fail(path, "nested deeper than ${ViewLimits.MAX_DEPTH} levels")
            if (++components > ViewLimits.MAX_COMPONENTS) fail(path, "more than ${ViewLimits.MAX_COMPONENTS} components")
            val type = string(o, "type", path, max = 16, required = true)!!
            return when (type) {
                "value" -> value(o, path)
                "rows" -> rows(o, path, depth)
                "gauge" -> gauge(o, path)
                "flow" -> flow(o, path)
                "sparkline" -> sparkline(o, path)
                "text" -> text(o, path)
                "stack" -> stack(o, path, depth)
                "image" -> image(o, path)
                "actions" -> actions(o, path)
                "progress" -> progress(o, path)
                "list" -> list(o, path)
                "none" -> {
                    if (depth != 1) fail("$path/type", "none can only be the whole view")
                    allowOnly(o, path, setOf("type"))
                    NoneView
                }
                else -> fail("$path/type", "unknown component \"$type\"")
            }
        }

        fun value(o: JsonObject, path: String): ValueView {
            allowOnly(o, path, setOf("type", "value", "unit", "label", "icon", "icon_rotation", "trend", "tone"))
            val rotation = number(o, "icon_rotation", path)
            if (rotation != null && (rotation < 0f || rotation > 360f)) fail("$path/icon_rotation", "expected degrees from 0 to 360")
            return ValueView(
                value = string(o, "value", path, ViewLimits.MAX_VALUE, required = true)!!,
                unit = string(o, "unit", path, ViewLimits.MAX_UNIT),
                label = string(o, "label", path, ViewLimits.MAX_LABEL),
                icon = icon(o, path),
                trend = string(o, "trend", path, 8)?.let { Trend.of(it) ?: fail("$path/trend", "expected up, down or flat") },
                tone = tone(o, path),
                iconRotation = rotation,
            )
        }

        fun rows(o: JsonObject, path: String, depth: Int): RowsView {
            allowOnly(o, path, setOf("type", "items", "tone"))
            val items = array(o, "items", path, 1, ViewLimits.MAX_ROWS)
            return RowsView(
                items.mapIndexed { i, item ->
                    val p = "$path/items/$i"
                    val v = component(item, p, depth + 1)
                    v as? ValueView ?: fail(p, "rows can only hold value components")
                },
                tone(o, path),
            )
        }

        fun gauge(o: JsonObject, path: String): GaugeView {
            allowOnly(o, path, setOf("type", "value", "label", "caption", "icon", "style", "tone", "negative_tone", "sub"))
            val style = string(o, "style", path, 8)?.let { GaugeStyle.of(it) ?: fail("$path/style", "expected ring, bar or dial") } ?: GaugeStyle.Ring
            val v = number(o, "value", path, required = true)!!
            if (style == GaugeStyle.Dial) {
                if (v < -100f || v > 100f) fail("$path/value", "expected a percentage from -100 to 100")
            } else {
                if (v < 0f || v > 100f) fail("$path/value", "expected a percentage from 0 to 100")
                if ("negative_tone" in o) fail("$path/negative_tone", "only a dial has a negative side")
            }
            return GaugeView(
                percent = v,
                label = string(o, "label", path, ViewLimits.MAX_GAUGE_LABEL),
                caption = string(o, "caption", path, ViewLimits.MAX_LABEL),
                icon = icon(o, path),
                style = style,
                tone = tone(o, path),
                negativeTone = if ("negative_tone" in o) tone(o, path, "negative_tone") else null,
                sub = if ("sub" in o) array(o, "sub", path, 1, ViewLimits.MAX_SUB).mapIndexed { i, e ->
                    val p = "$path/sub/$i"
                    val so = obj(e, p)
                    allowOnly(so, p, setOf("value", "icon", "tone"))
                    SubValue(string(so, "value", p, ViewLimits.MAX_SUB_TEXT, required = true)!!, icon(so, p), tone(so, p))
                } else emptyList(),
            )
        }

        fun image(o: JsonObject, path: String): ImageView {
            allowOnly(o, path, setOf("type", "src", "shape", "description", "tone"))
            val src = string(o, "src", path, ViewLimits.MAX_SRC, required = true)!!
            if (!src.startsWith("https://") && !src.startsWith("http://")) fail("$path/src", "expected an http(s) URL")
            return ImageView(
                src = src,
                shape = string(o, "shape", path, 8)?.let { ImageShape.of(it) ?: fail("$path/shape", "expected square or round") } ?: ImageShape.Square,
                description = string(o, "description", path, ViewLimits.MAX_DESCRIPTION),
                tone = tone(o, path),
            )
        }

        fun actions(o: JsonObject, path: String): ActionsView {
            allowOnly(o, path, setOf("type", "items", "tone"))
            val items = array(o, "items", path, 1, ViewLimits.MAX_ACTIONS).mapIndexed { i, e ->
                val p = "$path/items/$i"
                val a = obj(e, p)
                allowOnly(a, p, setOf("id", "icon", "label", "tone", "primary"))
                val id = string(a, "id", p, ViewLimits.MAX_ID, required = true)!!
                if (!ID.matches(id)) fail("$p/id", "expected a-z, 0-9, - and _")
                ActionItem(
                    id = id, icon = icon(a, p, required = true)!!, label = string(a, "label", p, ViewLimits.MAX_ACTION_LABEL),
                    tone = tone(a, p), primary = (a["primary"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
            if (items.map { it.id }.toSet().size != items.size) fail("$path/items", "action ids must be unique")
            return ActionsView(items, tone(o, path))
        }

        fun progress(o: JsonObject, path: String): ProgressView {
            allowOnly(o, path, setOf("type", "value", "min", "max", "at", "running", "label", "time", "style", "tone"))
            val min = number(o, "min", path) ?: 0f
            val max = number(o, "max", path, required = true)!!
            if (max <= min) fail("$path/max", "expected more than min")
            val v = number(o, "value", path, required = true)!!
            if (v < min || v > max) fail("$path/value", "expected min to max")
            val at = o["at"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toDoubleOrNull()?.toLong() ?: fail("$path/at", "expected epoch milliseconds") }
            return ProgressView(
                value = v, max = max, min = min, at = at,
                style = string(o, "style", path, 8)?.let { ProgressStyle.of(it) ?: fail("$path/style", "expected bar or marker") } ?: ProgressStyle.Bar,
                running = (o["running"] as? JsonPrimitive)?.booleanOrNull ?: false,
                label = string(o, "label", path, ViewLimits.MAX_LABEL),
                time = (o["time"] as? JsonPrimitive)?.booleanOrNull ?: false,
                tone = tone(o, path),
            )
        }

        fun flow(o: JsonObject, path: String): FlowView {
            allowOnly(o, path, setOf("type", "nodes", "edges", "tone"))
            val nodesJson = array(o, "nodes", path, ViewLimits.MIN_FLOW_NODES, ViewLimits.MAX_FLOW_NODES)
            val nodes = nodesJson.mapIndexed { i, e ->
                val p = "$path/nodes/$i"
                val n = obj(e, p)
                allowOnly(n, p, setOf("id", "icon", "value", "label", "tone", "percent"))
                val id = string(n, "id", p, ViewLimits.MAX_ID, required = true)!!
                if (!ID.matches(id)) fail("$p/id", "ids use a-z, 0-9, - and _")
                FlowNode(
                    id = id,
                    icon = icon(n, p, required = true)!!,
                    value = string(n, "value", p, ViewLimits.MAX_NODE_TEXT),
                    label = string(n, "label", p, ViewLimits.MAX_NODE_TEXT),
                    tone = tone(n, p),
                    percent = number(n, "percent", p)?.also { if (it < 0f || it > 100f) fail("$p/percent", "expected 0 to 100") },
                )
            }
            val index = HashMap<String, Int>()
            nodes.forEachIndexed { i, n -> if (index.put(n.id, i) != null) fail("$path/nodes/$i/id", "duplicate id \"${n.id}\"") }
            val edgesJson = if (o.containsKey("edges")) array(o, "edges", path, 0, ViewLimits.MAX_FLOW_EDGES) else JsonArray(emptyList())
            val edges = edgesJson.mapIndexed { i, e ->
                val p = "$path/edges/$i"
                val ed = obj(e, p)
                allowOnly(ed, p, setOf("from", "to", "power"))
                val from = string(ed, "from", p, ViewLimits.MAX_ID, required = true)!!
                val to = string(ed, "to", p, ViewLimits.MAX_ID, required = true)!!
                val fi = index[from] ?: fail("$p/from", "no node \"$from\"")
                val ti = index[to] ?: fail("$p/to", "no node \"$to\"")
                if (fi == ti) fail(p, "an edge needs two different nodes")
                val power = number(ed, "power", p) ?: 0f
                if (power < 0f) fail("$p/power", "power is a magnitude (>= 0); swap from and to for the other direction")
                FlowEdge(fi, ti, power)
            }
            return FlowView(nodes, edges, tone(o, path))
        }

        fun list(o: JsonObject, path: String): ListView {
            allowOnly(o, path, setOf("type", "rows", "label", "tone"))
            val rows = array(o, "rows", path, 1, ViewLimits.MAX_LIST_ROWS).mapIndexed { i, e ->
                val p = "$path/rows/$i"
                val r = obj(e, p)
                allowOnly(r, p, setOf("title", "time", "detail", "badge", "icon", "tone"))
                ListRow(
                    title = string(r, "title", p, ViewLimits.MAX_LIST_TITLE, required = true)!!,
                    time = string(r, "time", p, ViewLimits.MAX_LIST_TIME),
                    detail = string(r, "detail", p, ViewLimits.MAX_LIST_DETAIL),
                    badge = string(r, "badge", p, ViewLimits.MAX_LIST_BADGE),
                    icon = icon(r, p),
                    tone = tone(r, p),
                )
            }
            return ListView(rows, string(o, "label", path, ViewLimits.MAX_LABEL), tone(o, path))
        }

        fun sparkline(o: JsonObject, path: String): SparklineView {
            allowOnly(o, path, setOf("type", "points", "min", "max", "label", "value", "show_range", "tone", "style", "highlight", "highlight_tone", "marker"))
            val pts = array(o, "points", path, ViewLimits.MIN_POINTS, ViewLimits.MAX_POINTS).mapIndexed { i, e ->
                when {
                    e is JsonNull -> Float.NaN
                    e is JsonPrimitive && !e.isString -> e.floatOrNull?.takeIf { it.isFinite() && kotlin.math.abs(it) <= ViewLimits.MAX_MAGNITUDE }
                        ?: fail("$path/points/$i", "expected a finite number or null")
                    else -> fail("$path/points/$i", "expected a number or null")
                }
            }
            if (pts.all { it.isNaN() }) fail("$path/points", "needs at least one number")
            val min = number(o, "min", path, signed = true)
            val max = number(o, "max", path, signed = true)
            if (min != null && max != null && min >= max) fail("$path/min", "min must be below max")
            val showRange = o["show_range"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: fail("$path/show_range", "expected true or false") } ?: false
            fun index(key: String): Int? {
                val v = number(o, key, path) ?: return null
                val i = v.toInt()
                if (i.toFloat() != v || i < 0 || i >= pts.size) fail("$path/$key", "expected an index of points (0 to ${pts.size - 1})")
                if (pts[i].isNaN()) fail("$path/$key", "points a gap (null)")
                return i
            }
            val highlight = index("highlight")
            if ("highlight_tone" in o && highlight == null) fail("$path/highlight_tone", "needs highlight")
            return SparklineView(
                pts, min, max,
                label = string(o, "label", path, ViewLimits.MAX_LABEL),
                value = string(o, "value", path, ViewLimits.MAX_VALUE),
                showRange = showRange,
                tone = tone(o, path),
                style = string(o, "style", path, 8)?.let { SparklineStyle.of(it) ?: fail("$path/style", "expected line or bars") } ?: SparklineStyle.Line,
                highlight = highlight,
                highlightTone = if ("highlight_tone" in o) tone(o, path, "highlight_tone") else null,
                marker = index("marker"),
            )
        }

        fun text(o: JsonObject, path: String): TextView {
            allowOnly(o, path, setOf("type", "lines", "icon", "tone"))
            val lines = array(o, "lines", path, 1, ViewLimits.MAX_TEXT_LINES).mapIndexed { i, e ->
                checkText(e, "$path/lines/$i", ViewLimits.MAX_TEXT_LINE)
            }
            return TextView(lines, icon(o, path), tone(o, path))
        }

        fun stack(o: JsonObject, path: String, depth: Int): StackView {
            allowOnly(o, path, setOf("type", "items", "direction", "tone"))
            val dir = string(o, "direction", path, 12)?.let { StackDirection.of(it) ?: fail("$path/direction", "expected vertical or horizontal") }
                ?: StackDirection.Vertical
            val items = array(o, "items", path, 1, ViewLimits.MAX_STACK_ITEMS).mapIndexed { i, e -> component(e, "$path/items/$i", depth + 1) }
            return StackView(items, dir, tone(o, path))
        }

        // ------------------------------------------------------------------ primitives

        fun array(o: JsonObject, key: String, path: String, min: Int, max: Int): JsonArray {
            val a = o[key] ?: fail("$path/$key", "required")
            if (a !is JsonArray) fail("$path/$key", "expected an array")
            if (a.size < min) fail("$path/$key", "needs at least $min item${if (min == 1) "" else "s"}")
            if (a.size > max) fail("$path/$key", "at most $max items")
            return a
        }

        fun string(o: JsonObject, key: String, path: String, max: Int, required: Boolean = false): String? {
            val e = o[key] ?: return if (required) fail("$path/$key", "required") else null
            return checkText(e, "$path/$key", max)
        }

        fun checkText(e: JsonElement, path: String, max: Int): String {
            val p = e as? JsonPrimitive
            if (p == null || !p.isString) fail(path, "expected a string")
            val s = p.content
            if (s.isBlank()) fail(path, "must not be empty")
            val len = s.codePointCount(0, s.length)
            if (len > max) fail(path, "longer than $max characters")
            if (s.any { it.isISOControl() || it == ' ' || it == ' ' }) fail(path, "contains line breaks or control characters")
            return s
        }

        fun number(o: JsonObject, key: String, path: String, required: Boolean = false, signed: Boolean = false): Float? {
            val e = o[key] ?: return if (required) fail("$path/$key", "required") else null
            val p = e as? JsonPrimitive
            if (p == null || p.isString) fail("$path/$key", "expected a number")
            val f = p.floatOrNull
            if (f == null || !f.isFinite() || kotlin.math.abs(f) > ViewLimits.MAX_MAGNITUDE) fail("$path/$key", "expected a finite number")
            if (!signed && f.isNaN()) fail("$path/$key", "expected a number")
            return f
        }

        fun tone(o: JsonObject, path: String, key: String = "tone"): Tone =
            string(o, key, path, 8)?.let { Tone.of(it) ?: fail("$path/$key", "expected neutral, good, warn, bad, accent, info, water, gas or heat") } ?: Tone.Neutral

        fun icon(o: JsonObject, path: String, required: Boolean = false): String? {
            val name = string(o, "icon", path, 24, required) ?: return null
            if (!PluginIconNames.isKnown(name)) fail("$path/icon", "unknown icon \"$name\"")
            return name
        }
    }

    private val ID = Regex("^[a-z0-9_-]+$")
}
