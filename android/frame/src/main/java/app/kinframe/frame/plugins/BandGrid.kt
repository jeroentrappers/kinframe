package app.kinframe.frame.plugins

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinframe.frame.slideshow.SlideRenderer

/**
 * The band layout reads as one grid with the multi-photo slides: the photo area and the band are cells with the same
 * gap, corner radius and warm dark background as the tiles of a pair or collage ([SlideRenderer.renderTiles]).
 */
object BandGrid {
    val Gap: Dp = SlideRenderer.TILE_GAP_DP.dp
    val Radius: Dp = SlideRenderer.TILE_RADIUS_DP.dp
    val Background = Color(SlideRenderer.TILE_BACKGROUND)
    val CardShape = RoundedCornerShape(SlideRenderer.TILE_RADIUS_DP.dp)
}

/**
 * Draws the grid's frame over the photo area: a [BandGrid.Gap] border and rounded corners in the grid background, both
 * scaled by [fraction] (1 = band open, 0 = full screen, nothing drawn), read in the draw phase only. A pair or collage
 * already has this border and these corners in its bitmap, so on those the frame lands exactly on them. Four thin
 * rectangles and four small corner paths: far cheaper on the Mali-400 than clipping the moving photo layers.
 */
fun Modifier.bandGridFrame(fraction: () -> Float): Modifier = drawWithCache {
    val gap = BandGrid.Gap.toPx()
    val r = BandGrid.Radius.toPx()
    // The outside of a rounded corner, for the top-left corner at the origin; mirrored for the other three.
    val corner = Path().apply {
        moveTo(0f, 0f)
        lineTo(r, 0f)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 270f, -90f, false)
        close()
    }
    val bg = BandGrid.Background
    onDrawWithContent {
        drawContent()
        val f = fraction().coerceIn(0f, 1f)
        if (f <= 0f) return@onDrawWithContent
        val g = gap * f
        val w = size.width
        val h = size.height
        drawRect(bg, Offset.Zero, Size(w, g))
        drawRect(bg, Offset(0f, h - g), Size(w, g))
        drawRect(bg, Offset(0f, g), Size(g, h - 2 * g))
        drawRect(bg, Offset(w - g, g), Size(g, h - 2 * g))
        fun at(x: Float, y: Float, sx: Float, sy: Float) = translate(x, y) { scale(sx * f, sy * f, Offset.Zero) { drawPath(corner, bg) } }
        at(g, g, 1f, 1f)
        at(w - g, g, -1f, 1f)
        at(g, h - g, 1f, -1f)
        at(w - g, h - g, -1f, -1f)
    }
}
