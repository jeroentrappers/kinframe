package app.kinframe.frame.slideshow

import kotlin.math.ceil

/**
 * The bottom-end corner the clock and weather block takes on the photo, in dp, measured from the photo area's
 * bottom-right corner (its screen margins included). [None] when the corner is empty (clock, date and weather off, or
 * moved into the plugin band).
 */
data class CornerReserve(val widthDp: Float, val heightDp: Float) {
    val isNone: Boolean get() = widthDp <= 0f || heightDp <= 0f

    companion object {
        val None = CornerReserve(0f, 0f)

        /** Before the corner has been measured: the old fixed reserve (340 dp) when the clock or date is on. */
        fun estimate(clockOrDate: Boolean, weather: Boolean): CornerReserve = when {
            clockOrDate && weather -> CornerReserve(520f, 150f)
            clockOrDate -> CornerReserve(316f, 140f)
            weather -> CornerReserve(260f, 120f)
            else -> None
        }

        /**
         * The measured block, rounded **up** to steps of [STEP_DP], so the clock ticking from "9:59" to "10:00" (or the
         * weather line changing by a letter) doesn't re-render the slides.
         */
        fun quantized(widthDp: Float, heightDp: Float): CornerReserve {
            if (widthDp <= 0f || heightDp <= 0f) return None
            fun up(v: Float) = ceil(v / STEP_DP) * STEP_DP
            return CornerReserve(up(widthDp), up(heightDp))
        }

        const val STEP_DP = 24f
    }
}

/**
 * Where the caption may go so it never runs under the clock and weather block (bottom-right): pure geometry for
 * [CaptionPainter] and the collage/pair labels, in pixels. The rule is the same for single photos, pairs, collages,
 * both orientations and the photo area beside the plugin band (the renderer works in that area's coordinates):
 *
 * - text whose line runs beside the block ends [GAP_DP] before it and is wrapped/ellipsized there;
 * - when that leaves less than [MIN_TEXT_DP] (a narrow portrait screen, a collage tile under the clock), the text keeps
 *   its full width and is lifted above the block instead.
 */
object CaptionSafeArea {
    /** Space between the caption's end and the clock block. */
    const val GAP_DP = 24f
    /**
     * Narrower than this, a caption is lifted above the block rather than squeezed beside it: about the width of
     * "Line Van Gavere · via Frameo · 5 days ago", so the usual sender line never ends in "…".
     */
    const val MIN_TEXT_DP = 320f
    /** Margin above the block when lifted. */
    const val LIFT_GAP_DP = 12f
    /** The caption's own margin to the screen edge when nothing is in the corner. */
    const val EDGE_DP = 36f

    /** A text box: at most [maxWidth] px wide, its last line ending at [bottom] px. */
    data class Box(val maxWidth: Int, val bottom: Float, val lifted: Boolean = false)

    /**
     * The single-photo caption block (avatar, caption, sender line): text starts at [textLeft] and would end at
     * [bottom] on a [w] × [h] px area.
     */
    fun single(w: Int, h: Int, textLeft: Float, bottom: Float, reserve: CornerReserve, density: Float): Box {
        val full = (w - EDGE_DP * density - textLeft).toInt().coerceAtLeast(1)
        if (reserve.isNone) return Box(full, bottom)
        val beside = (w - (reserve.widthDp + GAP_DP) * density - textLeft).toInt()
        if (beside >= MIN_TEXT_DP * density) return Box(beside, bottom)
        return Box(full, minOf(bottom, h - (reserve.heightDp + LIFT_GAP_DP) * density), lifted = true)
    }

    /**
     * The label inside one tile of a pair or collage, [left]…[right] × …[tileBottom] px on a [w] × [h] px area, with
     * [pad] px inside the tile. Only a tile that reaches into the block's corner is affected.
     */
    fun tile(left: Float, right: Float, tileBottom: Float, w: Int, h: Int, pad: Float, reserve: CornerReserve, density: Float): Box {
        val bottom = tileBottom - pad
        val full = (right - left - 2 * pad).toInt().coerceAtLeast(1)
        if (reserve.isNone) return Box(full, bottom)
        val blockLeft = w - (reserve.widthDp + GAP_DP) * density
        val blockTop = h - reserve.heightDp * density
        // The label sits in the tile's bottom band; it only meets the block when that band is beside it.
        if (right <= blockLeft || tileBottom <= blockTop) return Box(full, bottom)
        val beside = (blockLeft - left - pad).toInt()
        if (beside >= MIN_TEXT_DP * density) return Box(beside, bottom)
        return Box(full, minOf(bottom, blockTop - LIFT_GAP_DP * density), lifted = true)
    }
}
