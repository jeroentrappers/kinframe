package app.kinframe.frame.plugins

import app.kinframe.frame.slideshow.Collage

/**
 * The split layout (docs/plugins.md §2.2): half the screen holds one photo, the other half is split into the dashboard
 * (widgets, clock and the next 48 hours of the calendar) and a second photo. On a landscape screen the halves are
 * side by side, the big photo is tall (portrait-shaped) and the small one wide; on a portrait screen the big photo is
 * on top and wide, and the bottom half holds the dashboard beside a tall photo. [side] puts the dashboard's half on the
 * left or right (landscape) and the dashboard itself left or right of the small photo (portrait).
 */
object SplitGeometry {
    data class Cells(val big: Collage.Cell, val small: Collage.Cell, val dashboard: Collage.Cell)

    fun cells(landscape: Boolean, side: BandSide): Cells {
        val right = side == BandSide.Right
        return if (landscape) {
            val half = if (right) Collage.Cell(.5f, 0f, 1f, 1f) else Collage.Cell(0f, 0f, .5f, 1f)
            Cells(
                big = if (right) Collage.Cell(0f, 0f, .5f, 1f) else Collage.Cell(.5f, 0f, 1f, 1f),
                // The dashboard on top, where the eye goes first; the photo below it.
                dashboard = Collage.Cell(half.left, 0f, half.right, .5f),
                small = Collage.Cell(half.left, .5f, half.right, 1f),
            )
        } else {
            Cells(
                big = Collage.Cell(0f, 0f, 1f, .5f),
                dashboard = if (right) Collage.Cell(.5f, .5f, 1f, 1f) else Collage.Cell(0f, .5f, .5f, 1f),
                small = if (right) Collage.Cell(0f, .5f, .5f, 1f) else Collage.Cell(.5f, .5f, 1f, 1f),
            )
        }
    }
}
