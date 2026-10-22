package app.kinframe.frame.slideshow

import kotlin.math.max
import kotlin.math.min

/** Geometry for drawing a photo on the screen. Pure functions, unit-tested. */
object FocalCrop {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * Fill mode: the part of the source (in source pixels) that covers the whole screen, centred on the focal point
     * ([fx], [fy] in 0..1 from top-left) as far as the image edges allow.
     */
    fun fillSource(srcW: Int, srcH: Int, dstW: Int, dstH: Int, fx: Double = 0.5, fy: Double = 0.5): Rect {
        val scale = max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val cw = min(srcW.toFloat(), dstW / scale)
        val ch = min(srcH.toFloat(), dstH / scale)
        val cx = (fx.coerceIn(0.0, 1.0) * srcW).toFloat()
        val cy = (fy.coerceIn(0.0, 1.0) * srcH).toFloat()
        val left = (cx - cw / 2).coerceIn(0f, srcW - cw)
        val top = (cy - ch / 2).coerceIn(0f, srcH - ch)
        return Rect(left, top, left + cw, top + ch)
    }

    /** Fit mode: where the whole image goes on the screen (letterboxed, centred). */
    fun fitDest(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val scale = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = srcW * scale
        val h = srcH * scale
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Largest power-of-two sample size that still decodes at least [needW] × [needH] pixels, so we never decode
     * beyond screen resolution (product spec §5 Memory).
     */
    fun sampleSize(srcW: Int, srcH: Int, needW: Int, needH: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= needW && srcH / (s * 2) >= needH) s *= 2
        return s
    }

    /**
     * Whether "fill" should fall back to "fit" because cropping would lose too much of the picture
     * (e.g. a portrait photo on a landscape frame).
     */
    fun cropTooAggressive(srcW: Int, srcH: Int, dstW: Int, dstH: Int, maxLoss: Float = 0.45f): Boolean {
        val r = fillSource(srcW, srcH, dstW, dstH)
        val kept = (r.width * r.height) / (srcW.toFloat() * srcH)
        return 1f - kept > maxLoss
    }
}
