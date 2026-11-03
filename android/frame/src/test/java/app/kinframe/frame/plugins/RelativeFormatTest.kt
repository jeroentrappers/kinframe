package app.kinframe.frame.plugins

import app.kinframe.core.plugin.PluginFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** `ctx.format.day`, `ago` and `until` (docs/plugins.md §3.2) with the frame's real translations. */
class RelativeFormatTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun f(tag: String, zone: ZoneId = brussels) = PluginFormat(Locale.forLanguageTag(tag), zone, words = FrameStrings.relativeWords(tag))

    @Test fun everyLanguageHasAllTheWords() {
        val keys = listOf("today", "tomorrow", "yesterday", "just_now", "now", "min_ago", "h_ago", "in_min", "in_h")
        FrameStrings.locales.forEach { loc ->
            val s = FrameStrings.strings(loc)
            keys.forEach { k ->
                val v = s["plugin_rel_$k"]
                assertTrue("$loc lacks plugin_rel_$k", !v.isNullOrBlank())
                if (k.endsWith("_ago") || k.startsWith("in_")) assertTrue("$loc plugin_rel_$k has no {n}: $v", v!!.contains("{n}"))
            }
        }
    }

    @Test fun daysAroundToday() {
        val now = at("2026-10-27T10:00:00Z") // Tuesday
        val nl = f("nl")
        assertEquals("Vandaag", nl.day(at("2026-10-27T21:00:00Z"), now))
        assertEquals("Morgen", nl.day(at("2026-10-28T06:00:00Z"), now))
        assertEquals("Gisteren", nl.day(at("2026-10-26T06:00:00Z"), now))
        assertEquals("Vrijdag", nl.day(at("2026-10-30T12:00:00Z"), now))
        assertEquals("Friday", f("en").day(at("2026-10-30T12:00:00Z"), now))
        assertEquals("Vendredi", f("fr").day(at("2026-10-30T12:00:00Z"), now))
        assertEquals("Freitag", f("de").day(at("2026-10-30T12:00:00Z"), now))
        assertEquals("金曜日", f("ja").day(at("2026-10-30T12:00:00Z"), now))
        // A week or more away: a short date without the year, in the language's own order.
        assertTrue(nl.day(at("2026-11-12T12:00:00Z"), now).startsWith("12 nov"))
        assertEquals("Nov 12", f("en").day(at("2026-11-12T12:00:00Z"), now))
        assertEquals("12.11.", f("de").day(at("2026-11-12T12:00:00Z"), now))
        assertTrue(f("ja").day(at("2026-11-12T12:00:00Z"), now).let { it.contains("11") && it.contains("12") && !it.contains("2026") })
    }

    @Test fun midnightIsTheFramesNotUtc() {
        // 23:30 in Brussels is already the next day there, but still the same day in UTC.
        val now = at("2026-10-27T22:30:00Z") // 23:30 CET on the 27th
        val nl = f("nl")
        assertEquals("Vandaag", nl.day(at("2026-10-27T22:50:00Z"), now))
        assertEquals("Morgen", nl.day(at("2026-10-27T23:10:00Z"), now)) // 00:10 on the 28th in Brussels
        // The same instants seen from New York: both still the 27th.
        assertEquals("Today", f("en", ZoneId.of("America/New_York")).day(at("2026-10-27T23:10:00Z"), now))
    }

    @Test fun overTheClockChange() {
        // Sunday 25 October 2026, 03:00 CEST → 02:00 CET: that day has 25 hours. Monday is still "tomorrow".
        val now = at("2026-10-24T22:30:00Z") // Sun 00:30 CEST
        val nl = f("nl")
        assertEquals("Vandaag", nl.day(at("2026-10-25T22:59:00Z"), now)) // Sun 23:59 CET
        assertEquals("Morgen", nl.day(at("2026-10-25T23:01:00Z"), now)) // Mon 00:01 CET
        // And in spring (29 March 2026, 23 hours).
        val spring = at("2026-03-28T23:30:00Z") // Sun 00:30 CET
        assertEquals("Vandaag", nl.day(at("2026-03-29T21:59:00Z"), spring)) // Sun 23:59 CEST
        assertEquals("Morgen", nl.day(at("2026-03-29T22:01:00Z"), spring))
    }

    @Test fun ago() {
        val now = at("2026-10-27T15:00:00Z")
        val nl = f("nl")
        assertEquals("zonet", nl.ago(now - 20_000, now))
        assertEquals("zonet", nl.ago(now + 5_000, now)) // a clock a little ahead is not "in the future"
        assertEquals("8 min geleden", nl.ago(now - 8 * 60_000 - 30_000, now))
        assertEquals("3 u geleden", nl.ago(now - 3 * 3_600_000 - 60_000, now))
        assertEquals("Gisteren", nl.ago(at("2026-10-26T15:00:00Z"), now))
        assertEquals("Zaterdag", nl.ago(at("2026-10-24T09:00:00Z"), now))
        assertEquals("8 min ago", f("en").ago(now - 8 * 60_000, now))
        assertEquals("il y a 8 min", f("fr").ago(now - 8 * 60_000, now))
        assertEquals("vor 3 Std.", f("de").ago(now - 3 * 3_600_000, now))
        assertEquals("8分前", f("ja").ago(now - 8 * 60_000, now))
    }

    @Test fun agoLateLastNightIsHoursNotYesterday() {
        // 01:00 now, 23:00 yesterday: two hours ago reads better than "yesterday".
        val now = at("2026-10-27T00:00:00Z") // 01:00 CET
        assertEquals("2 u geleden", f("nl").ago(at("2026-10-26T22:00:00Z"), now))
        // But 20 hours ago on the previous day is "yesterday".
        assertEquals("Gisteren", f("nl").ago(at("2026-10-26T04:00:00Z"), now))
    }

    @Test fun until() {
        val now = at("2026-10-27T15:00:00Z")
        val nl = f("nl")
        assertEquals("nu", nl.until(now + 10_000, now))
        assertEquals("over 5 min", nl.until(now + 4 * 60_000 + 10_000, now)) // rounds up: the bus isn't early
        assertEquals("over 2 u", nl.until(now + 2 * 3_600_000, now))
        assertEquals("Morgen", nl.until(at("2026-10-28T07:00:00Z"), now))
        assertEquals("in 5 min", f("en").until(now + 5 * 60_000, now))
        assertEquals("dans 5 min", f("fr").until(now + 5 * 60_000, now))
        assertEquals("5分後", f("ja").until(now + 5 * 60_000, now))
    }
}
