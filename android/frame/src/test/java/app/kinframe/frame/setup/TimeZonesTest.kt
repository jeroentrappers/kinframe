package app.kinframe.frame.setup

import android.app.Application
import app.kinframe.frame.Paper
import app.kinframe.frame.Screenshots.shot
import app.kinframe.frame.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

class TimeZonesTest {
    private val october = Instant.parse("2026-10-22T12:00:00Z")
    private val january = Instant.parse("2026-01-15T12:00:00Z")

    @Test fun suggestsTheFirmwareZoneThenTheCountry() {
        assertEquals(listOf("Europe/Amsterdam", "Europe/Brussels"), TimeZones.suggest("Europe/Amsterdam", "BE"))
        assertEquals(listOf("Europe/Amsterdam", "Europe/Brussels"), TimeZones.suggest("Europe/Amsterdam", "NL"))
        // UTC and Etc/ zones are firmware defaults, not choices.
        assertEquals(listOf("Europe/Paris", "Europe/Brussels"), TimeZones.suggest("UTC", "fr"))
        assertEquals(listOf("Europe/Brussels"), TimeZones.suggest("Etc/GMT+1", null))
        assertEquals(listOf("Asia/Kolkata", "Europe/Brussels"), TimeZones.suggest("Asia/Calcutta", "IN"))
        assertEquals(listOf("Europe/Brussels"), TimeZones.suggest("Not/AZone", "XX"))
    }

    @Test fun realZones() {
        assertTrue(TimeZones.isReal("America/Argentina/Buenos_Aires"))
        listOf("UTC", "GMT", "Etc/UTC", "SystemV/EST5", "Nowhere/Town", "EST").forEach { assertFalse(it, TimeZones.isReal(it)) }
    }

    @Test fun regionListsAreSortedByOffsetAndUseSummerTime() {
        val europe = TimeZones.zonesIn("Europe", october)
        assertTrue(europe.size > 30)
        assertEquals(europe.sortedBy { it.offsetSeconds }.map { it.offsetSeconds }, europe.map { it.offsetSeconds })
        assertEquals(7200, europe.first { it.id == "Europe/Brussels" }.offsetSeconds)
        assertEquals(3600, TimeZones.zone("Europe/Brussels", january).offsetSeconds)
        assertTrue(europe.none { it.id.startsWith("Etc/") })
        assertEquals("Buenos Aires", TimeZones.city("America/Argentina/Buenos_Aires"))
    }

    @Test fun offsetLabels() {
        assertEquals("UTC", TimeZones.offsetLabel(0))
        assertEquals("UTC+2", TimeZones.offsetLabel(7200))
        assertEquals("UTC+5:30", TimeZones.offsetLabel(19800))
        assertEquals("UTC−3:30", TimeZones.offsetLabel(-12600))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class SetupStepsScreenshotTest {
    private val now = Instant.parse("2026-10-22T12:20:00Z").toEpochMilli()

    @Test fun timeZoneLandscape() = shot("setup_timezone_landscape") {
        Paper()
        TimeZonePicker(listOf("Europe/Amsterdam", "Europe/Brussels"), "Europe/Brussels", now, {}, {}, {})
    }

    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi")
    fun timeZonePortraitDutch() = shot("setup_timezone_portrait_nl") {
        Paper()
        TimeZonePicker(listOf("Europe/Amsterdam", "Europe/Brussels"), "Europe/Amsterdam", now, {}, {}, {})
    }

    @Test fun updateDownloading() = shot("setup_update_downloading") {
        Paper()
        UpdateStepView(UpdateManager.Phase.Downloading, "0.3.0", 0.42f) {}
    }

    @Test fun updateFailed() = shot("setup_update_failed") {
        Paper()
        UpdateStepView(UpdateManager.Phase.Failed, null, null) {}
    }
}
