package app.kinframe.family.send

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Focal-point framing and video trimming rules, shared by the editor and its tests. */
object Framing {
    /** Frame screens are 16:10 (landscape) or 10:16 (portrait). */
    const val LANDSCAPE = 16f / 10f
    const val PORTRAIT = 10f / 16f

    /** Shortest video the app sends (product spec 4.3: trimming, min 3 s). */
    const val MIN_VIDEO_MS = 3_000L

    /**
     * Where to anchor an image scaled to cover a box so the focal point is centred, clamped so no empty space shows.
     * Returns alignment biases in -1..1 (Compose's BiasAlignment, same meaning as CSS object-position), which is how the
     * frame crops in fill mode.
     */
    fun coverBias(focalX: Float, focalY: Float, imageAspect: Float, boxAspect: Float): Pair<Float, Float> {
        val scaleW = if (imageAspect >= boxAspect) imageAspect / boxAspect else 1f
        val scaleH = if (imageAspect >= boxAspect) 1f else boxAspect / imageAspect
        fun axis(f: Float, size: Float): Float {
            if (size <= 1.0001f) return 0f
            val t = (0.5f - f * size).coerceIn(1 - size, 0f) // left/top edge of the image in box units
            return (t / (1 - size)) * 2 - 1
        }
        return axis(focalX, scaleW) to axis(focalY, scaleH)
    }

    fun clampFocal(x: Float, y: Float): Pair<Float, Float> {
        fun r(v: Float) = (v.coerceIn(0f, 1f) * 1000).roundToInt() / 1000f
        return r(x) to r(y)
    }

    /**
     * Keeps a trim range valid: at least [MIN_VIDEO_MS] long (when the video allows), at most [maxMs] long, inside the
     * video. [moved] says which handle the person dragged, so the other one follows.
     */
    fun clampTrim(startMs: Long, endMs: Long, durationMs: Long, maxMs: Long, moved: TrimHandle): Pair<Long, Long> {
        val minLen = min(MIN_VIDEO_MS, durationMs)
        val maxLen = max(minLen, min(maxMs, durationMs))
        var s = startMs.coerceIn(0, durationMs)
        var e = endMs.coerceIn(0, durationMs)
        if (moved == TrimHandle.Start) {
            if (e - s < minLen) e = min(durationMs, s + minLen).also { if (it - s < minLen) s = it - minLen }
            if (e - s > maxLen) e = s + maxLen
        } else {
            if (e - s < minLen) s = max(0, e - minLen).also { if (e - it < minLen) e = it + minLen }
            if (e - s > maxLen) s = e - maxLen
        }
        return s to e
    }

    /** The initial trim for a video longer than the server allows: its first [maxMs]. */
    fun defaultTrim(durationMs: Long?, maxMs: Long): Pair<Long, Long>? =
        if (durationMs != null && durationMs > maxMs) 0L to maxMs else null
}

enum class TrimHandle { Start, End }
