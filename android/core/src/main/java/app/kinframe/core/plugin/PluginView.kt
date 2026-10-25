package app.kinframe.core.plugin

import androidx.compose.runtime.Immutable

/**
 * Plugin views, API 1 (docs/plugins.md §4). A plugin describes *what* to show as a small tree of components; the frame
 * draws it with its own type, colours and animations. The JSON form is specified in `docs/api/plugin-view.schema.json`
 * and parsed (strictly) by [PluginViewParser]. Everything here is immutable, so a new view that equals the previous
 * one costs nothing to "redraw" and equality doubles as the "value changed" test for the *only when changed* mode.
 */
@Immutable
sealed interface PluginView {
    val tone: Tone
}

/** Maps to the frame's palette; plugins never pick colours. */
enum class Tone(val json: String) {
    Neutral("neutral"), Good("good"), Warn("warn"), Bad("bad"), Accent("accent"), Info("info");

    companion object {
        fun of(json: String): Tone? = entries.firstOrNull { it.json == json }
    }
}

enum class Trend(val json: String) {
    Up("up"), Down("down"), Flat("flat");

    companion object {
        fun of(json: String): Trend? = entries.firstOrNull { it.json == json }
    }
}

enum class GaugeStyle(val json: String) {
    Ring("ring"), Bar("bar"), Dial("dial");

    companion object {
        fun of(json: String): GaugeStyle? = entries.firstOrNull { it.json == json }
    }
}

enum class StackDirection(val json: String) {
    Vertical("vertical"), Horizontal("horizontal");

    companion object {
        fun of(json: String): StackDirection? = entries.firstOrNull { it.json == json }
    }
}

/** Icon, big number, unit, optional label and trend arrow: `⚡ 1,2 kW ↑`. */
@Immutable
data class ValueView(
    /** Already formatted by the plugin (`ctx.format.power(1234)` → "1,2 kW"). */
    val value: String,
    /** Drawn smaller after the number. When absent, a trailing unit in [value] ("1,2 kW") is split off for display. */
    val unit: String? = null,
    val label: String? = null,
    val icon: String? = null,
    val trend: Trend? = null,
    override val tone: Tone = Tone.Neutral,
    /**
     * Turns [icon] clockwise by this many degrees (0–360), e.g. the `direction` arrow for a wind direction. Null draws
     * it upright.
     */
    val iconRotation: Float? = null,
) : PluginView

/** Up to four [ValueView]s stacked: grid / solar / home. */
@Immutable
data class RowsView(val items: List<ValueView>, override val tone: Tone = Tone.Neutral) : PluginView

/**
 * A ring or bar from 0–100 % with a short label inside: car battery 68 %. A [GaugeStyle.Dial] is two-sided: zero at
 * the top, −100–100 %, positive clockwise in [tone] and negative anticlockwise in [negativeTone] (grid use vs return).
 */
@Immutable
data class GaugeView(
    val percent: Float,
    val label: String? = null,
    val caption: String? = null,
    val icon: String? = null,
    val style: GaugeStyle = GaugeStyle.Ring,
    override val tone: Tone = Tone.Neutral,
    val negativeTone: Tone? = null,
    /** Up to three small values under the gauge, e.g. today's ↑ 11,8 / ↓ 14,5 kWh beside the live power. */
    val sub: List<SubValue> = emptyList(),
) : PluginView

/** A small secondary value: an optional icon (an arrow) and text in a tone. */
@Immutable
data class SubValue(val value: String, val icon: String? = null, val tone: Tone = Tone.Neutral)

/** [percent]: an optional ring around the node's circle (0–100), e.g. a home battery's charge. */
@Immutable
data class FlowNode(
    val id: String, val icon: String, val value: String? = null, val label: String? = null, val tone: Tone = Tone.Neutral,
    val percent: Float? = null,
)

/** An arrow from node [from] to node [to] (indexes into [FlowView.nodes]); its speed follows [power]. 0 = idle. */
@Immutable
data class FlowEdge(val from: Int, val to: Int, val power: Float)

/** Up to five nodes with animated arrows between them: solar → home ← grid. */
@Immutable
data class FlowView(val nodes: List<FlowNode>, val edges: List<FlowEdge>, override val tone: Tone = Tone.Neutral) : PluginView

/** A small line chart of up to 96 points; `NaN` marks a gap (null in JSON). */
@Immutable
data class SparklineView(
    val points: List<Float>,
    val min: Float? = null,
    val max: Float? = null,
    val label: String? = null,
    val value: String? = null,
    val showRange: Boolean = false,
    override val tone: Tone = Tone.Neutral,
) : PluginView

/** One or two short lines: "Charging · full at 23:10". */
@Immutable
data class TextView(val lines: List<String>, val icon: String? = null, override val tone: Tone = Tone.Neutral) : PluginView

/** A vertical or horizontal group of the above. */
@Immutable
data class StackView(
    val items: List<PluginView>,
    val direction: StackDirection = StackDirection.Vertical,
    override val tone: Tone = Tone.Neutral,
) : PluginView

/**
 * A picture the plugin points to (album art, a camera snapshot, a map tile): [src] is an http(s) URL the frame fetches
 * through the plugin's own network permissions, size-limited and decoded small, and caches. Drawn square or round at a
 * size set by the form. Until it has loaded, a quiet placeholder.
 */
@Immutable
data class ImageView(
    val src: String,
    val shape: ImageShape = ImageShape.Square,
    /** Read out by accessibility services; not drawn. */
    val description: String? = null,
    override val tone: Tone = Tone.Neutral,
) : PluginView

enum class ImageShape(val json: String) {
    Square("square"), Round("round");

    companion object {
        fun of(json: String): ImageShape? = entries.firstOrNull { it.json == json }
    }
}

/**
 * "Nothing to show right now" (the music stopped, no bin day this week): the widget is hidden and its place in the
 * band is freed. Only as the whole view. Returning null instead means "no new view" and keeps the last one.
 */
@Immutable
data object NoneView : PluginView {
    override val tone: Tone get() = Tone.Neutral
}

/** One button: [id] goes back to the plugin's `action(ctx, id)` handler. */
@Immutable
data class ActionItem(val id: String, val icon: String, val label: String? = null, val tone: Tone = Tone.Neutral, val primary: Boolean = false)

/**
 * A row of round icon buttons (play/pause, next, volume). Tappable in the detail card only; the corner and the band
 * don't draw them, so a widget never turns the photo into a remote control. Needs `permissions.actions` in the
 * manifest (checked by the host).
 */
@Immutable
data class ActionsView(val items: List<ActionItem>, override val tone: Tone = Tone.Neutral) : PluginView

/**
 * A thin progress bar with an optional label: a track's position, a wash cycle. While [running], the frame moves it on
 * by itself at one unit per second from [value] measured at [at] (epoch ms), so a plugin that refreshes every 10 s
 * still shows a smoothly moving bar. [time]: show "1:23 / 3:45" (units are seconds).
 */
@Immutable
data class ProgressView(
    val value: Float,
    val max: Float,
    /** The start of the track (default 0): a range like today's low to high temperature. */
    val min: Float = 0f,
    /** Bar: filled up to the value. Marker: a dot on the track (where today's temperature sits in its range). */
    val style: ProgressStyle = ProgressStyle.Bar,
    val at: Long? = null,
    val running: Boolean = false,
    val label: String? = null,
    val time: Boolean = false,
    override val tone: Tone = Tone.Neutral,
) : PluginView

enum class ProgressStyle(val json: String) {
    Bar("bar"), Marker("marker");

    companion object {
        fun of(json: String): ProgressStyle? = entries.firstOrNull { it.json == json }
    }
}

/** The limits a view must stay within (docs/plugins.md §4). The schema in docs/api mirrors these numbers. */
object ViewLimits {
    const val API = 1
    /** Serialized size the host accepts from a plugin before parsing. */
    const val MAX_JSON_BYTES = 16 * 1024
    /** Component nesting, the root counting as 1: `stack › stack › value` is the deepest allowed. */
    const val MAX_DEPTH = 3
    /** All components in one view, containers and rows' values included (flow nodes are not components). */
    const val MAX_COMPONENTS = 24
    const val MAX_STACK_ITEMS = 4
    const val MAX_ROWS = 4
    const val MIN_FLOW_NODES = 2
    const val MAX_FLOW_NODES = 5
    const val MAX_FLOW_EDGES = 8
    const val MIN_POINTS = 2
    const val MAX_POINTS = 96
    const val MAX_TEXT_LINES = 2
    const val MAX_TEXT_LINE = 60
    const val MAX_VALUE = 16
    const val MAX_UNIT = 8
    const val MAX_LABEL = 32
    const val MAX_GAUGE_LABEL = 12
    const val MAX_NODE_TEXT = 16
    const val MAX_ID = 24
    const val MAX_ACTIONS = 5
    const val MAX_SUB = 3
    const val MAX_SUB_TEXT = 12
    const val MAX_ACTION_LABEL = 16
    const val MAX_SRC = 2048
    const val MAX_DESCRIPTION = 60
    /** Edge power and sparkline values: anything bigger is a bug, not a measurement. */
    const val MAX_MAGNITUDE = 1e12f
}

/**
 * The fixed icon set of API 1. Names are kebab-case; [app.kinframe.core.design.PluginIcons] draws each one in the
 * kinframe line style. New icons can be added within API 1 (older frames reject a view that uses one they don't know,
 * so plugins should only use icons from the API version they declare).
 */
object PluginIconNames {
    val all: List<String> = listOf(
        // energy
        "bolt", "grid", "solar", "sun", "home", "battery", "battery-charging", "plug", "car", "flame", "gas", "meter",
        "water", "thermometer", "wind", "leaf", "euro", "fan", "lightbulb",
        // weather and time
        "moon", "cloud", "clock", "calendar", "rain", "direction",
        // home and travel
        "washing-machine", "trash", "door", "lock", "bike", "bus", "train", "user", "wifi", "bell",
        // generic
        "info", "warning", "check", "arrow-up", "arrow-down", "arrow-left", "arrow-right",
        // media and controls
        "music", "play", "pause", "skip-next", "skip-previous", "volume-up", "volume-down", "volume-off", "power",
    )
    private val set = all.toHashSet()
    fun isKnown(name: String) = name in set
}

/** Every image [ImageView.src] in this view, for the host to fetch. */
fun PluginView.imageSources(): List<String> = when (this) {
    is ImageView -> listOf(src)
    is StackView -> items.flatMap { it.imageSources() }
    else -> emptyList()
}

fun PluginView.hasActions(): Boolean = when (this) {
    is ActionsView -> true
    is StackView -> items.any { it.hasActions() }
    else -> false
}

/** Whether drawing this view needs the animation clock (moving flow arrows). */
fun PluginView.animates(): Boolean = when (this) {
    is FlowView -> edges.any { it.power > 0f }
    is StackView -> items.any { it.animates() }
    else -> false
}
