package app.kinframe.frame.slideshow

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Ken Burns (product spec 4.4, "within the GLES 2.0 budget"), modelled on the documentary technique and on how
 * editors build it (iMovie / Final Cut: a start and an end framing, longer moves chained so one segment's end is the
 * next one's start):
 * - the camera never stands still: it moves from the first frame the photo is visible until it has faded out,
 *   through the cross-dissolves, with no easing to a halt;
 * - each move changes the framing in both size and position: a push in from a wide framing onto the subject (the
 *   focal point when the photo has one), a drift to another detail, a pull back out, and so on;
 * - the framings are keyframes on a closed Catmull–Rom spline, so chained segments join without a kink or a stop.
 *
 * Budget: the slide is already one screen-sized bitmap (one texture). The motion is a `graphicsLayer` scale and
 * translation of that texture, so each frame the Mali-400 draws one textured quad (two during the cross-fade):
 * about 1 M (2 M) fragments, far below its fill rate, and nothing is decoded or re-rendered. The scale never drops
 * below 1 and the translation is bounded by the current scale, so no edge of the photo ever shows. Close framings
 * upscale the screen-sized texture by up to [MAX_ZOOM], a slight softness traded for a clearly visible move.
 */
object KenBurns {
    /** A framing: scale, and translation as a fraction of the screen size (positive = right/down). */
    data class Key(val scale: Float, val x: Float, val y: Float)

    /**
     * Keyframes of a closed loop. Time is measured in segments: t = 1 is [keys][1], and the motion keeps going past
     * the last key back to the first.
     */
    data class Params(val keys: List<Key>) {
        fun scaleAt(t: Float) = at(t).scale
        fun xAt(t: Float) = at(t).x
        fun yAt(t: Float) = at(t).y

        fun at(t: Float): Key {
            val n = keys.size
            val i = floor(t).toInt()
            val u = t - i
            val p0 = keys[(i - 1).mod(n)]; val p1 = keys[i.mod(n)]; val p2 = keys[(i + 1).mod(n)]; val p3 = keys[(i + 2).mod(n)]
            val s = catmullRom(p0.scale, p1.scale, p2.scale, p3.scale, u).coerceIn(1f, MAX_ZOOM)
            // The spline may overshoot a little between keys: keep the photo covering the screen.
            val max = (s - 1f) / 2f
            return Key(
                s,
                catmullRom(p0.x, p1.x, p2.x, p3.x, u).coerceIn(-max, max),
                catmullRom(p0.y, p1.y, p2.y, p3.y, u).coerceIn(-max, max),
            )
        }
    }

    const val WIDE_MIN = 1.02f
    const val WIDE_MAX = 1.06f
    const val CLOSE_MIN = 1.24f
    const val MAX_ZOOM = 1.32f

    /** One move between framings: a push in onto the subject takes this long. */
    const val SEGMENT_MS = 9_000L

    private const val KEYS = 6

    /**
     * Deterministic for a placement: the same photo always moves the same way (stable across restarts, easy to
     * test). Even keys are wide, odd keys close; the first close framing is the focal point when there is one.
     */
    fun params(id: String, focalX: Double?, focalY: Double?): Params {
        var h = id.hashCode().toLong() xor 0x5DEECE66DL
        fun rnd(): Float {
            h = h * 6364136223846793005L + 1442695040888963407L
            return ((h ushr 40).toInt() and 0xFFFFFF) / 16_777_216f
        }
        val keys = (0 until KEYS).map { k ->
            val close = k % 2 == 1
            val scale = if (close) CLOSE_MIN + rnd() * (MAX_ZOOM - CLOSE_MIN) else WIDE_MIN + rnd() * (WIDE_MAX - WIDE_MIN)
            val max = (scale - 1f) / 2f
            if (k == 1 && (focalX != null || focalY != null)) {
                // Moving the image by (0.5 − focal) brings the focal point toward the centre.
                Key(
                    scale,
                    ((0.5f - (focalX?.toFloat() ?: 0.5f)) * (scale - 1f) * 2f).coerceIn(-max, max),
                    ((0.5f - (focalY?.toFloat() ?: 0.5f)) * (scale - 1f) * 2f).coerceIn(-max, max),
                )
            } else {
                // Close framings go to a detail away from the centre; wide ones sit anywhere their slack allows.
                val reach = if (close) 0.6f + 0.4f * rnd() else rnd()
                val angle = rnd() * 2f * Math.PI.toFloat()
                Key(scale, cos(angle) * reach * max, sin(angle) * reach * max)
            }
        }
        return Params(keys)
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (3f * p1 - p0 - 3f * p2 + p3) * t3)
    }
}
