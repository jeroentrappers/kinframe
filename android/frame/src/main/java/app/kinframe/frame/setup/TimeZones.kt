package app.kinframe.frame.setup

import java.time.Instant
import java.time.ZoneId

/**
 * Time-zone choice for the setup wizard and settings, without asking anyone on the internet where the frame is
 * (no IP geolocation: it would need a third party). The suggestion is the system zone when the firmware set a real
 * one (the reference frame ships with Europe/Amsterdam), else the main zone of the locale's country.
 */
object TimeZones {
    data class Zone(val id: String, val city: String, val region: String, val offsetSeconds: Int)

    val regions = listOf("Europe", "America", "Asia", "Africa", "Australia", "Pacific", "Atlantic", "Indian", "Antarctica")

    /** Main zone per country (ISO 3166 alpha-2) for the locales a frame is likely set to. */
    private val countryZones = mapOf(
        "NL" to "Europe/Amsterdam", "BE" to "Europe/Brussels", "LU" to "Europe/Luxembourg", "DE" to "Europe/Berlin",
        "FR" to "Europe/Paris", "GB" to "Europe/London", "IE" to "Europe/Dublin", "ES" to "Europe/Madrid",
        "PT" to "Europe/Lisbon", "IT" to "Europe/Rome", "CH" to "Europe/Zurich", "AT" to "Europe/Vienna",
        "DK" to "Europe/Copenhagen", "SE" to "Europe/Stockholm", "NO" to "Europe/Oslo", "FI" to "Europe/Helsinki",
        "PL" to "Europe/Warsaw", "CZ" to "Europe/Prague", "SK" to "Europe/Bratislava", "HU" to "Europe/Budapest",
        "SI" to "Europe/Ljubljana", "HR" to "Europe/Zagreb", "RS" to "Europe/Belgrade", "RO" to "Europe/Bucharest",
        "BG" to "Europe/Sofia", "GR" to "Europe/Athens", "TR" to "Europe/Istanbul", "UA" to "Europe/Kyiv",
        "LT" to "Europe/Vilnius", "LV" to "Europe/Riga", "EE" to "Europe/Tallinn", "RU" to "Europe/Moscow",
        "IL" to "Asia/Jerusalem", "AE" to "Asia/Dubai", "SA" to "Asia/Riyadh", "EG" to "Africa/Cairo", "IN" to "Asia/Kolkata",
        "TH" to "Asia/Bangkok", "VN" to "Asia/Ho_Chi_Minh", "ID" to "Asia/Jakarta", "CN" to "Asia/Shanghai",
        "TW" to "Asia/Taipei", "JP" to "Asia/Tokyo", "KR" to "Asia/Seoul", "US" to "America/New_York",
        "CA" to "America/Toronto", "MX" to "America/Mexico_City", "BR" to "America/Sao_Paulo", "AR" to "America/Argentina/Buenos_Aires",
        "AU" to "Australia/Sydney", "NZ" to "Pacific/Auckland", "ZA" to "Africa/Johannesburg", "MA" to "Africa/Casablanca",
    )

    /** Up to three suggestions, best first, without duplicates. Unknown or UTC-like system zones are ignored. */
    fun suggest(systemZone: String?, country: String?, fallback: String = "Europe/Brussels"): List<String> = buildList {
        systemZone?.takeIf { isReal(it) }?.let { add(canonical(it)) }
        country?.uppercase()?.let { countryZones[it] }?.let { add(it) }
        add(fallback)
    }.distinct().filter { isValid(it) }.take(3)

    /** True for a zone a person would pick (not UTC, GMT, Etc/…, SystemV/…, or an unknown id). */
    fun isReal(id: String): Boolean {
        if (!isValid(id)) return false
        val u = id.uppercase()
        return u != "UTC" && u != "GMT" && !u.startsWith("ETC/") && !u.startsWith("SYSTEMV/") && '/' in id
    }

    fun isValid(id: String): Boolean = try { ZoneId.of(id); true } catch (e: Exception) { false }

    /** Older names the frame might find in its firmware map to the current ones. */
    private fun canonical(id: String) = when (id) {
        "Europe/Kiev" -> "Europe/Kyiv".takeIf { isValid(it) } ?: id
        "Asia/Calcutta" -> "Asia/Kolkata"
        "Asia/Saigon" -> "Asia/Ho_Chi_Minh"
        else -> id
    }

    /** All pickable zones of [region], sorted by current UTC offset then city. */
    fun zonesIn(region: String, at: Instant, available: Set<String> = ZoneId.getAvailableZoneIds()): List<Zone> =
        available.asSequence()
            .filter { it.startsWith("$region/") && isReal(it) }
            .mapNotNull { id -> runCatching { zone(id, at) }.getOrNull() }
            .sortedWith(compareBy<Zone> { it.offsetSeconds }.thenBy { it.city })
            .toList()

    fun zone(id: String, at: Instant): Zone {
        val offset = ZoneId.of(id).rules.getOffset(at).totalSeconds
        return Zone(id, city(id), id.substringBefore('/'), offset)
    }

    /** "America/Argentina/Buenos_Aires" → "Buenos Aires". */
    fun city(id: String): String = id.substringAfterLast('/').replace('_', ' ')

    /** "UTC+1", "UTC−3:30", "UTC" (with a real minus sign). */
    fun offsetLabel(seconds: Int): String {
        if (seconds == 0) return "UTC"
        val sign = if (seconds > 0) "+" else "−"
        val abs = kotlin.math.abs(seconds)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${m.toString().padStart(2, '0')}"
    }
}
