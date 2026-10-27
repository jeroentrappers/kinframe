package app.kinframe.core.plugin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.kinframe.core.design.Kin
import app.kinframe.core.plugin.ActionsView
import app.kinframe.core.plugin.ImageShape
import app.kinframe.core.plugin.ImageView
import app.kinframe.core.plugin.ListRow
import app.kinframe.core.plugin.ListView
import app.kinframe.core.plugin.SparklineStyle
import app.kinframe.core.plugin.NoneView
import app.kinframe.core.plugin.ProgressStyle
import app.kinframe.core.plugin.ProgressView
import app.kinframe.core.plugin.SubValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.PluginIcons
import app.kinframe.core.plugin.FlowView
import app.kinframe.core.plugin.GaugeStyle
import app.kinframe.core.plugin.GaugeView
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.RowsView
import app.kinframe.core.plugin.SparklineView
import app.kinframe.core.plugin.StackDirection
import app.kinframe.core.plugin.StackView
import app.kinframe.core.plugin.TextView
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.Trend
import app.kinframe.core.plugin.ValueView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws an API 1 [PluginView] natively. Cheap by construction: no per-frame allocations, value changes and gauge
 * sweeps animate in the draw phase (graphicsLayer / draw lambdas), and flow arrows read the shared
 * [LocalPluginClock] in their draw lambda only.
 */
@Composable
fun PluginViewContent(view: PluginView, form: ViewForm, palette: PluginPalette, modifier: Modifier = Modifier) {
    val m = metricsFor(form)
    Box(modifier) { Component(view, form, palette, m, Tone.Neutral) }
}

@Composable
private fun Component(view: PluginView, form: ViewForm, p: PluginPalette, m: FormMetrics, inherited: Tone) {
    val tone = if (view.tone == Tone.Neutral) inherited else view.tone
    when (view) {
        is ValueView -> Value(view, form, p, m, tone)
        is RowsView -> Rows(view, form, p, m, tone)
        is GaugeView -> Gauge(view, form, p, m, tone)
        is FlowView -> Flow(view, form, p, m, tone)
        is SparklineView -> Sparkline(view, form, p, m, tone)
        is TextView -> Lines(view, form, p, m, tone)
        is StackView -> Stack(view, form, p, m, tone)
        is ImageView -> PImage(view, form, p)
        is ActionsView -> Actions(view, form, p, tone)
        is ProgressView -> Progress(view, form, p, m, tone)
        is ListView -> PList(view, form, p, m, tone)
        NoneView -> Unit
    }
}

// ------------------------------------------------------------------------------------------------ helpers

private fun TextStyle.on(color: Color, p: PluginPalette) = copy(color = color, shadow = p.shadow)

@Composable
private fun PIcon(name: String?, size: Dp, tint: Color, modifier: Modifier = Modifier, rotation: Float? = null) {
    val icon = PluginIcons.of(name) ?: return
    Icon(icon, null, (if (rotation != null && rotation != 0f) modifier.graphicsLayer { rotationZ = rotation } else modifier).size(size), tint = tint)
}

/**
 * A text that rolls to its new value: the old one slides up and fades while the new one rises in. Both offsets are
 * read in graphicsLayer, so the 350 ms animation costs layer updates, not recompositions.
 */
/**
 * "21,4" with the decimal digits small and raised, their tops level with the whole digits (a price tag's "12,⁹⁹",
 * after the HomeWizard Energy Display): the whole number reads from across the room and the decimals don't widen it.
 * The separator stays full size on the baseline, so "3,8" never reads as 3⁸. Only styling: the text (and what
 * accessibility services read) stays "21,4". Plain text when there are no decimals to raise.
 */
internal fun raised(text: String): AnnotatedString {
    val (whole, rest) = PluginFormat.raisedDecimals(text) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(whole)
        withStyle(SpanStyle(fontSize = RAISED_SIZE, baselineShift = BaselineShift(RAISED_SHIFT))) { append(rest) }
    }
}

/**
 * The decimals' size, in em of the number, and their lift in em of the number: digit tops (cap height ≈ 0.71 em in
 * Roboto) meet when lift = 0.71 × (1 − size).
 */
private val RAISED_SIZE = androidx.compose.ui.unit.TextUnit(0.62f, androidx.compose.ui.unit.TextUnitType.Em)
private const val RAISED_SHIFT = 0.27f

@Composable
internal fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null, raise: Boolean = false) {
    fun show(t: String) = if (raise) raised(t) else AnnotatedString(t)
    var shown by remember { mutableStateOf(text) }
    var previous by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        previous = shown
        shown = text
        progress.snapTo(0f)
        progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        previous = null
    }
    Box(modifier) {
        previous?.let { old ->
            Text(
                show(old), style = style, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align, overflow = TextOverflow.Clip,
                modifier = Modifier.graphicsLayer {
                    val v = progress.value
                    alpha = 1f - v
                    translationY = -size.height * 0.45f * v
                },
            )
        }
        Text(
            show(shown), style = style, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer {
                if (previous != null) {
                    val v = progress.value
                    alpha = v
                    translationY = size.height * 0.45f * (1f - v)
                }
            },
        )
    }
}

@Composable
private fun TrendMark(trend: Trend?, size: Dp, color: Color) {
    val icon = when (trend) {
        Trend.Up -> KinIcons.ArrowUp
        Trend.Down -> KinIcons.ArrowDown
        Trend.Flat -> KinIcons.ArrowRight
        null -> return
    }
    Icon(icon, null, Modifier.size(size), tint = color)
}

// ------------------------------------------------------------------------------------------------ value

@Composable
private fun Value(v: ValueView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val (number, unit) = if (v.unit != null) v.value to v.unit else PluginFormat.splitUnit(v.value)
    val strong = p.tone(tone)
    val iconTint = if (tone == Tone.Neutral) p.soft else strong
    when (form) {
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PIcon(v.icon, m.icon, iconTint, rotation = v.iconRotation)
            RollingText(number, m.value.on(strong, p), raise = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                unit?.let { Text(it, style = m.unit.on(p.soft, p), maxLines = 1) }
                TrendMark(v.trend, 14.dp, p.soft)
            }
        }
        ViewForm.Strip -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            PIcon(v.icon, m.icon, iconTint, rotation = v.iconRotation)
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingText(number, m.value.on(strong, p), raise = true)
                    unit?.let { Text(it, style = m.unit.on(p.soft, p), maxLines = 1, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)) }
                    Box(Modifier.padding(start = 2.dp, bottom = 4.dp)) { TrendMark(v.trend, 14.dp, p.soft) }
                }
                v.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            PIcon(v.icon, m.icon, iconTint, rotation = v.iconRotation)
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingText(number, m.value.on(strong, p), raise = true)
                    unit?.let { Text(it, style = m.unit.on(p.soft, p), maxLines = 1, modifier = Modifier.padding(start = 5.dp, bottom = 6.dp)) }
                    Box(Modifier.padding(start = 6.dp, bottom = 9.dp)) { TrendMark(v.trend, 20.dp, p.soft) }
                }
                v.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ rows

@Composable
private fun Rows(r: RowsView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    when (form) {
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.items.forEach { Component(it, form, p, m, tone) }
        }
        // The strip is one short line: values side by side with their icons, labels left out (as in the column), so
        // a table of several values still fits next to the other widgets.
        ViewForm.Strip -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            r.items.forEach { Component(it.copy(label = null), form, p, m, tone) }
        }
        else -> {
            // A small table: icon and label at the start, value and unit at the end.
            val rowValue = m.value.copy(fontSize = m.value.fontSize * 0.62f, lineHeight = m.value.lineHeight * 0.62f, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.items.forEach { v ->
                    val t = if (v.tone == Tone.Neutral) tone else v.tone
                    val (number, unit) = if (v.unit != null) v.value to v.unit else PluginFormat.splitUnit(v.value)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PIcon(v.icon, m.icon * 0.7f, if (t == Tone.Neutral) p.soft else p.tone(t), rotation = v.iconRotation)
                        v.label?.let {
                            Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).widthIn(max = 220.dp))
                        }
                        Spacer(Modifier.weight(1f).widthIn(min = 20.dp))
                        RollingText(number, rowValue.on(p.tone(t), p), raise = true)
                        unit?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, modifier = Modifier.padding(start = 4.dp)) }
                        Box(Modifier.padding(start = 4.dp)) { TrendMark(v.trend, 16.dp, p.soft) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ gauge

@Composable
private fun Gauge(g: GaugeView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val color = if (tone == Tone.Neutral) p.text else p.tone(tone)
    val target = g.percent.coerceIn(0f, 100f) / 100f
    val sweep by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "gauge")
    val label = g.label ?: "${g.percent.roundToInt()}%"
    if (g.style == GaugeStyle.Bar) {
        val barW = when (form) { ViewForm.Column -> 60.dp; ViewForm.Strip -> 110.dp; ViewForm.Detail -> 320.dp; else -> 200.dp }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (form == ViewForm.Column) Alignment.CenterHorizontally else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PIcon(g.icon, m.icon * 0.75f, color)
                Text(label, style = m.nodeValue.on(p.text, p), maxLines = 1)
                if (!form.compact) g.caption?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val track = p.faint
            val stroke = m.gaugeStroke
            Canvas(Modifier.size(barW, stroke)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(color, size = Size(size.width * sweep, size.height), cornerRadius = r)
            }
            if (form == ViewForm.Strip) g.caption?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    val ring = @Composable {
        val track = p.faint
        if (g.style == GaugeStyle.Dial) {
            Dial(g, form, p, m, tone)
        } else Box(Modifier.size(m.gauge).drawBehind {
            val sw = m.gaugeStroke.toPx()
            val inset = sw / 2
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            if (sweep > 0f) drawArc(color, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!form.compact) PIcon(g.icon, m.icon * 0.55f, p.soft)
                Text(label, style = m.nodeValue.on(p.text, p), maxLines = 1)
            }
        }
    }
    val ringAndSubs = @Composable {
        if (g.sub.isEmpty() || form == ViewForm.Strip) ring()
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ring()
            SubValues(g.sub, form, p, m, tone)
        }
    }
    when (form) {
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ringAndSubs()
            g.caption?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap + 4.dp)) {
            ringAndSubs()
            if (g.caption != null || (form.compact && g.icon != null)) {
                Column {
                    if (form.compact) PIcon(g.icon, m.icon * 0.8f, p.soft)
                    g.caption?.let { Text(it, style = (if (form.compact) m.label else m.line1).on(p.text, p), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (form.compact) 140.dp else 260.dp)) }
                }
            }
        }
    }
}

/** Small values under a gauge: an arrow (or other icon) in the value's tone, the number in text colour. */
@Composable
private fun SubValues(sub: List<SubValue>, form: ViewForm, p: PluginPalette, m: FormMetrics, inherited: Tone) {
    val style = m.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium).on(p.text, p)
    val item = @Composable { s: SubValue ->
        val t = if (s.tone == Tone.Neutral) inherited else s.tone
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PIcon(s.icon, m.icon * 0.45f, if (t == Tone.Neutral) p.soft else p.tone(t))
            Text(s.value, style = style, maxLines = 1)
        }
    }
    if (form == ViewForm.Column) Column(horizontalAlignment = Alignment.CenterHorizontally) { sub.forEach { item(it) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { sub.forEach { item(it) } }
}

/**
 * The two-sided gauge: an open 240° ring with a tick at the top for zero. Positive values sweep clockwise in the
 * gauge's tone, negative ones anticlockwise in its negative tone; the label's trailing unit is drawn small below.
 */
@Composable
private fun Dial(g: GaugeView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val target = g.percent.coerceIn(-100f, 100f) / 100f
    val sweep by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "dial")
    // The colour follows the arc as it moves, not the new value: from using to returning, the purple arc shrinks back
    // to zero and only then grows green on the other side.
    val positive = if (tone == Tone.Neutral) p.text else p.tone(tone)
    val negative = p.tone(g.negativeTone ?: tone)
    val track = p.faint
    val tick = p.soft
    val (number, unit) = PluginFormat.splitUnit(g.label ?: "${g.percent.roundToInt()}%")
    Box(Modifier.size(m.gauge * 1.2f).drawBehind {
        val sw = m.gaugeStroke.toPx()
        val inset = sw / 2
        val arcSize = Size(size.width - sw, size.height - sw)
        drawArc(track, -90f - SPAN, 2 * SPAN, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        val a = abs(sweep) * SPAN
        if (a > 0.5f) drawArc(if (sweep < 0f) negative else positive, if (sweep < 0f) -90f - a else -90f, a, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(tick, Offset(size.width / 2, 0f), Offset(size.width / 2, sw * 1.4f), strokeWidth = sw * 0.3f)
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = m.gaugeStroke)) {
            if (form == ViewForm.Detail) PIcon(g.icon, m.icon * 0.5f, p.soft)
            Text(raised(number), style = m.nodeValue.on(p.text, p), maxLines = 1)
            unit?.let { Text(it, style = m.label.copy(fontSize = m.label.fontSize * 0.85f, lineHeight = m.label.fontSize).on(p.soft, p), maxLines = 1) }
        }
    }
}

private const val SPAN = 120f

// ------------------------------------------------------------------------------------------------ sparkline

@Composable
private fun Sparkline(s: SparklineView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val color = if (tone == Tone.Neutral) p.text else p.tone(tone)
    val faint = p.faint
    val markColor = p.soft
    val hiColor = s.highlightTone?.let { if (it == Tone.Neutral) p.text else p.tone(it) } ?: color
    // With a highlight or a "now" marker the other bars step back, so the two picked-out points read at a glance; on a
    // photo (pastel tones, a bright sky behind) only a little, or they'd vanish.
    val quiet = if (s.highlight != null || s.marker != null) color.copy(alpha = if (p.shadow != null) 0.75f else 0.42f) else color
    val chart = @Composable {
        Box(Modifier.size(m.sparkW, m.sparkH).drawWithCache {
            val pts = s.points
            var lo = s.min ?: Float.POSITIVE_INFINITY
            var hi = s.max ?: Float.NEGATIVE_INFINITY
            if (s.min == null || s.max == null) for (v in pts) if (!v.isNaN()) {
                if (s.min == null) lo = min(lo, v)
                if (s.max == null) hi = max(hi, v)
            }
            val bars = s.style == SparklineStyle.Bars
            // Bars stand on zero when the range includes it (negative prices hang below it), else on the bottom edge.
            if (bars && s.min == null) lo = min(lo, 0f)
            if (hi - lo < 1e-6f) { hi += 1f; lo -= 1f }
            val sw = (if (form.compact) 2.dp else 2.5.dp).toPx()
            val pad = sw
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val n = pts.size
            fun y(v: Float) = pad + h * (1f - ((v.coerceIn(lo, hi) - lo) / (hi - lo)))
            fun x(i: Int) = if (bars) pad + w * (i + 0.5f) / n else pad + w * i / (n - 1).coerceAtLeast(1)
            val path = Path()
            var lastX = 0f
            var lastY = 0f
            if (!bars) {
                var pen = false
                for (i in 0 until n) {
                    val v = pts[i]
                    if (v.isNaN()) { pen = false; continue }
                    if (pen) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                    pen = true
                    lastX = x(i); lastY = y(v)
                }
            }
            // Thin bars at the column's size (100 in 64 dp) touch and read as an area; picked-out ones stay visible.
            val slot = w / n
            val barW = max(slot * 0.72f, 1f)
            val pickW = max(barW, 2.dp.toPx())
            val zero = y(0f.coerceIn(lo, hi))
            val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                // The base line: zero for bars (negative prices hang below it), the bottom edge for a line.
                val base = if (bars) zero else size.height - pad
                drawLine(faint, Offset(pad, base), Offset(size.width - pad, base), strokeWidth = 1.dp.toPx())
                s.marker?.let { i ->
                    // "Now": a thin line across the chart, behind the data.
                    drawLine(markColor, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
                }
                if (bars) {
                    fun bar(i: Int, c: Color, bw: Float) {
                        val v = pts[i]
                        if (v.isNaN()) return
                        val top = min(y(v), zero)
                        val bottom = max(y(v), zero)
                        drawRect(c, Offset(x(i) - bw / 2, top), Size(bw, max(bottom - top, 1f)))
                    }
                    for (i in 0 until n) if (i != s.highlight && i != s.marker) bar(i, quiet, barW)
                    s.marker?.let { bar(it, color, pickW) }
                    s.highlight?.let { i ->
                        bar(i, hiColor, pickW)
                        // A dot at the bar's tip, so a highlight near zero (the cheapest quarter hour) still shows.
                        val v = pts[i]
                        drawCircle(hiColor, pickW * 1.1f, Offset(x(i), if (y(v) <= zero) y(v) - pickW * 1.1f else y(v) + pickW * 1.1f))
                    }
                } else {
                    drawPath(path, if (s.highlight != null || s.marker != null) quiet else color, style = stroke)
                    if (s.marker == null) drawCircle(color, sw * 1.6f, Offset(lastX, lastY))
                    s.marker?.let { i -> drawCircle(color, sw * 1.8f, Offset(x(i), y(pts[i]))) }
                    s.highlight?.let { i -> drawCircle(hiColor, sw * 1.8f, Offset(x(i), y(pts[i]))) }
                }
            }
        })
    }
    when (form) {
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            s.value?.let { RollingText(it, m.nodeValue.on(p.text, p)) }
            chart()
        }
        ViewForm.Strip -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            chart()
            Column {
                s.value?.let { RollingText(it, m.nodeValue.on(p.text, p)) }
                s.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.label != null || s.value != null) {
                Row(Modifier.width(m.sparkW), verticalAlignment = Alignment.Bottom) {
                    s.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                        ?: Spacer(Modifier.weight(1f))
                    s.value?.let { RollingText(it, m.nodeValue.on(p.text, p)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                chart()
                if (s.showRange) {
                    val (lo, hi) = range(s)
                    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(hi, style = m.label.on(p.soft, p), maxLines = 1)
                        Spacer(Modifier.size(m.sparkH - 40.dp))
                        Text(lo, style = m.label.on(p.soft, p), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun range(s: SparklineView): Pair<String, String> {
    val vals = s.points.filter { !it.isNaN() }
    val lo = s.min ?: vals.minOrNull() ?: 0f
    val hi = s.max ?: vals.maxOrNull() ?: 0f
    fun f(v: Float) = if (kotlin.math.abs(v) >= 100 || v == v.roundToInt().toFloat()) v.roundToInt().toString() else ((v * 10).roundToInt() / 10f).toString()
    return f(lo) to f(hi)
}

// ------------------------------------------------------------------------------------------------ list

@Composable
private fun PList(l: ListView, form: ViewForm, p: PluginPalette, m: FormMetrics, inherited: Tone) {
    fun toneOf(r: ListRow) = if (r.tone == Tone.Neutral) inherited else r.tone
    fun strongOf(r: ListRow) = toneOf(r).let { if (it == Tone.Neutral) p.text else p.tone(it) }
    val medium = androidx.compose.ui.text.font.FontWeight.Medium
    when (form) {
        // Too narrow for a title: the first row's icon once, then the times with short badges ("+3"); a longer badge
        // ("Cancelled") doesn't fit and colours the time instead. A list without times shows its first title.
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PIcon(l.rows.first().icon, m.icon, if (toneOf(l.rows.first()) == Tone.Neutral) p.soft else strongOf(l.rows.first()))
            val timed = l.rows.filter { it.time != null }.take(3)
            if (timed.isEmpty()) {
                Text(l.rows.first().title, style = m.label.on(p.text, p), maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            } else timed.forEach { r ->
                val badge = r.badge?.takeIf { it.length <= COLUMN_BADGE }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.time!!, style = m.nodeValue.on(if (r.badge != null && badge == null) strongOf(r) else p.text, p), maxLines = 1)
                    badge?.let { Text(it, style = m.label.copy(fontWeight = medium).on(strongOf(r), p), maxLines = 1) }
                }
            }
        }
        // One short line per row, the first two rows.
        ViewForm.Strip -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            l.rows.take(2).forEach { r ->
                Row(Modifier.widthIn(max = 300.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PIcon(r.icon, 18.dp, if (toneOf(r) == Tone.Neutral) p.soft else strongOf(r))
                    r.time?.let { Text(it, style = m.label.copy(fontWeight = medium).on(p.text, p), maxLines = 1) }
                    Text(r.title, style = m.label.on(p.text, p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    r.badge?.let { Text(it, style = m.label.copy(fontWeight = medium).on(strongOf(r), p), maxLines = 1) }
                }
            }
        }
        // A small timetable: the times in a column as wide as the widest one ("22:23" or "Tomorrow"), so the titles
        // line up; badges at the end.
        else -> {
            val detail = form == ViewForm.Detail
            val rows = if (detail) l.rows else l.rows.take(3)
            val timeStyle = m.line1.copy(fontWeight = medium).on(p.text, p)
            androidx.compose.ui.layout.SubcomposeLayout { constraints ->
                val timeW = subcompose("times") { rows.forEach { r -> r.time?.let { Text(it, style = timeStyle, maxLines = 1) } } }
                    .maxOfOrNull { it.measure(Constraints()).width } ?: 0
                val table = subcompose("table") {
                    Column(Modifier.width(if (detail) 460.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(if (detail) 10.dp else 6.dp)) {
                        l.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        rows.forEach { r ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PIcon(r.icon, m.icon * 0.6f, if (toneOf(r) == Tone.Neutral) p.soft else strongOf(r), Modifier.padding(end = 8.dp))
                                if (timeW > 0) Text(r.time ?: "", style = timeStyle, maxLines = 1, modifier = Modifier.width(timeW.toDp() + 10.dp))
                                // Title and detail share what's left; the title gives way first, the badge keeps the end.
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.title, style = m.line1.on(p.text, p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    r.detail?.let { Text(it, style = m.line2.on(p.soft, p), maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                                }
                                r.badge?.let { Text(it, style = m.line1.copy(fontWeight = medium).on(strongOf(r), p), maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                            }
                        }
                    }
                }.first().measure(constraints)
                layout(table.width, table.height) { table.place(0, 0) }
            }
        }
    }
}

/** The longest badge the band column draws beside a time ("+12"); longer ones colour the time instead. */
private const val COLUMN_BADGE = 4

// ------------------------------------------------------------------------------------------------ text

@Composable
private fun Lines(t: TextView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val strong = if (tone == Tone.Neutral) p.text else p.tone(tone)
    when (form) {
        ViewForm.Column -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PIcon(t.icon, m.icon, strong)
            t.lines.forEachIndexed { i, line ->
                Text(line, style = (if (i == 0) m.line1 else m.line2).on(if (i == 0) strong else p.soft, p), maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            PIcon(t.icon, if (form == ViewForm.Strip) m.icon else m.icon * 0.8f, strong)
            Column(Modifier.widthIn(max = if (form == ViewForm.Strip) 260.dp else 420.dp)) {
                t.lines.forEachIndexed { i, line ->
                    RollingText(line, (if (i == 0) m.line1 else m.line2).on(if (i == 0) strong else p.soft, p), maxLines = if (form == ViewForm.Strip) 1 else 2)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ image

@Composable
private fun PImage(v: ImageView, form: ViewForm, p: PluginPalette) {
    val size = when (form) { ViewForm.Detail -> 168.dp; ViewForm.Corner -> 76.dp; else -> 56.dp }
    val shape = if (v.shape == ImageShape.Round) CircleShape else RoundedCornerShape(if (form == ViewForm.Detail) 12.dp else 8.dp)
    val bitmap = LocalPluginImages.current(v.src)
    val described = Modifier.size(size).clip(shape).semantics { v.description?.let { contentDescription = it } }
    if (bitmap != null) {
        Image(bitmap, null, described, contentScale = ContentScale.Crop)
    } else {
        // Loading or failed: a quiet tile, so the layout doesn't jump when the picture arrives.
        Box(described.background(p.faint))
    }
}

// ------------------------------------------------------------------------------------------------ actions

@Composable
private fun Actions(a: ActionsView, form: ViewForm, p: PluginPalette, tone: Tone) {
    // Buttons only in the detail card: on the photo or in the band a tap opens the card instead.
    if (form != ViewForm.Detail) return
    val run = LocalPluginActions.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        a.items.forEach { item ->
            val t = if (item.tone == Tone.Neutral) tone else item.tone
            val c = if (t == Tone.Neutral) p.text else p.tone(t)
            val size = if (item.primary) 64.dp else 52.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(size).clip(CircleShape)
                        .background(if (item.primary) c else p.faint.copy(alpha = 0.6f))
                        .then(if (run != null) Modifier.clickable(onClickLabel = item.label ?: item.id) { run(item.id) } else Modifier.graphicsLayer { alpha = 0.4f })
                        .semantics { contentDescription = item.label ?: item.id; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { PIcon(item.icon, if (item.primary) 30.dp else 24.dp, if (item.primary) p.onTone else c) }
                item.label?.let { Text(it, style = Kin.type.caption.on(p.soft, p), maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ progress

@Composable
private fun Progress(v: ProgressView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val color = if (tone == Tone.Neutral) p.text else p.tone(tone)
    // A running bar moves on by itself, one unit per second since `at`; ticking once a second only while it runs.
    val now by produceState(System.currentTimeMillis(), v) {
        while (v.running) { value = System.currentTimeMillis(); delay(1_000) }
    }
    val current = if (v.running && v.at != null) (v.value + (now - v.at) / 1000f).coerceIn(v.min, v.max) else v.value
    val fraction = ((current - v.min) / (v.max - v.min)).coerceIn(0f, 1f)
    val width = when (form) { ViewForm.Column -> 60.dp; ViewForm.Strip -> 120.dp; ViewForm.Detail -> 360.dp; else -> 200.dp }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = if (form == ViewForm.Column) Alignment.CenterHorizontally else Alignment.Start) {
        if (form != ViewForm.Column) v.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = width)) }
        val track = p.faint
        if (v.style == ProgressStyle.Marker) {
            // A dot on the track: where the value sits between min and max (today's temperature in its range).
            Canvas(Modifier.size(width, 10.dp)) {
                val h = 3.dp.toPx()
                drawRoundRect(track, Offset(0f, (size.height - h) / 2), Size(size.width, h), CornerRadius(h / 2))
                val r = size.height / 2
                drawCircle(color, r, Offset(r + (size.width - 2 * r) * fraction, size.height / 2))
            }
        } else Canvas(Modifier.size(width, if (form.compact) 3.dp else 4.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = r)
        }
        if (v.time && form != ViewForm.Column) {
            Row(Modifier.width(width), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockText(current), style = m.label.on(p.soft, p), maxLines = 1)
                Text(clockText(v.max), style = m.label.on(p.soft, p), maxLines = 1)
            }
        }
    }
}

/** Seconds as "3:07" or "1:02:07". */
internal fun clockText(seconds: Float): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%d:%02d".format(mm, ss)
}

// ------------------------------------------------------------------------------------------------ stack

@Composable
private fun Stack(s: StackView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    // The band decides the axis: a narrow column stacks vertically, a short strip horizontally.
    val vertical = when (form) {
        ViewForm.Column -> true
        ViewForm.Strip -> false
        else -> s.direction == StackDirection.Vertical
    }
    val gap = if (form.compact) 10.dp else 16.dp
    if (vertical) {
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = if (form == ViewForm.Column) Alignment.CenterHorizontally else Alignment.Start,
        ) { s.items.forEach { Component(it, form, p, m, tone) } }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap + 6.dp)) {
            s.items.forEach { Component(it, form, p, m, tone) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ flow

/** Node centres and edge polylines, written during layout and read by the draw lambda (no allocations per frame). */
private class FlowGeometry {
    val segs = FloatArray(MAX_EDGES * 3 * 4)
    val segCount = IntArray(MAX_EDGES)
    val length = FloatArray(MAX_EDGES)
    var edges = 0

    companion object { const val MAX_EDGES = 8 }
}

@Composable
private fun Flow(f: FlowView, form: ViewForm, p: PluginPalette, m: FormMetrics, tone: Tone) {
    val geo = remember { FlowGeometry() }
    val clock = LocalPluginClock.current
    val maxPower = remember(f) { f.edges.maxOfOrNull { it.power }?.takeIf { it > 0f } ?: 1f }
    val hub = remember(f) { hubOf(f) }
    val line = p.faint
    val arrowColor = if (tone == Tone.Neutral) p.text else p.tone(tone)
    val circle = m.nodeCircle
    Layout(
        content = {
            f.nodes.forEach { n ->
                val nt = if (n.tone == Tone.Neutral) tone else n.tone
                val c = if (nt == Tone.Neutral) p.text else p.tone(nt)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(circle).drawBehind {
                            val sw = 2.dp.toPx()
                            val pct = n.percent
                            if (pct == null) {
                                drawCircle(c, size.minDimension / 2 - sw / 2, style = Stroke(sw))
                            } else {
                                // A charge ring: the faint track and the filled share, from the top, clockwise.
                                val rw = 3.dp.toPx()
                                drawCircle(line, size.minDimension / 2 - rw / 2, style = Stroke(rw))
                                drawArc(
                                    c, -90f, 3.6f * pct.coerceIn(0f, 100f), false, Offset(rw / 2, rw / 2),
                                    Size(size.width - rw, size.height - rw), style = Stroke(rw, cap = StrokeCap.Round),
                                )
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { PIcon(n.icon, m.nodeIcon, c) }
                    n.value?.let { RollingText(it, m.nodeValue.on(p.text, p), Modifier.padding(top = 3.dp)) }
                    if (form == ViewForm.Detail || form == ViewForm.Corner) n.label?.let { Text(it, style = m.label.on(p.soft, p), maxLines = 1) }
                }
            }
        },
        modifier = Modifier.drawBehind {
            val t = clock?.floatValue ?: 0f
            val spacing = 15.dp.toPx()
            val half = 3.5.dp.toPx()
            val base = 1.5.dp.toPx()
            val strong = 2.2.dp.toPx()
            for (e in 0 until geo.edges) {
                val off = e * 12
                for (s in 0 until geo.segCount[e]) {
                    val i = off + s * 4
                    drawLine(line, Offset(geo.segs[i], geo.segs[i + 1]), Offset(geo.segs[i + 2], geo.segs[i + 3]), strokeWidth = base, cap = StrokeCap.Round)
                }
                val power = f.edges[e].power
                if (power <= 0f) continue
                val len = geo.length[e]
                // 10–60 dp/s, by the square root of the share of the largest flow: small flows still visibly move.
                val speed = (10f + 50f * sqrt(power / maxPower)).dp.toPx()
                var d = (t * speed) % spacing + half
                while (d < len - half) {
                    // Walk the polyline to distance d.
                    var rest = d
                    for (s in 0 until geo.segCount[e]) {
                        val i = off + s * 4
                        val x0 = geo.segs[i]; val y0 = geo.segs[i + 1]; val x1 = geo.segs[i + 2]; val y1 = geo.segs[i + 3]
                        val dx = x1 - x0; val dy = y1 - y0
                        val sl = sqrt(dx * dx + dy * dy)
                        if (rest <= sl || s == geo.segCount[e] - 1) {
                            val ux = if (sl > 0f) dx / sl else 0f
                            val uy = if (sl > 0f) dy / sl else 0f
                            val px = x0 + ux * rest; val py = y0 + uy * rest
                            // Fade in at the start and out at the end of the edge.
                            val a = min(1f, min(d, len - d) / (spacing * 0.9f))
                            val c = arrowColor.copy(alpha = arrowColor.alpha * a)
                            val tipX = px + ux * half; val tipY = py + uy * half
                            drawLine(c, Offset(px - ux * half - uy * half, py - uy * half + ux * half), Offset(tipX, tipY), strokeWidth = strong, cap = StrokeCap.Round)
                            drawLine(c, Offset(px - ux * half + uy * half, py - uy * half - ux * half), Offset(tipX, tipY), strokeWidth = strong, cap = StrokeCap.Round)
                            break
                        }
                        rest -= sl
                    }
                    d += spacing
                }
            }
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose.copy(minWidth = 0, minHeight = 0)) }
        val n = placeables.size
        val cellW = placeables.maxOf { it.width }
        val cellH = placeables.maxOf { it.height }
        val r = circle.toPx() / 2
        val xs = IntArray(n)
        val ys = IntArray(n)
        val w: Int
        val h: Int
        // Room on the side (column) or above (strip) for an arrow that has to go around a node.
        val detourPad = if (n >= 4 && form == ViewForm.Strip) 12.dp.roundToPx() else 0
        when (form) {
            ViewForm.Column, ViewForm.Strip -> {
                // A line of nodes with the hub in the middle and the others alternating around it, so most edges join
                // neighbours; an edge to a node further away goes around the one in between.
                val order = sequenceOrder(n, hub)
                val gap = (if (form == ViewForm.Column) 24.dp else 34.dp).roundToPx()
                if (form == ViewForm.Column) {
                    var y = 0
                    order.forEach { i -> xs[i] = detourPad + (cellW - placeables[i].width) / 2; ys[i] = y; y += placeables[i].height + gap }
                    w = cellW + 2 * detourPad; h = y - gap
                } else {
                    var x = 0
                    order.forEach { i -> xs[i] = x; ys[i] = detourPad; x += placeables[i].width + gap }
                    w = x - gap; h = cellH + detourPad
                }
            }
            else -> {
                val gapX = (if (form == ViewForm.Detail) 64.dp else 52.dp).roundToPx()
                val gapY = 40.dp.roundToPx()
                if (n == 2) {
                    xs[0] = 0; ys[0] = 0
                    xs[1] = placeables[0].width + gapX; ys[1] = 0
                    w = xs[1] + placeables[1].width; h = cellH
                } else {
                    // Hub in the middle; the others start, end, above and below it.
                    val others = (0 until n).filter { it != hub }
                    val hasTop = others.size >= 3
                    val hasBottom = others.size >= 4
                    val row = if (hasTop) cellH + gapY else 0
                    fun put(i: Int, col: Int, rowY: Int) { xs[i] = col * (cellW + gapX) + (cellW - placeables[i].width) / 2; ys[i] = rowY }
                    put(hub, 1, row)
                    put(others[0], 0, row)
                    put(others[1], 2, row)
                    if (hasTop) put(others[2], 1, 0)
                    if (hasBottom) put(others[3], 1, row + cellH + gapY)
                    w = 3 * cellW + 2 * gapX
                    h = row + cellH + (if (hasBottom) gapY + cellH else 0)
                }
            }
        }
        if (layoutDirection == LayoutDirection.Rtl) for (i in 0 until n) xs[i] = w - xs[i] - placeables[i].width
        // Edges run between circle centres and start and end where they leave a node's box (circle, value and label),
        // so arrows never cross a node's text.
        val inflate = 2.dp.toPx()
        fun exit(i: Int, cx: Float, cy: Float, ux: Float, uy: Float): Float {
            val l = xs[i] - inflate; val t = ys[i] - inflate
            val rr = xs[i] + placeables[i].width + inflate; val b = ys[i] + placeables[i].height + inflate
            val tx = if (ux > 1e-4f) (rr - cx) / ux else if (ux < -1e-4f) (l - cx) / ux else Float.MAX_VALUE
            val ty = if (uy > 1e-4f) (b - cy) / uy else if (uy < -1e-4f) (t - cy) / uy else Float.MAX_VALUE
            return min(tx, ty).coerceAtLeast(0f)
        }
        geo.edges = min(f.edges.size, FlowGeometry.MAX_EDGES)
        for (e in 0 until geo.edges) {
            val ed = f.edges[e]
            val ax = xs[ed.from] + placeables[ed.from].width / 2f
            val ay = ys[ed.from] + r
            val bx = xs[ed.to] + placeables[ed.to].width / 2f
            val by = ys[ed.to] + r
            val blocked = (0 until n).any { k ->
                k != ed.from && k != ed.to && distToSegment(xs[k] + placeables[k].width / 2f, ys[k] + r, ax, ay, bx, by) < r
            }
            val off = e * 12
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            if (!blocked) {
                val ux = dx / len; val uy = dy / len
                val ta = exit(ed.from, ax, ay, ux, uy)
                val tb = exit(ed.to, bx, by, -ux, -uy)
                geo.segs[off] = ax + ux * ta; geo.segs[off + 1] = ay + uy * ta
                geo.segs[off + 2] = bx - ux * tb; geo.segs[off + 3] = by - uy * tb
                geo.segCount[e] = 1
                geo.length[e] = (len - ta - tb).coerceAtLeast(0f)
            } else if (form.compact) {
                // No room to go around in a band: a short arrow beside the far node shows the direction instead.
                val seq = sequenceOrder(n, hub)
                val far = if (kotlin.math.abs(seq.indexOf(ed.from) - seq.indexOf(hub)) >= kotlin.math.abs(seq.indexOf(ed.to) - seq.indexOf(hub))) ed.from else ed.to
                val fx = xs[far] + placeables[far].width / 2f
                val fy = ys[far] + r
                val gapSide = 5.dp.toPx()
                if (form == ViewForm.Column) {
                    val dir = if (by >= ay) 1f else -1f
                    val x = fx + r + gapSide
                    geo.segs[off] = x; geo.segs[off + 1] = fy - dir * r
                    geo.segs[off + 2] = x; geo.segs[off + 3] = fy + dir * r
                } else {
                    val dir = if (bx >= ax) 1f else -1f
                    val y = fy - r - gapSide
                    geo.segs[off] = fx - dir * r; geo.segs[off + 1] = y
                    geo.segs[off + 2] = fx + dir * r; geo.segs[off + 3] = y
                }
                geo.segCount[e] = 1
                geo.length[e] = 2 * r
            } else {
                // Around the node in between: out sideways or up, along, and back in.
                val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                val vx: Float; val vy: Float
                when {
                    form == ViewForm.Column -> { vx = 1f; vy = 0f }
                    horizontal -> { vx = 0f; vy = if (form == ViewForm.Strip || ys.min() >= ay - r) -1f else 1f }
                    else -> { vx = 1f; vy = 0f }
                }
                val ea = exit(ed.from, ax, ay, vx, vy)
                val eb = exit(ed.to, bx, by, vx, vy)
                val want = when {
                    form == ViewForm.Column -> cellW / 2f + 6.dp.toPx()
                    form == ViewForm.Strip -> r + 9.dp.toPx()
                    horizontal -> r + 12.dp.toPx()
                    else -> cellW / 2f + 12.dp.toPx()
                }
                val side = maxOf(want, ea + 4.dp.toPx(), eb + 4.dp.toPx())
                val pts = floatArrayOf(ax + vx * ea, ay + vy * ea, ax + vx * side, ay + vy * side, bx + vx * side, by + vy * side, bx + vx * eb, by + vy * eb)
                var total = 0f
                for (sg in 0 until 3) {
                    val i = off + sg * 4
                    geo.segs[i] = pts[sg * 2]; geo.segs[i + 1] = pts[sg * 2 + 1]; geo.segs[i + 2] = pts[sg * 2 + 2]; geo.segs[i + 3] = pts[sg * 2 + 3]
                    val ddx = geo.segs[i + 2] - geo.segs[i]; val ddy = geo.segs[i + 3] - geo.segs[i + 1]
                    total += sqrt(ddx * ddx + ddy * ddy)
                }
                geo.segCount[e] = 3
                geo.length[e] = total
            }
        }
        layout(w, h) { placeables.forEachIndexed { i, pl -> pl.place(xs[i], ys[i]) } }
    }
}

/** The node with the most edges (the house, usually); the first node on a tie. */
private fun hubOf(f: FlowView): Int {
    val deg = IntArray(f.nodes.size)
    f.edges.forEach { deg[it.from]++; deg[it.to]++ }
    var best = 0
    for (i in deg.indices) if (deg[i] > deg[best]) best = i
    return if (f.nodes.size == 2) 0 else best
}

/** [o2, o0, hub, o1, o3]: the hub in the middle, the others alternating before and after it. */
private fun sequenceOrder(n: Int, hub: Int): List<Int> {
    if (n == 2) return listOf(0, 1)
    val others = (0 until n).filter { it != hub }
    val before = others.filterIndexed { i, _ -> i % 2 == 0 }.reversed()
    val after = others.filterIndexed { i, _ -> i % 2 == 1 }
    return before + hub + after
}

private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val l2 = dx * dx + dy * dy
    if (l2 == 0f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
    val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    val cx = ax + t * dx; val cy = ay + t * dy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}
