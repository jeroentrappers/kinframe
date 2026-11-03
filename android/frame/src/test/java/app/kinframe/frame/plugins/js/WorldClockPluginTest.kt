package app.kinframe.frame.plugins.js

import app.kinframe.core.plugin.ListView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** `kinframe.world-clock`: the frame does the time zone arithmetic (docs/plugins.md §9.8). */
class WorldClockPluginTest {
    private val kits = ArrayList<JsPluginKit>()
    @After fun down() = kits.forEach { it.close() }

    private fun kit(vararg settings: Pair<String, Any>, language: String = "nl", at: String = "2026-10-27T22:30:00Z"): JsPluginKit =
        JsPluginKit(JsPluginKit.plugin("kinframe.world-clock"),
            JsonObject(settings.associate { (k, v) -> k to (if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }), language)
            .also { it.now = Instant.parse(at).toEpochMilli(); kits += it }

    @Test fun placesWithTheirDayAndDifference() = runBlocking {
        // 23:30 in Brussels (CET, the clocks went back on the 25th).
        val k = kit("city_1" to "Asia/Tokyo", "city_2" to "America/New_York", "name_2" to "Anna", "city_3" to "Asia/Kolkata", "city_4" to "Europe/Brussels")
        val v = k.view() as ListView
        assertEquals(listOf("Tokyo", "Anna", "Kolkata", "Brussels"), v.rows.map { it.title })
        assertEquals(listOf("07:30", "18:30", "04:00", "23:30"), v.rows.map { it.time })
        assertEquals("Morgen · +8 h", v.rows[0].detail)
        assertEquals("−5 h", v.rows[1].detail)
        assertEquals("Morgen · +4:30", v.rows[2].detail)
        assertNull("the frame's own time needs no detail", v.rows[3].detail)
        assertEquals(listOf("sun", "sun", "moon", "moon"), v.rows.map { it.icon })
        // The detail card shows the same places.
        assertEquals(4, (k.detail() as ListView).rows.size)
    }

    @Test fun yesterdayAndTwelveHourClocksFollowTheFrame() = runBlocking {
        val k = kit("city_1" to "Pacific/Honolulu", "show_difference" to false, language = "en", at = "2026-10-27T08:15:00Z")
        val row = (k.view() as ListView).rows.single()
        assertEquals("Honolulu", row.title)
        assertEquals("Yesterday", row.detail)
        assertTrue(row.time!!, row.time!!.startsWith("22:15") || row.time!!.contains("10:15"))
    }

    @Test fun noPlaceShowsNothingAndAnUnknownZoneIsAnError() = runBlocking {
        assertNull(kit().render())
        val bad = kit("city_1" to "Mars/Olympus_Mons")
        val e = runCatching { bad.render() }.exceptionOrNull()
        assertTrue(e?.message ?: "none", e?.message?.contains("unknown time zone") == true)
    }
}
