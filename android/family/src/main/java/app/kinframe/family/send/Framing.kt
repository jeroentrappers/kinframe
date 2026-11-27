package app.kinframe.family.send

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Focal-point framing, zoom per orientation and video trimming rules, shared by the editor and its tests. */
object Framing {
    /** Frame screens are 16:10 (landscape) or 10:16 (portrait). */
    const val LANDSCAPE = 16f / 10f
    const val PORTRAIT = 10f / 16f

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f

    /** Below this many photo pixels across the screen's long side the editor warns softly (docs/spec/framing.md §4). */
    const val SHARP_PX = 960

    /** The master rendition's long edge (README §5.3): what the frame has to show the visible part with. */
    const val MASTER_LONG_EDGE = 2560

    /** A view for one orientation (docs/spec/framing.md §2): centre of the visible part in 0..1 and zoom 1..4. */
    data class View(val x: Float, val y: Float, val zoom: Float = 1f)

    /** The visible part of the photo, as fractions of its width and height from the top-left. */
    data class Visible(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centerX: Float get() = left + width / 2
        val centerY: Float get() = top + height / 2
    }

    /**
     * The one framing rule (docs/spec/framing.md §3) in photo fractions: the cover crop of a photo of [imageAspect] in
     * a box of [boxAspect], narrowed by the zoom around the view's centre and kept inside the photo.
     */
    fun visible(view: View, imageAspect: Float, boxAspect: Float): Visible {
        val zoom = view.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Cover: the wider shape loses its sides, the taller one its top and bottom.
        val w = (if (imageAspect >= boxAspect) boxAspect / imageAspect else 1f) / zoom
        val h = (if (imageAspect >= boxAspect) 1f else imageAspect / boxAspect) / zoom
        val left = (view.x.coerceIn(0f, 1f) - w / 2).coerceIn(0f, 1f - w)
        val top = (view.y.coerceIn(0f, 1f) - h / 2).coerceIn(0f, 1f - h)
        return Visible(left, top, w, h)
    }

    /**
     * A view the window can show: zoom in 1..4 and the centre moved so the window stays on the photo (the clamp of §3),
     * rounded to 3 decimals like the server does ([round] false while a gesture is still moving it).
     */
    fun clampView(view: View, imageAspect: Float, boxAspect: Float, round: Boolean = true): View {
        val z = view.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val v = visible(view.copy(zoom = z), imageAspect, boxAspect)
        fun r(f: Float) = if (round) (f.coerceIn(0f, 1f) * 1000).roundToInt() / 1000f else f.coerceIn(0f, 1f)
        return View(r(v.centerX), r(v.centerY), if (round) (z * 1000).roundToInt() / 1000f else z)
    }

    /**
     * Photo pixels across the screen's long side for this view: the item's size scaled to the master's long edge, times
     * the visible fraction of that side. Below [SHARP_PX] the editor says it may look less sharp (§4).
     */
    fun visiblePixels(view: View, width: Int, height: Int, boxAspect: Float): Int {
        if (width <= 0 || height <= 0) return Int.MAX_VALUE
        val scale = min(1f, MASTER_LONG_EDGE.toFloat() / max(width, height))
        val v = visible(view, width.toFloat() / height, boxAspect)
        return if (boxAspect >= 1f) (v.width * width * scale).roundToInt() else (v.height * height * scale).roundToInt()
    }

    fun mayLookSoft(view: View, width: Int?, height: Int?, boxAspect: Float): Boolean =
        width != null && height != null && visiblePixels(view, width, height, boxAspect) < SHARP_PX

    /** The focal point to send with a framing: the landscape view's centre, else the portrait one's, else [tapped] (§4). */
    fun focalFor(landscape: View?, portrait: View?, tapped: Pair<Float, Float>): Pair<Float, Float> =
        landscape?.let { it.x to it.y } ?: portrait?.let { it.x to it.y } ?: tapped

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
