package app.kinframe.frame.slideshow

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.robolectric.RuntimeEnvironment
import app.kinframe.frame.Locales
import org.junit.Assert.assertEquals
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

/**
 * The caption credit of a photo another member shared from a different frame (README §5.3): "Anna · shared by Ben",
 * localized and bidi-safe, in a single slide and a collage tile. Screenshots go to `build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SharedByCaptionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ctx(lang: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val locale = Locales.toLocale(lang)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        return base.createConfigurationContext(config)
    }

    /** Without bidi controls, to compare the words. */
    private fun plain(s: String?) = s?.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }

    @Test fun creditLine() {
        assertEquals("Anna", Credit.line(ctx("en"), "Anna", null))
        assertEquals("Anna", Credit.line(ctx("en"), "Anna", "  "))
        assertEquals("Anna · shared by Ben", plain(Credit.line(ctx("en"), "Anna", "Ben")))
        assertEquals("Anna · gedeeld door Ben", plain(Credit.line(ctx("nl"), "Anna", "Ben")))
        // The sender sharing their own photo again is just the sender.
        assertEquals("Anna", Credit.line(ctx("en"), "Anna", "Anna"))
        assertNull(Credit.line(ctx("en"), null, "Ben"))
        // Right to left: the Latin names are isolated so the separator stays between them.
        val ar = Credit.line(ctx("ar"), "Anna", "Ben")!!
        assertTrue(ar, ar.contains("شاركها") && ar.length > plain(ar)!!.length)
    }

    private fun photo(w: Int, h: Int, top: Int, bottom: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        val f = tmp.newFile("p${System.nanoTime()}.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun save(name: String, b: Bitmap) {
        val f = File("build/screenshots/$name.png")
        f.parentFile!!.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
    }

    @Test fun captionWithSharedByCredit() {
        for (lang in listOf("en", "nl", "ar")) {
            val c = ctx(lang)
            val meta = listOfNotNull(Credit.line(c, "Oma Maria", "Jeroen"), "Yesterday").joinToString(" · ")
            val request = SlideRenderer.Request(
                photo(1600, 1000, 0xFF9CCBF0.toInt(), 0xFF55804A.toInt()), isVideo = false, screenW = 1024, screenH = 600,
                fill = true, focalX = null, focalY = null, rotation = 0, blurBars = true,
                caption = SlideRenderer.CaptionSpec(
                    text = "Sunday at the beach", meta = meta, avatar = null, initials = "OM", avatarColor = Avatars.color("oma"),
                ),
                density = 1f,
            )
            save("caption_shared_by_$lang", SlideRenderer().render(request))
        }
    }

    @Test fun collageTileWithSharedByCredit() {
        val c = ctx("en")
        val placed = Collage.place(listOf(1.5f, 1.5f), 1024, 600)
        val labels = listOf(Credit.line(c, "Oma Maria", "Jeroen") + " · Yesterday", "Tom · Today")
        val tiles = placed.cells.mapIndexed { i, cell ->
            val k = placed.order[i]
            SlideRenderer.TileRequest(photo(900, 600, 0xFFF3D9B1.toInt() + k, 0xFF6B3F2F.toInt()), 0, null, null, cell, label = labels[k])
        }
        save("collage_shared_by_en", SlideRenderer().renderTiles(tiles, 1024, 600, 1f))
    }
}
