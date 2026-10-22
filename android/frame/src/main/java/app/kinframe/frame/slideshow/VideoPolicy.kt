package app.kinframe.frame.slideshow

/** Video playback rules that don't need a player (unit-tested). */
object VideoPolicy {
    /** Auto-mute: on after [autoMuteMin] minutes without a touch; null or 0 = never. */
    fun muted(autoMuteMin: Int?, idleMs: Long): Boolean = autoMuteMin != null && autoMuteMin > 0 && idleMs >= autoMuteMin * 60_000L

    /** When a video slide may advance: [ended] once played through (loop: one full play), plus the interval unless `once_advance`. */
    fun due(playback: String, ended: Boolean, elapsedMs: Long, intervalMs: Long): Boolean = when (playback) {
        "once_advance" -> ended
        else -> ended && elapsedMs >= intervalMs
    }
}
