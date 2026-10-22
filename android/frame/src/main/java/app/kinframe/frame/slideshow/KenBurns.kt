package app.kinframe.frame.slideshow

import kotlin.math.abs

/**
 * Ken Burns: a slow zoom and drift over the time a photo is on screen (product spec 4.4, "within the GLES 2.0
 * budget").
 *
 * Budget: the slide is already one screen-sized bitmap (one texture). The motion is a `graphicsLayer` scale and
 * translation of that texture, so each frame the Mali-400 draws one textured quad (two during the cross-fade):
 * about 1 M (2 M) fragments at 1280 × 800, far below its fill rate, and nothing is decoded or re-rendered. The zoom
 * stays at most 10 %, so upscaling softness stays invisible from a few steps away, and the scale never drops below
 * 1, so no edge of the photo ever shows.
 */
object KenBurns {
    /** Scale and translation (fractions of the screen size, positive = right/down) at the start and end. */
    data class Params(
        val startScale: Float,
        val endScale: Float,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
    ) {
        fun scaleAt(t: Float) = lerp(startScale, endScale, ease(t))
        fun xAt(t: Float) = lerp(startX, endX, ease(t))
        fun yAt(t: Float) = lerp(startY, endY, ease(t))
    }

    const val MIN_ZOOM = 1.04f
    const val MAX_ZOOM = 1.10f

    /**
     * Deterministic parameters for a placement: the same photo always moves the same way (stable across restarts,
     * easy to test). Zooms in toward the focal point, or out from it for every other photo, and the translation is
     * clamped so the scaled image always covers the screen: |x| ≤ (scale − 1) / 2.
     */
    fun params(id: String, focalX: Double?, focalY: Double?): Params {
        val h = id.hashCode()
        val zoom = MIN_ZOOM + (abs(h % 1000) / 1000f) * (MAX_ZOOM - MIN_ZOOM)
        val zoomIn = (h ushr 1) % 2 == 0
        // Toward the focal point; without one, a gentle drift in a direction picked from the id.
        val fx = focalX?.toFloat() ?: (0.5f + (((h ushr 4) % 3) - 1) * 0.25f)
        val fy = focalY?.toFloat() ?: (0.5f + (((h ushr 6) % 3) - 1) * 0.15f)
        val max = (zoom - 1f) / 2f
        // Moving the image toward -focal offset brings the focal point to the centre.
        val tx = ((0.5f - fx) * (zoom - 1f) * 2f).coerceIn(-max, max)
        val ty = ((0.5f - fy) * (zoom - 1f) * 2f).coerceIn(-max, max)
        return if (zoomIn) Params(1f, zoom, 0f, 0f, tx, ty) else Params(zoom, 1f, tx, ty, 0f, 0f)
    }

    /** Smooth start and end, so the motion is never noticed as it begins or stops. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
