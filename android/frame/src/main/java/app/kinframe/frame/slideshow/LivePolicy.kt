package app.kinframe.frame.slideshow

import app.kinframe.core.api.LivePhotos

/**
 * Live/Motion Photo playback rules (settings `slideshow.live_photos`), kept free of the player so they are unit-tested.
 *
 * A live slide shows its still. With `on` the clip plays once (muted unless the sender kept its sound) when the slide
 * has appeared, then crossfades back to the still; a tap plays it again. With `tap` only a tap plays it, with `off`
 * never. The slide's interval starts when the clip has finished, so the still always gets its full time. Clips play
 * only when the photo is alone on screen (never inside a collage or a pair).
 */
object LivePolicy {
    private fun mode(setting: String) = if (setting in LivePhotos.all) setting else LivePhotos.ON

    /** Play when the slide appears. */
    fun autoPlay(setting: String, hasClip: Boolean, alone: Boolean): Boolean =
        mode(setting) == LivePhotos.ON && hasClip && alone

    /** A tap on the photo (re)plays the clip, unless it is playing already. */
    fun playOnTap(setting: String, hasClip: Boolean, alone: Boolean, playing: Boolean): Boolean =
        mode(setting) != LivePhotos.OFF && hasClip && alone && !playing

    /** The small "Live" mark: shown while the still is on screen and the clip can be played. */
    fun showMark(setting: String, hasClip: Boolean, alone: Boolean, playing: Boolean): Boolean =
        mode(setting) != LivePhotos.OFF && hasClip && alone && !playing

    /** "Tap to play" instead of just "Live" when nothing plays by itself. */
    fun tapHint(setting: String): Boolean = mode(setting) == LivePhotos.TAP

    /** Whether to pre-buffer the next slide's clip while the current one shows. */
    fun prebuffer(setting: String, hasClip: Boolean, currentIsVideo: Boolean): Boolean =
        mode(setting) == LivePhotos.ON && hasClip && !currentIsVideo

    /** A live slide advances [intervalMs] after its clip ended, or after it appeared when nothing played. */
    fun due(playing: Boolean, elapsedMs: Long, intervalMs: Long): Boolean = !playing && elapsedMs >= intervalMs
}
