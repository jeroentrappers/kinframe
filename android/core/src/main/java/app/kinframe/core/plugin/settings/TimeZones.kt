package app.kinframe.core.plugin.settings

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A time zone as the `timezone` setting's picker shows it: "New York", "America · UTC−4". */
data class TimeZoneOption(
    /** The IANA id, "America/New_York": what is stored. */
    val id: String,
    /** The last part of the id, underscores as spaces: "New York". */
    val city: String,
    /** The parts before it: "America", "America / Argentina"; empty for "UTC". */
    val region: String,
    /** The offset from UTC at the moment the option was made, or null for an id this device doesn't know. */
    val offsetSeconds: Int?,
) {
    /** "UTC−4", "UTC+5:30", "UTC"; null when unknown. */
    val offset: String? get() = offsetSeconds?.let(TimeZones::formatOffset)

    /** The line under the city: "America · UTC−4". */
    val detail: String get() = listOfNotNull(region.takeIf { it.isNotEmpty() }, offset).joinToString(" · ")
}

/** The `timezone` setting type (docs/plugins.md §3.1): an IANA time zone id such as `Asia/Tokyo`. */
object TimeZones {
    private val available: Set<String> by lazy { ZoneId.getAvailableZoneIds() }

    /** The ids the picker offers: region ids ("Europe/Brussels"), not `Etc/GMT+5`, `SystemV/…` or bare aliases ("EST"). */
    val pickable: List<String> by lazy { available.filter(::isRegionId).sorted() }

    fun isRegionId(id: String): Boolean = '/' in id && !id.startsWith("Etc/") && !id.startsWith("SystemV/")

    /** Whether [id] is a zone id this device knows (any of them, also `UTC` or an alias a stored value may hold). */
    fun known(id: String): Boolean = id in available

    fun city(id: String): String = id.substringAfterLast('/').replace('_', ' ')

    fun region(id: String): String = id.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.joinToString(" / ") { it.replace('_', ' ') }

    /** "UTC−4" (a real minus sign), "UTC+5:30", "UTC+5:45", "UTC" for no offset. */
    fun formatOffset(seconds: Int): String {
        if (seconds == 0) return "UTC"
        val sign = if (seconds < 0) "−" else "+"
        val abs = kotlin.math.abs(seconds) / 60
        val h = abs / 60
        val m = abs % 60
        return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${m.toString().padStart(2, '0')}"
    }

    /** [id] as the picker shows it at [now]; an unknown id still gets its city and region, without an offset. */
    fun option(id: String, now: Instant = Instant.now()): TimeZoneOption {
        val offset = if (known(id)) runCatching { ZoneId.of(id).rules.getOffset(now).totalSeconds }.getOrNull() else null
        return TimeZoneOption(id, city(id), region(id), offset)
    }

    /** Every pickable zone at [now], by city. */
    fun options(now: Instant = Instant.now()): List<TimeZoneOption> =
        pickable.map { option(it, now) }.sortedWith(compareBy<TimeZoneOption>({ it.city.lowercase(Locale.ROOT) }, { it.id }))

    /** The options matching every word of [query] in the city, the region or the id ("new york", "tokyo", "america/"). */
    fun search(options: List<TimeZoneOption>, query: String): List<TimeZoneOption> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return options
        return options.filter { o ->
            val hay = "${o.city} ${o.region} ${o.id} ${o.id.replace('_', ' ')}".lowercase(Locale.ROOT)
            words.all { hay.contains(it) }
        }
    }
}
