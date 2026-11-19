package app.kinframe.frame.birthdays

import java.time.LocalDate

/**
 * When the balloons run (docs/spec/birthdays.md §6.4): once when the birthday card first appears on an awake screen
 * that day, then once an hour while it stays visible, aligned to that first run (10:12, 11:12, …).
 *
 * - The first run waits (up to an hour) while something busy is on screen, e.g. a video; hourly runs are skipped,
 *   not queued: one that cannot start within [GRACE_MS] of its slot moves to the next hour.
 * - A screen that wakes counts as a first appearance again only if no balloons ran in the last hour.
 * - A new local date starts over (midnight on a screen that is on; a card that appears when the clock becomes
 *   trusted or the list arrives counts as a first appearance).
 *
 * Pure: the slideshow calls [tick] with the current state and starts the balloons when it answers true.
 */
class BalloonPlanner {
    private var date: LocalDate? = null
    private var visible = false
    /** Start of the hourly rhythm; null until the first run of the day. */
    private var anchor: Long? = null
    /** A first appearance waiting for a moment the balloons may run. */
    private var pendingSince: Long? = null
    private var lastSlot = 0L
    private var lastRunAt = Long.MIN_VALUE / 2

    /**
     * [cardShown]: today has a birthday and the clock is trusted. [awake]: the screen is on (not asleep, fading or
     * dimmed). [canRun]: nothing busy is on screen (video, menus, cards, a transition).
     */
    fun tick(nowMs: Long, today: LocalDate?, cardShown: Boolean, awake: Boolean, canRun: Boolean): Boolean {
        if (today != date) {
            date = today
            visible = false
            anchor = null
            pendingSince = null
            lastSlot = 0
        }
        val nowVisible = today != null && cardShown && awake
        if (!nowVisible) {
            visible = false
            return false
        }
        if (!visible) {
            visible = true
            // A first appearance: the first of the day, or a wake-up more than an hour after the last run.
            if (anchor == null || nowMs - lastRunAt >= HOUR_MS) {
                pendingSince = nowMs
                anchor = null
            }
        }
        pendingSince?.let { since ->
            if (nowMs - since > HOUR_MS) {
                pendingSince = null
                return false
            }
            if (!canRun) return false
            pendingSince = null
            anchor = nowMs
            lastSlot = 0
            lastRunAt = nowMs
            return true
        }
        val a = anchor ?: return false
        val slot = (nowMs - a) / HOUR_MS
        if (slot <= lastSlot) return false
        val late = nowMs - (a + slot * HOUR_MS)
        if (late > GRACE_MS) {
            lastSlot = slot
            return false
        }
        if (!canRun) return false
        lastSlot = slot
        lastRunAt = nowMs
        return true
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        /** How long an hourly run may wait for a transition or a short overlay to finish. */
        const val GRACE_MS = 2 * 60_000L
        /** How often the slideshow asks while a card is shown. */
        const val TICK_MS = 10_000L
        const val DURATION_MS = 6_000
    }
}
