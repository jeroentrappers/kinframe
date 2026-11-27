package app.kinframe.frame.slideshow

import app.kinframe.core.api.Framing
import app.kinframe.core.api.FramingView
import app.kinframe.core.api.KinJson
import app.kinframe.frame.data.PlacementEntity

/** Which view of a photo a screen shows (docs/spec/framing.md §3.1). Pure, unit-tested. */
object FramingPick {
    /**
     * The view for one screen: centre [x], [y] (0..1 of the photo) and [zoom]. [chosen]: it came from a framing (the
     * frame owner's or the sender's), so the frame fills even where it crops a lot (§3.2) and Ken Burns stays inside it
     * (§3.4).
     */
    data class View(val x: Double, val y: Double, val zoom: Double, val chosen: Boolean) {
        /** Framed with a zoom: shown big, never in a pair or collage (§3.5). */
        val zoomed: Boolean get() = chosen && zoom > 1.0
    }

    /** A screen is landscape when its width is at least its height (a square screen too, §2). */
    fun isLandscape(screenW: Int, screenH: Int): Boolean = screenW >= screenH

    /** The view of [p] for a [screenW] × [screenH] screen. */
    fun of(p: PlacementEntity, screenW: Int, screenH: Int): View =
        pick(p.isVideo, p.framingOverride, p.focalOverridden, p.framing, p.focalX, p.focalY, isLandscape(screenW, screenH))

    /**
     * For the screen's orientation, the first that exists: the placement's `framing_override`, its `focal_override` at
     * 1× ([focalOverridden]: [focalX]/[focalY] are that override), the item's `framing`, the focal point at 1× (else the
     * centre). Videos ignore framing. [framing] and [framingOverride] are stored `Framing` JSON.
     */
    fun pick(
        isVideo: Boolean, framingOverride: String?, focalOverridden: Boolean, framing: String?,
        focalX: Double?, focalY: Double?, landscape: Boolean,
    ): View {
        val focal = View(focalX ?: 0.5, focalY ?: 0.5, 1.0, chosen = false)
        if (isVideo) return focal
        parse(framingOverride)?.of(landscape)?.let { return chosen(it) }
        if (focalOverridden && (focalX != null || focalY != null)) return focal
        parse(framing)?.of(landscape)?.let { return chosen(it) }
        return focal
    }

    private fun chosen(v: FramingView) =
        View(v.x.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.5, v.y.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.5, FocalCrop.clampZoom(v.zoom), chosen = true)

    private fun parse(json: String?): Framing? =
        json?.let { runCatching { KinJson.decodeFromString(Framing.serializer(), it) }.getOrNull() }
}
