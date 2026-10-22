package app.kinframe.frame.slideshow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.kinframe.frame.data.PlacementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class CollageTest {
    private val portrait = 2f / 3
    private val landscape = 3f / 2

    @Test fun cellsTileTheScreenWithoutOverlap() {
        for (n in 2..6) for (land in listOf(true, false)) {
            val cells = Collage.cells(n, land)
            assertEquals(n, cells.size)
            assertEquals("area n=$n", 1.0, cells.sumOf { (it.width * it.height).toDouble() }, 1e-4)
            for (i in cells.indices) for (j in i + 1 until cells.size) {
                val a = cells[i]; val b = cells[j]
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertTrue("overlap n=$n", overlapW <= 1e-4f || overlapH <= 1e-4f)
            }
        }
    }

    @Test fun orientationAwareLayouts() {
        // Landscape frame, 3 photos: the portrait goes in the tall cell, the landscapes in the wide ones.
        val p = Collage.place(listOf(landscape, portrait, landscape), 1280, 800)
        val tall = p.cells.indices.maxBy { p.cells[it].height / p.cells[it].width }
        assertEquals(1, p.order[tall])
        // Portrait frame uses the transposed set: the big cell is on top and wide.
        val q = Collage.place(listOf(portrait, landscape, portrait), 800, 1280)
        val wide = q.cells.indices.maxBy { q.cells[it].aspect(800, 1280) }
        assertEquals(1, q.order[wide])
        assertTrue(q.cells[wide].top == 0f)
    }

    @Test fun placementIsTheBestAssignment() {
        val aspects = listOf(0.66f, 1.5f, 1.0f, 1.78f, 0.75f)
        for ((w, h) in listOf(1280 to 800, 800 to 1280)) {
            val best = Collage.place(aspects, w, h)
            val m = Collage.mismatch(best, aspects, w, h)
            permutations(aspects.indices.toList()).forEach { perm ->
                assertTrue(m <= Collage.mismatch(Collage.Placed(best.cells, perm), aspects, w, h) + 1e-4)
            }
        }
    }

    @Test fun countFollowsTheSetting() {
        assertEquals(3, Collage.count(3, 10))
        assertEquals(2, Collage.count(6, 2))
        assertEquals(6, Collage.count(9, 10))
        assertEquals(2, Collage.count(0, 10))
    }

    @Test fun smartPairing() {
        assertTrue(Collage.wantsPair(portrait, 1280, 800))
        assertFalse(Collage.wantsPair(landscape, 1280, 800))
        assertFalse(Collage.wantsPair(1f, 1280, 800))
        assertTrue(Collage.wantsPair(landscape, 800, 1280))
        assertFalse(Collage.wantsPair(portrait, 800, 1280))
        // The first upcoming photo of the same orientation; unknown sizes are skipped.
        assertEquals("c", Collage.partner(listOf("a" to landscape, "b" to null, "c" to portrait, "d" to portrait), 1280, 800))
        assertNull(Collage.partner(listOf("a" to landscape), 1280, 800))
    }

    private fun permutations(l: List<Int>): List<List<Int>> =
        if (l.size <= 1) listOf(l) else l.flatMap { x -> permutations(l - x).map { listOf(x) + it } }
}

class KenBurnsTest {
    @Test fun neverShowsAnEdge() {
        repeat(500) { i ->
            val p = KenBurns.params("placement-$i", if (i % 3 == 0) null else (i % 10) / 9.0, if (i % 4 == 0) null else (i % 7) / 6.0)
            for (k in 0..20) {
                val t = k / 20f
                val s = p.scaleAt(t)
                assertTrue(s >= 1f - 1e-6f && s <= KenBurns.MAX_ZOOM + 1e-6f)
                val slack = (s - 1f) / 2f + 1e-5f
                assertTrue("x id=$i t=$t", abs(p.xAt(t)) <= slack)
                assertTrue("y id=$i t=$t", abs(p.yAt(t)) <= slack)
            }
        }
    }

    @Test fun subtleDeterministicAndVaried() {
        val a = KenBurns.params("abc", 0.2, 0.3)
        assertEquals(a, KenBurns.params("abc", 0.2, 0.3))
        val zooms = (0 until 100).map { KenBurns.params("p$it", null, null) }
        assertTrue(zooms.all { maxOf(it.startScale, it.endScale) in KenBurns.MIN_ZOOM..KenBurns.MAX_ZOOM })
        assertTrue("both directions", zooms.any { it.endScale > it.startScale } && zooms.any { it.endScale < it.startScale })
        // Toward a focal point on the left: the image moves right.
        val left = (0 until 50).map { KenBurns.params("q$it", 0.1, 0.5) }.first { it.endScale > it.startScale }
        assertTrue(left.endX > 0f)
        assertEquals(0f, KenBurns.ease(0f)); assertEquals(1f, KenBurns.ease(1f)); assertEquals(0.5f, KenBurns.ease(0.5f), 1e-6f)
    }
}

class PlaylistExtrasTest {
    private val day = 86_400_000L
    private val now = 1000 * day
    private fun items(n: Int, featured: Set<String> = emptySet()) =
        (1..n).map { PlaylistEntry("p$it", receivedAt = now - it * day, takenAt = null, featured = "p$it" in featured) }

    @Test fun upcomingAndConsumeForCollages() {
        val p = Playlist(1) { now }
        p.update(items(6), PlayOrder.RECEIVED, false)
        assertEquals("p1", p.next())
        assertEquals(listOf("p2", "p3", "p4"), p.upcoming(3))
        p.consume("p2"); p.consume("p3")
        assertEquals("p4", p.next())
        assertEquals(listOf("p5", "p6"), p.upcoming(5))
    }

    @Test fun onThisDayPlaysFirstOncePerDay() {
        val p = Playlist(1) { now }
        p.update(items(5, featured = setOf("p4")), PlayOrder.RECEIVED, false)
        assertEquals(listOf("p4", "p1", "p2", "p3", "p5"), List(5) { p.next() })
        p.update(items(5, featured = setOf("p4")), PlayOrder.RECEIVED, false)
        assertNotEquals("p4", p.next())
    }

    @Test fun onThisDayDates() {
        val z = ZoneId.of("Europe/Brussels")
        val today = LocalDate.of(2026, 10, 22)
        fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(15, 0).atZone(z).toInstant().toEpochMilli()
        assertEquals(3, OnThisDay.yearsAgo(ms(2023, 10, 22), today, z))
        assertNull(OnThisDay.yearsAgo(ms(2026, 10, 22), today, z))
        assertNull(OnThisDay.yearsAgo(ms(2023, 10, 21), today, z))
        assertNull(OnThisDay.yearsAgo(null, today, z))
        assertEquals(2, OnThisDay.yearsAgo(ms(2024, 2, 29), LocalDate.of(2026, 2, 28), z))
    }

    @Test fun videoPolicy() {
        assertFalse(VideoPolicy.muted(null, Long.MAX_VALUE))
        assertFalse(VideoPolicy.muted(10, 9 * 60_000L))
        assertTrue(VideoPolicy.muted(10, 10 * 60_000L))
        assertTrue(VideoPolicy.due("once_advance", ended = true, elapsedMs = 0, intervalMs = 30_000))
        assertFalse(VideoPolicy.due("once_hold", ended = true, elapsedMs = 10_000, intervalMs = 30_000))
        assertTrue(VideoPolicy.due("loop", ended = true, elapsedMs = 30_000, intervalMs = 30_000))
        assertFalse(VideoPolicy.due("once_hold", ended = false, elapsedMs = 90_000, intervalMs = 30_000))
    }

    @Suppress("unused") private fun unused(p: PlacementEntity) = p
}

/** Renders collages and smart pairs with [SlideRenderer.renderTiles] to `build/screenshots/`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class CollageScreenshotTest {
    @get:Rule val tmp = TemporaryFolder()
    private val palettes = listOf(
        intArrayOf(0xFF9CCBF0.toInt(), 0xFF55804A.toInt()), intArrayOf(0xFFE39A6B.toInt(), 0xFF3A2A2A.toInt()),
        intArrayOf(0xFFF3D9B1.toInt(), 0xFF6B3F2F.toInt()), intArrayOf(0xFFBFD8D2.toInt(), 0xFF1F3B36.toInt()),
        intArrayOf(0xFFF6C1CF.toInt(), 0xFF7A3E5A.toInt()), intArrayOf(0xFFFFE08A.toInt(), 0xFF8A5A12.toInt()),
    )

    private fun photo(i: Int, w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = palettes[i % palettes.size]
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p[0], p[1], Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.5f, h * 0.42f, minOf(w, h) * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) })
        c.drawText("${i + 1}", w * 0.47f, h * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p[1]; textSize = minOf(w, h) * 0.15f })
        val f = tmp.newFile("p$i.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun render(name: String, w: Int, h: Int, sizes: List<Pair<Int, Int>>, labels: Boolean = true) {
        val files = sizes.mapIndexed { i, (pw, ph) -> photo(i, pw, ph) }
        val placed = Collage.place(sizes.map { it.first.toFloat() / it.second }, w, h)
        val names = listOf("Anna · Yesterday", "Tom · 3 days ago", "Oma · Today", "Jeroen · Today", "Lies · Last week", "Pieter · Today")
        val tiles = placed.cells.mapIndexed { i, cell ->
            val k = placed.order[i]
            SlideRenderer.TileRequest(files[k], 0, null, null, cell, label = names[k].takeIf { labels }, caption = "Sunday at the beach".takeIf { labels && k == 0 })
        }
        val out = SlideRenderer().renderTiles(tiles, w, h, 1f)
        val f = File("build/screenshots/$name.png")
        f.parentFile!!.mkdirs()
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val P = 600 to 900
    private val L = 900 to 600

    @Test fun pairLandscape() = render("pair_portraits_landscape", 1280, 800, listOf(P, P))
    @Test fun pairPortrait() = render("pair_landscapes_portrait", 800, 1280, listOf(L, L))
    @Test fun collage3Landscape() = render("collage3_landscape", 1280, 800, listOf(L, P, L))
    @Test fun collage4Landscape() = render("collage4_landscape", 1280, 800, listOf(L, P, L, L), labels = false)
    @Test fun collage5Landscape() = render("collage5_landscape", 1280, 800, listOf(L, P, L, P, L))
    @Test fun collage6Landscape() = render("collage6_landscape", 1280, 800, listOf(L, P, L, P, L, L), labels = false)
    @Test fun collage3Portrait() = render("collage3_portrait", 800, 1280, listOf(P, L, P))
    @Test fun collage5Portrait() = render("collage5_portrait", 800, 1280, listOf(P, L, P, P, L))
    @Test fun collage6Portrait() = render("collage6_portrait", 800, 1280, listOf(P, L, P, P, L, P), labels = false)
}
