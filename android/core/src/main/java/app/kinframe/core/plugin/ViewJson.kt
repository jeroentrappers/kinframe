package app.kinframe.core.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds view JSON the way a plugin's `render()` returns it, for built-in widgets written in Kotlin. Going through
 * JSON (and [PluginViewParser]) keeps built-ins honest: they get exactly what a JavaScript plugin gets.
 */
object ViewJson {
    fun value(
        value: String, unit: String? = null, label: String? = null, icon: String? = null, trend: Trend? = null, tone: Tone = Tone.Neutral,
        iconRotation: Float? = null,
    ) =
        buildJsonObject {
            put("type", "value"); put("value", value)
            unit?.let { put("unit", it) }; label?.let { put("label", it) }; icon?.let { put("icon", it) }
            iconRotation?.let { put("icon_rotation", it) }
            trend?.let { put("trend", it.json) }; tone(tone)
        }

    fun rows(items: List<JsonObject>, tone: Tone = Tone.Neutral) = buildJsonObject {
        put("type", "rows"); put("items", JsonArray(items)); tone(tone)
    }

    fun gauge(
        percent: Float, label: String? = null, caption: String? = null, icon: String? = null, style: GaugeStyle = GaugeStyle.Ring,
        tone: Tone = Tone.Neutral, negativeTone: Tone? = null,
    ) = buildJsonObject {
        put("type", "gauge"); put("value", percent)
        label?.let { put("label", it) }; caption?.let { put("caption", it) }; icon?.let { put("icon", it) }
        if (style != GaugeStyle.Ring) put("style", style.json)
        tone(tone)
        negativeTone?.let { put("negative_tone", it.json) }
    }

    fun node(id: String, icon: String, value: String? = null, label: String? = null, tone: Tone = Tone.Neutral, percent: Float? = null) = buildJsonObject {
        put("id", id); put("icon", icon); value?.let { put("value", it) }; label?.let { put("label", it) }; tone(tone)
        percent?.let { put("percent", it) }
    }

    fun image(src: String, shape: ImageShape = ImageShape.Square, description: String? = null) = buildJsonObject {
        put("type", "image"); put("src", src); if (shape != ImageShape.Square) put("shape", shape.json); description?.let { put("description", it) }
    }

    fun action(id: String, icon: String, label: String? = null, primary: Boolean = false) = buildJsonObject {
        put("id", id); put("icon", icon); label?.let { put("label", it) }; if (primary) put("primary", true)
    }

    fun actions(items: List<JsonObject>) = buildJsonObject { put("type", "actions"); put("items", JsonArray(items)) }

    fun progress(value: Float, max: Float, at: Long? = null, running: Boolean = false, label: String? = null, time: Boolean = false, tone: Tone = Tone.Neutral) =
        buildJsonObject {
            put("type", "progress"); put("value", value); put("max", max); at?.let { put("at", it) }
            if (running) put("running", true); label?.let { put("label", it) }; if (time) put("time", true); tone(tone)
        }

    fun edge(from: String, to: String, power: Float) = buildJsonObject { put("from", from); put("to", to); put("power", power) }

    fun flow(nodes: List<JsonObject>, edges: List<JsonObject>, tone: Tone = Tone.Neutral) = buildJsonObject {
        put("type", "flow"); put("nodes", JsonArray(nodes)); put("edges", JsonArray(edges)); tone(tone)
    }

    fun sparkline(
        points: List<Float?>, min: Float? = null, max: Float? = null, label: String? = null, value: String? = null, showRange: Boolean = false,
        tone: Tone = Tone.Neutral, style: SparklineStyle = SparklineStyle.Line, highlight: Int? = null, highlightTone: Tone? = null, marker: Int? = null,
    ) =
        buildJsonObject {
            put("type", "sparkline")
            put("points", JsonArray(points.map { p -> if (p == null || p.isNaN()) JsonNull else JsonPrimitive(p) }))
            min?.let { put("min", it) }; max?.let { put("max", it) }
            label?.let { put("label", it) }; value?.let { put("value", it) }
            if (showRange) put("show_range", true)
            tone(tone)
            if (style != SparklineStyle.Line) put("style", style.json)
            highlight?.let { put("highlight", it) }; highlightTone?.let { put("highlight_tone", it.json) }
            marker?.let { put("marker", it) }
        }

    fun listRow(title: String, time: String? = null, detail: String? = null, badge: String? = null, icon: String? = null, tone: Tone = Tone.Neutral) =
        buildJsonObject {
            put("title", title); time?.let { put("time", it) }; detail?.let { put("detail", it) }
            badge?.let { put("badge", it) }; icon?.let { put("icon", it) }; tone(tone)
        }

    fun list(rows: List<JsonObject>, label: String? = null, tone: Tone = Tone.Neutral) = buildJsonObject {
        put("type", "list"); put("rows", JsonArray(rows)); label?.let { put("label", it) }; tone(tone)
    }

    fun text(lines: List<String>, icon: String? = null, tone: Tone = Tone.Neutral) = buildJsonObject {
        put("type", "text"); put("lines", JsonArray(lines.map { JsonPrimitive(it) })); icon?.let { put("icon", it) }; tone(tone)
    }

    fun stack(items: List<JsonElement>, direction: StackDirection = StackDirection.Vertical, tone: Tone = Tone.Neutral) = buildJsonObject {
        put("type", "stack"); put("items", JsonArray(items))
        if (direction != StackDirection.Vertical) put("direction", direction.json)
        tone(tone)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.tone(t: Tone) {
        if (t != Tone.Neutral) put("tone", t.json)
    }
}
