package app.kinframe.frame.weather

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * kinframe's own weather icons, drawn on a 100 × 100 grid: soft gradients, no outlines, and a faint built-in
 * shadow under clouds so white shapes stay readable on bright photos ([onPaper] gives clouds more body on the
 * light empty-state background). Paths are built once per size
 * ([drawWithCache]); nothing animates, so the Mali-400 only pays for them when the widget changes.
 */
@Composable
fun WeatherIcon(
    glyph: WeatherGlyph,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onPaper: Boolean = false,
) {
    Spacer(
        modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .drawWithCache {
                val s = this.size.minDimension / 100f
                onDrawBehind { scale(s, pivot = Offset.Zero) { drawGlyph(glyph, onPaper) } }
            },
    )
}

private object Palette {
    val sunCore = Color(0xFFFFD25E)
    val sunEdge = Color(0xFFFFA92E)
    val sunHalo = Color(0xFFFFC44D)
    val moonLight = Color(0xFFFBF4E2)
    val moonShade = Color(0xFFE4D6B5)
    val cloudTop = Color(0xFFFFFFFF)
    val cloudBottom = Color(0xFFDCE3EC)
    // On the light paper background clouds need more body to stand out.
    val paperCloudTop = Color(0xFFEFF3F7)
    val paperCloudBottom = Color(0xFFBCC8D6)
    val stormTop = Color(0xFFE6EBF1)
    val stormBottom = Color(0xFFA7B3C2)
    val rain = Color(0xFF5DB0FF)
    val drizzle = Color(0xFF8CC8FF)
    val snow = Color(0xFFF4FAFF)
    val snowEdge = Color(0xFF9CC7EE)
    val boltTop = Color(0xFFFFE071)
    val boltBottom = Color(0xFFFFAE1A)
    val fog = Color(0xFFF2F5F8)
    val shadow = Color(0xFF0B1A2A)
}

private fun DrawScope.drawGlyph(g: WeatherGlyph, paper: Boolean) {
    when (g) {
        WeatherGlyph.Sun -> sun(Offset(50f, 50f), 25f)
        WeatherGlyph.Moon -> moon(Offset(50f, 50f), 28f, stars = true)
        WeatherGlyph.PartlyCloudyDay -> {
            sun(Offset(63f, 36f), 23f)
            cloud(4f, 38f, 76f, paper)
        }
        WeatherGlyph.PartlyCloudyNight -> {
            moon(Offset(64f, 34f), 23f, stars = false)
            cloud(4f, 38f, 76f, paper)
        }
        WeatherGlyph.Cloud -> {
            cloud(30f, 18f, 58f, paper, back = true)
            cloud(8f, 32f, 74f, paper)
        }
        WeatherGlyph.Fog -> {
            cloud(16f, 14f, 68f, paper)
            fogLines(paper)
        }
        WeatherGlyph.Drizzle -> {
            cloud(10f, 12f, 80f, paper)
            drops(listOf(32f to 72f, 52f to 78f, 72f to 72f), length = 6f, width = 5f, color = Palette.drizzle)
        }
        WeatherGlyph.Rain -> {
            cloud(10f, 12f, 80f, paper)
            drops(listOf(32f to 70f, 52f to 76f, 72f to 70f), length = 13f, width = 5.5f, color = Palette.rain)
        }
        WeatherGlyph.HeavyRain -> {
            cloud(10f, 10f, 80f, paper, storm = true)
            drops(listOf(24f to 68f, 40f to 74f, 56f to 68f, 72f to 74f), length = 17f, width = 5.5f, color = Palette.rain)
        }
        WeatherGlyph.Snow -> {
            cloud(10f, 12f, 80f, paper)
            flake(Offset(32f, 78f), 7f)
            flake(Offset(52f, 86f), 7f)
            flake(Offset(72f, 78f), 7f)
        }
        WeatherGlyph.Sleet -> {
            cloud(10f, 12f, 80f, paper)
            drops(listOf(32f to 70f, 72f to 70f), length = 12f, width = 5.5f, color = Palette.rain)
            flake(Offset(52f, 84f), 7f)
        }
        WeatherGlyph.Thunder -> {
            cloud(10f, 10f, 80f, paper, storm = true)
            bolt()
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float) {
    // A soft glow rather than rings: it fades to nothing, so it never shows an edge on dark or light photos.
    drawCircle(
        Brush.radialGradient(0.6f to Palette.sunHalo.copy(alpha = 0.34f), 1f to Palette.sunHalo.copy(alpha = 0f), center = c, radius = r * 1.6f),
        r * 1.6f, c,
    )
    drawCircle(
        Brush.linearGradient(listOf(Palette.sunCore, Palette.sunEdge), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)),
        r, c,
    )
}

private fun DrawScope.moon(c: Offset, r: Float, stars: Boolean) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.42f), r * 0.86f)) }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    drawPath(crescent, Brush.linearGradient(listOf(Palette.moonLight, Palette.moonShade), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r * 0.4f, c.y + r)))
    if (stars) {
        sparkle(Offset(c.x + r * 0.95f, c.y + r * 0.35f), 5.5f)
        sparkle(Offset(c.x + r * 0.55f, c.y - r * 1.05f), 3.8f)
    }
}

private fun DrawScope.sparkle(c: Offset, r: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, Palette.moonLight)
}

/** A cloud [w] wide (about 0.6 w tall) with its top-left at ([x], [y]). */
private fun cloudPath(x: Float, y: Float, w: Float): Path {
    fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(x + cx * w, y + cy * w), r * w)) }
    val base = Path().apply {
        addRoundRect(RoundRect(x, y + 0.30f * w, x + w, y + 0.60f * w, CornerRadius(0.15f * w)))
    }
    var p = Path().apply { op(base, circle(0.56f, 0.27f, 0.25f), PathOperation.Union) }
    p = Path().apply { op(p, circle(0.27f, 0.37f, 0.17f), PathOperation.Union) }
    p = Path().apply { op(p, circle(0.80f, 0.42f, 0.16f), PathOperation.Union) }
    return p
}

private fun DrawScope.cloud(x: Float, y: Float, w: Float, paper: Boolean, storm: Boolean = false, back: Boolean = false) {
    val path = cloudPath(x, y, w)
    // Two offset passes stand in for a blur (none on the Mali-400): a soft edge under the cloud.
    translate(0f, 3.2f) { drawPath(path, Palette.shadow.copy(alpha = 0.10f)) }
    translate(0f, 1.6f) { drawPath(path, Palette.shadow.copy(alpha = 0.14f)) }
    val top = when { storm -> Palette.stormTop; paper -> Palette.paperCloudTop; else -> Palette.cloudTop }
    val bottom = when { storm -> Palette.stormBottom; paper -> Palette.paperCloudBottom; else -> Palette.cloudBottom }
    val brush = Brush.verticalGradient(listOf(top, bottom), startY = y, endY = y + 0.6f * w)
    drawPath(path, brush, alpha = if (back) 0.72f else 1f)
}

private fun DrawScope.drops(at: List<Pair<Float, Float>>, length: Float, width: Float, color: Color) {
    // Slanted like falling rain; drawn from (x, y) down-left.
    at.forEach { (x, y) ->
        drawLine(color, Offset(x, y), Offset(x - length * 0.36f, y + length), strokeWidth = width, cap = StrokeCap.Round)
    }
}

private fun DrawScope.flake(c: Offset, r: Float) {
    drawCircle(Palette.snowEdge.copy(alpha = 0.55f), r + 1.4f, c)
    for (i in 0 until 3) {
        val a = Math.toRadians(90.0 + i * 60.0)
        val dx = (kotlin.math.cos(a) * r).toFloat()
        val dy = (kotlin.math.sin(a) * r).toFloat()
        drawLine(Palette.snow, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = 2.6f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.bolt() {
    val p = Path().apply {
        moveTo(54f, 56f)
        lineTo(38f, 78f)
        lineTo(50f, 78f)
        lineTo(44f, 96f)
        lineTo(64f, 70f)
        lineTo(52f, 70f)
        lineTo(58f, 56f)
        close()
    }
    drawPath(p, Brush.verticalGradient(listOf(Palette.boltTop, Palette.boltBottom), startY = 56f, endY = 96f))
}

private fun DrawScope.fogLines(paper: Boolean) {
    val stroke = Stroke(width = 6f, cap = StrokeCap.Round)
    listOf(Triple(14f, 76f, 72f), Triple(26f, 88f, 84f)).forEach { (x0, y, x1) ->
        val p = Path().apply { moveTo(x0, y); lineTo(x1, y) }
        translate(0f, 1.6f) { drawPath(p, Palette.shadow.copy(alpha = 0.14f), style = stroke) }
        drawPath(p, if (paper) Palette.paperCloudBottom else Palette.fog, style = stroke)
    }
}
