package app.kinframe.core.plugin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.kinframe.core.design.KinColors
import app.kinframe.core.design.KinPalettes
import app.kinframe.core.plugin.Tone
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tone stays readable as a graphic (WCAG 1.4.11, 3:1) where it is drawn: on the frame's paper and the detail
 * card, on the dark palette, and on photos (white-ish tones with a shadow; checked against a mid-dark photo tone).
 * The colour-vision check of the quantity tones is in docs/plugins.md §4.3.
 */
class PluginPaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun check(name: String, p: PluginPalette, backgrounds: List<Color>, tones: List<Tone>) {
        for (t in tones) for (bg in backgrounds) {
            val c = contrast(p.tone(t), bg)
            assertTrue("$name ${t.json} on $bg: ${"%.2f".format(c)}", c >= 3f)
        }
    }

    private val semantic = listOf(Tone.Good, Tone.Warn, Tone.Bad, Tone.Info)
    private val quantities = listOf(Tone.Water, Tone.Gas, Tone.Heat)

    @Test fun paper() {
        val c = KinColors()
        check("paper", PluginPalette.onPaper(c), listOf(c.paper, c.surface), semantic + quantities)
    }

    @Test fun dark() {
        val c = KinPalettes.dark
        check("dark", PluginPalette.onPaper(c), listOf(c.paper, c.surface), semantic + quantities)
    }

    @Test fun onPhotos() {
        // The test photo's sage (the darkest "mid" tone the screenshots use) and a dark evening photo.
        check("photo", PluginPalette.OnPhoto, listOf(Color(0xFF5B6B5E), Color(0xFF2B2A33)), quantities)
    }

    @Test fun everyToneHasItsOwnColour() {
        listOf(PluginPalette.onPaper(KinColors()), PluginPalette.onPaper(KinPalettes.dark), PluginPalette.OnPhoto).forEach { p ->
            val colours = Tone.entries.map { p.tone(it) }
            assertTrue(colours.toString(), colours.toSet().size == colours.size)
        }
    }
}
