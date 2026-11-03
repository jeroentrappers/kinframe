package app.kinframe.frame.plugins

import kotlin.math.roundToInt

/** A rectangle in screen pixels. */
data class PxRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * The band layout (docs/plugins.md §2.1): the photo gets a 3:2 area (2:3 in portrait) and the rest of the long side
 * becomes the band. 1280×800 → photo 1200×800 + an 80 px column; 800×1280 → an 80 px strip on top + photo 800×1200;
 * 1920×1080 → a 300 px column. A screen without room for a usable band (4:3, or a band thinner than [MIN_BAND_DP])
 * falls back to the overlay layout.
 */
object BandGeometry {
    /** Thinner than this, a compact widget cannot show an icon and a value legibly. */
    const val MIN_BAND_DP = 64

    sealed interface Result
    /** No room for a band on this screen: use the corners. */
    data object Overlay : Result
    data class Band(val photo: PxRect, val band: PxRect, val edge: Edge) : Result {
        /** Thickness of the band (its width in a column, its height in a strip). */
        val thickness: Int get() = if (edge == Edge.Top) band.height else band.width
        val vertical: Boolean get() = edge != Edge.Top
    }

    /** Where the band is: a column at the left or right in landscape, a strip at the top in portrait. */
    enum class Edge { Left, Right, Top }

    fun compute(widthPx: Int, heightPx: Int, density: Float, side: BandSide): Result {
        if (widthPx <= 0 || heightPx <= 0) return Overlay
        val min = (MIN_BAND_DP * density).roundToInt()
        return if (widthPx >= heightPx) {
            val photoW = (heightPx * 3f / 2f).roundToInt()
            val band = widthPx - photoW
            if (band < min) Overlay
            else if (side == BandSide.Left) Band(PxRect(band, 0, photoW, heightPx), PxRect(0, 0, band, heightPx), Edge.Left)
            else Band(PxRect(0, 0, photoW, heightPx), PxRect(photoW, 0, band, heightPx), Edge.Right)
        } else {
            val photoH = (widthPx * 3f / 2f).roundToInt()
            val band = heightPx - photoH
            if (band < min) Overlay
            else Band(PxRect(0, band, widthPx, photoH), PxRect(0, 0, widthPx, band), Edge.Top)
        }
    }

    /**
     * The photo area while the band opens or closes: [fraction] 1 = band fully open, 0 = collapsed (full screen).
     * Returned as left, top, width, height packed in [out] so the per-frame layout pass allocates nothing.
     */
    fun animatedPhoto(full: PxRect, band: Band, fraction: Float, out: IntArray) {
        val t = band.thickness * fraction.coerceIn(0f, 1f)
        when (band.edge) {
            Edge.Left -> { out[0] = full.left + t.roundToInt(); out[1] = full.top; out[2] = full.width - t.roundToInt(); out[3] = full.height }
            Edge.Right -> { out[0] = full.left; out[1] = full.top; out[2] = full.width - t.roundToInt(); out[3] = full.height }
            Edge.Top -> { out[0] = full.left; out[1] = full.top + t.roundToInt(); out[2] = full.width; out[3] = full.height - t.roundToInt() }
        }
    }

    /** Scale that makes a [srcW]×[srcH] render cover a [dstW]×[dstH] area (centre crop), used while the band animates. */
    fun coverScale(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Float =
        if (srcW <= 0 || srcH <= 0) 1f else maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
}
