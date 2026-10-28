package app.kinframe.frame.plugins

import app.kinframe.frame.calendar.CalendarFixtures
import app.kinframe.frame.calendar.CalendarLayout
import app.kinframe.frame.slideshow.Collage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The split layout (docs/plugins.md §2.2): where the photos and the dashboard go, and which photo goes where. */
class SplitLayoutTest {
    private fun aspect(c: Collage.Cell, w: Int, h: Int) = c.aspect(w, h)

    @Test fun landscapeAPortraitHalfAndTwoWideQuarters() {
        val c = SplitGeometry.cells(landscape = true, side = BandSide.Right)
        assertEquals(Collage.Cell(0f, 0f, .5f, 1f), c.big)
        assertEquals(Collage.Cell(.5f, 0f, 1f, .5f), c.dashboard)
        assertEquals(Collage.Cell(.5f, .5f, 1f, 1f), c.small)
        assertEquals(0.8f, aspect(c.big, 1280, 800), 0.001f)
        assertEquals(1.6f, aspect(c.small, 1280, 800), 0.001f)
        val left = SplitGeometry.cells(landscape = true, side = BandSide.Left)
        assertEquals(.5f, left.big.left)
        assertEquals(0f, left.dashboard.left)
    }

    @Test fun portraitAWideHalfOnTopAndTwoTallQuarters() {
        val c = SplitGeometry.cells(landscape = false, side = BandSide.Right)
        assertEquals(Collage.Cell(0f, 0f, 1f, .5f), c.big)
        assertEquals(Collage.Cell(.5f, .5f, 1f, 1f), c.dashboard)
        assertEquals(Collage.Cell(0f, .5f, .5f, 1f), c.small)
        assertEquals(1.25f, aspect(c.big, 800, 1280), 0.001f)
        assertEquals(0.625f, aspect(c.small, 800, 1280), 0.001f)
    }

    @Test fun eachPhotoGoesWhereItsShapeFits() {
        val c = SplitGeometry.cells(landscape = true, side = BandSide.Right)
        val cells = listOf(c.big, c.small)
        // A landscape photo comes up: it takes the wide quarter, and the next portrait one joins it in the tall half.
        val upcoming = listOf("wide2" to 1.5f, "tall" to 0.75f, "wide3" to 1.33f)
        assertEquals("tall", Collage.splitPartner(1.5f, cells, upcoming, 1280, 800))
        val placed = Collage.placeInto(cells, listOf(1.5f, 0.75f), 1280, 800)
        assertEquals(listOf(1, 0), placed.order) // big (tall) cell ← photo 1 (portrait), small (wide) ← photo 0
        // A portrait one comes up: the first landscape photo joins it.
        assertEquals("wide2", Collage.splitPartner(0.75f, cells, upcoming, 1280, 800))
        // Portrait frame: the big cell is wide, so a landscape photo goes there and a portrait one below.
        val p = SplitGeometry.cells(landscape = false, side = BandSide.Right)
        assertEquals("tall", Collage.splitPartner(1.5f, listOf(p.big, p.small), upcoming, 800, 1280))
        assertEquals(listOf(0, 1), Collage.placeInto(listOf(p.big, p.small), listOf(1.5f, 0.75f), 800, 1280).order)
        // Nothing of the other shape coming up: the next photo anyway (it is cropped more), never none.
        assertEquals("wide2", Collage.splitPartner(1.5f, cells, listOf("wide2" to 1.5f, "x" to null), 1280, 800))
        assertEquals(null, Collage.splitPartner(1.5f, cells, emptyList(), 1280, 800))
    }

    @Test fun theAgendaCoversTheNext48Hours() {
        val d = CalendarFixtures.data()
        val days = CalendarLayout.within(d, CalendarFixtures.today, CalendarFixtures.nowMs, hours = 48, maxEvents = 20)
        val end = CalendarFixtures.nowMs + 48 * 3_600_000L
        assertEquals(CalendarFixtures.today, days.first().date)
        assertTrue(days.all { it.date <= CalendarFixtures.today.plusDays(2) })
        days.flatMap { it.timed }.forEach { e -> assertTrue(e.title, e.start!!.toInstant().toEpochMilli() < end) }
        // Grandpa's birthday is in two days (all day): within the window's last day.
        assertTrue(days.flatMap { it.all }.any { it.title == "Grandpa's birthday" })
        // Nothing from the third day on.
        assertTrue(days.flatMap { it.all }.none { it.title == "Lunch at Oma's" })
    }

    @Test fun wideDashboardWidgetsLineUpWithTheAgendaColumns() {
        // 600 wide, 14 between the columns: each column 293, the right one at 307.
        val p = gridPlacement(listOf(160 to 50, 270 to 70, 120 to 30, 500 to 40), maxW = 600, gap = 14, rowGap = 12)
        assertEquals(StripPlace(0, 0), p[0]) // the price, left
        assertEquals(StripPlace(307, 0), p[1]) // the energy flow, right
        assertEquals(StripPlace(0, 62), p[2]) // the wind under the price, not on a row of its own
        assertEquals(StripPlace(0, 104), p[3]) // too wide for a column: across both, below everything
        // Wider than the dashboard: scaled down to fit.
        assertEquals(0.5f, gridPlacement(listOf(1200 to 40), 600, 14, 12)[0].scale, 0.001f)
    }

    @Test fun narrowDashboardWidgetsFlowLikeWords() {
        val p = flowPlacement(listOf(160 to 50, 270 to 70, 120 to 30), maxW = 500, gap = 36, rowGap = 12)
        assertEquals(listOf(StripPlace(0, 0), StripPlace(196, 0), StripPlace(0, 82)), p)
    }
}
