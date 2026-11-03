package app.kinframe.frame.slideshow

import kotlin.math.abs
import kotlin.math.ln

/**
 * Collage and smart-pairing geometry (product spec 4.4). Pure and unit-tested.
 *
 * A layout is a set of cells in normalised screen coordinates (0..1). Each orientation of the frame has its own
 * set for 2–6 photos, and photos go to the cells whose shape fits them best: portraits in tall cells, landscapes in
 * wide ones, so as little as possible is cropped.
 */
object Collage {
    data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        fun aspect(screenW: Int, screenH: Int) = (width * screenW) / (height * screenH)
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    /** A layout with photo [order]: `order[i]` is the index of the photo shown in `cells[i]`. */
    data class Placed(val cells: List<Cell>, val order: List<Int>)

    private fun c(l: Float, t: Float, r: Float, b: Float) = Cell(l, t, r, b)

    /** Cell sets for a landscape frame; a portrait frame uses the same sets transposed. */
    private val landscapeSets: Map<Int, List<Cell>> = mapOf(
        2 to listOf(c(0f, 0f, .5f, 1f), c(.5f, 0f, 1f, 1f)),
        3 to listOf(c(0f, 0f, .5f, 1f), c(.5f, 0f, 1f, .5f), c(.5f, .5f, 1f, 1f)),
        4 to listOf(c(0f, 0f, .5f, .5f), c(.5f, 0f, 1f, .5f), c(0f, .5f, .5f, 1f), c(.5f, .5f, 1f, 1f)),
        5 to listOf(c(0f, 0f, .4f, 1f), c(.4f, 0f, .7f, .5f), c(.7f, 0f, 1f, .5f), c(.4f, .5f, .7f, 1f), c(.7f, .5f, 1f, 1f)),
        6 to listOf(
            c(0f, 0f, 1 / 3f, .5f), c(1 / 3f, 0f, 2 / 3f, .5f), c(2 / 3f, 0f, 1f, .5f),
            c(0f, .5f, 1 / 3f, 1f), c(1 / 3f, .5f, 2 / 3f, 1f), c(2 / 3f, .5f, 1f, 1f),
        ),
    )

    fun cells(count: Int, landscapeScreen: Boolean): List<Cell> {
        val set = landscapeSets.getValue(count.coerceIn(2, 6))
        return if (landscapeScreen) set else set.map { Cell(it.top, it.left, it.bottom, it.right) }
    }

    /**
     * Puts photos with the given aspect ratios (width / height) into the cells for their count on a
     * [screenW] × [screenH] frame. Cells and photos are both sorted by aspect and matched in order, which minimises
     * the total log-aspect mismatch (and so the cropping) for this kind of assignment.
     */
    fun place(photoAspects: List<Float>, screenW: Int, screenH: Int): Placed {
        val cells = cells(photoAspects.size, screenW >= screenH)
        val cellOrder = cells.indices.sortedBy { cells[it].aspect(screenW, screenH) }
        val photoOrder = photoAspects.indices.sortedBy { photoAspects[it] }
        val order = IntArray(cells.size)
        cellOrder.forEachIndexed { rank, cellIdx -> order[cellIdx] = photoOrder[rank] }
        return Placed(cells, order.toList())
    }

    /**
     * Puts photos into given [cells] (the split layout's two photo cells): the same matching by aspect as [place].
     */
    fun placeInto(cells: List<Cell>, photoAspects: List<Float>, screenW: Int, screenH: Int): Placed {
        require(cells.size == photoAspects.size)
        val cellOrder = cells.indices.sortedBy { cells[it].aspect(screenW, screenH) }
        val photoOrder = photoAspects.indices.sortedBy { photoAspects[it] }
        val order = IntArray(cells.size)
        cellOrder.forEachIndexed { rank, cellIdx -> order[cellIdx] = photoOrder[rank] }
        return Placed(cells, order.toList())
    }

    /**
     * The split layout's second photo: [p] (aspect [aspect]) takes the cell its shape fits best, and the partner is the
     * first of the [upcoming] photos (ids and aspects, in play order) that fits the other cell: a landscape photo for a
     * wide cell, a portrait one for a tall cell. When none of them does, the first upcoming photo (cropped more).
     */
    fun splitPartner(aspect: Float, cells: List<Cell>, upcoming: List<Pair<String, Float?>>, screenW: Int, screenH: Int): String? {
        if (upcoming.isEmpty() || cells.size != 2) return null
        val a = cells[0].aspect(screenW, screenH)
        val b = cells[1].aspect(screenW, screenH)
        fun fit(x: Float, cell: Float) = abs(ln((x / cell).toDouble()))
        val other = if (fit(aspect, a) <= fit(aspect, b)) b else a
        val wide = other >= 1f
        return upcoming.firstOrNull { (_, x) -> x != null && (x >= 1f) == wide }?.first ?: upcoming.first().first
    }

    /** Sum of |ln(photo aspect / cell aspect)|: 0 is a perfect fit, used by tests and to compare layouts. */
    fun mismatch(p: Placed, aspects: List<Float>, screenW: Int, screenH: Int): Double =
        p.cells.indices.sumOf { i -> abs(ln((aspects[p.order[i]] / p.cells[i].aspect(screenW, screenH)).toDouble())) }

    /** How many photos per screen: the setting, limited by what can be shown (videos never go in a collage). */
    fun count(collageMax: Int, available: Int): Int = minOf(collageMax.coerceIn(2, 6), available)

    // ------------------------------------------------------------------------------------------ smart pairing

    /** Portrait photos look awkward on a landscape frame (and vice versa): show two side by side instead. */
    fun wantsPair(photoAspect: Float, screenW: Int, screenH: Int): Boolean {
        val screen = screenW.toFloat() / screenH
        return if (screen >= 1f) photoAspect < 0.85f else photoAspect > 1.18f
    }

    /**
     * The partner for a mismatched photo: the first of the [upcoming] ones (aspects, in play order) that has the
     * same orientation, so the order barely changes. Null keeps the photo on its own.
     */
    fun partner(upcoming: List<Pair<String, Float?>>, screenW: Int, screenH: Int): String? =
        upcoming.firstOrNull { (_, a) -> a != null && wantsPair(a, screenW, screenH) }?.first
}
