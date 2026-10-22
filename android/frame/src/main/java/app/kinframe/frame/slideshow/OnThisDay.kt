package app.kinframe.frame.slideshow

import app.kinframe.frame.data.PlacementEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "On this day" memories (settings `on_this_day`): photos taken on today's date in an earlier year. */
object OnThisDay {
    fun ids(items: List<PlacementEntity>, today: LocalDate, zone: ZoneId): Set<String> =
        items.filter { yearsAgo(it.capturedAt, today, zone) != null }.mapTo(HashSet()) { it.id }

    /** Whole years since [takenMs] when it was taken on [today]'s month and day (29 Feb counts on 28 Feb), else null. */
    fun yearsAgo(takenMs: Long?, today: LocalDate, zone: ZoneId): Int? {
        val d = Instant.ofEpochMilli(takenMs ?: return null).atZone(zone).toLocalDate()
        if (d.year >= today.year) return null
        val sameDay = d.monthValue == today.monthValue && (
            d.dayOfMonth == today.dayOfMonth ||
                (d.monthValue == 2 && d.dayOfMonth == 29 && today.dayOfMonth == 28 && !today.isLeapYear)
            )
        return if (sameDay) today.year - d.year else null
    }
}
