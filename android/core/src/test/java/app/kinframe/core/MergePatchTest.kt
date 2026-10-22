package app.kinframe.core

import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MergePatch
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MergePatchTest {
    @Test
    fun rfc7396Semantics() {
        val target = KinJson.parseToJsonElement("""{"a":"b","c":{"d":"e","f":"g"},"keep":1}""")
        val patch = KinJson.parseToJsonElement("""{"a":"z","c":{"f":null},"n":[1,2]}""")
        assertEquals(
            KinJson.parseToJsonElement("""{"a":"z","c":{"d":"e"},"keep":1,"n":[1,2]}"""),
            MergePatch.apply(target, patch),
        )
    }

    @Test
    fun settingsDefaultsAndUnknownFields() {
        val raw = KinJson.parseToJsonElement("""{"version":7,"slideshow":{"interval_s":10,"future_field":true},"brand_new":{}}""").jsonObject
        val s = FrameSettings.parse(raw)
        assertEquals(7, s.version)
        assertEquals(10, s.slideshow.intervalS)
        assertEquals(800, s.slideshow.transitionMs)
        assertEquals(204, s.display.brightness)
        assertEquals("23:00", s.display.sleepSchedules.single().sleepAt)
        assertEquals(FrameSettings.DEFAULT, FrameSettings.parse(null))
    }
}
