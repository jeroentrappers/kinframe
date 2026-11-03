package app.kinframe.family

import app.kinframe.family.ui.Avatars
import app.kinframe.family.util.Locales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Account settings helpers: the language list and the square profile photo. */
class AccountTest {
    @Test fun languagesMatchTheWebList() {
        assertEquals(36, Locales.NATIVE.size)
        assertEquals("nl", Locales.match("nl-BE"))
        assertEquals("zh-TW", Locales.match("zh-Hant-TW"))
        assertEquals("zh-CN", Locales.match("zh_CN"))
        assertEquals("nb", Locales.match("no"))
        assertEquals("pt", Locales.match("pt-BR"))
        assertNull(Locales.match("xx"))
        assertNull(Locales.match(null))
    }

    @Test fun profilePhotosAreTheCentredSquare() {
        assertEquals(Triple(500, 0, 3000), Avatars.crop(4000, 3000))
        assertEquals(Triple(0, 250, 1000), Avatars.crop(1000, 1500))
        assertEquals(Triple(0, 0, 256), Avatars.crop(256, 256))
    }
}
