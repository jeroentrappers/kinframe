package app.kinframe.frame.sync

import app.kinframe.frame.FrameController
import app.kinframe.frame.slideshow.Stage
import app.kinframe.frame.slideshow.TransitionKind
import app.kinframe.frame.slideshow.VideoState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * What the screen shows right now, for the `now_showing` websocket message (README §6.1): a TV mirroring the frame
 * follows it live (README §6.4). Times are this frame's clock; the server only uses differences between them.
 */
data class NowShowing(
    /** Every photo on screen (a collage or pair has several), the main one first; empty while nothing shows. */
    val placementIds: List<String>,
    val startedAtMs: Long,
    val transition: String,
    val transitionMs: Int,
    val direction: Int,
    val kenBurns: Boolean,
    val holdMs: Long,
    val video: Video?,
    val nextPlacementId: String?,
    /** `on`, `off`, `dimmed` or `sleeping` (DisplayController.screenStateName). */
    val screen: String,
) {
    data class Video(val positionMs: Long, val playing: Boolean, val live: Boolean)

    fun toJson(sentAtMs: Long): String = buildJsonObject {
        put("type", JsonPrimitive("now_showing"))
        put("placement_id", placementIds.firstOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("placement_ids", JsonArray(placementIds.take(MAX_IDS).map { JsonPrimitive(it) }))
        put("started_at", JsonPrimitive(iso(startedAtMs)))
        put("transition", JsonPrimitive(transition))
        put("transition_ms", JsonPrimitive(transitionMs.coerceIn(0, 5_000)))
        put("direction", JsonPrimitive(if (direction < 0) -1 else 1))
        put("ken_burns", JsonPrimitive(kenBurns))
        put("hold_ms", JsonPrimitive(holdMs.coerceIn(0, Int.MAX_VALUE.toLong())))
        put(
            "video",
            video?.let { v ->
                buildJsonObject {
                    put("position_ms", JsonPrimitive(v.positionMs.coerceAtLeast(0)))
                    put("playing", JsonPrimitive(v.playing))
                    put("live", JsonPrimitive(v.live))
                }
            } ?: JsonNull,
        )
        put("next_placement_id", nextPlacementId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("screen", JsonPrimitive(screen))
        put("sent_at", JsonPrimitive(iso(sentAtMs)))
    }.toString()

    companion object {
        const val MAX_IDS = 6

        private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

        /** The report for a slideshow [stage] that started at [startedAtMs]. */
        fun of(
            stage: Stage, startedAtMs: Long, video: VideoState?, position: () -> Pair<Long, Boolean>?,
            next: String?, screen: String,
        ): NowShowing {
            val incoming = stage.incoming
            val v = video?.takeIf { incoming != null && it.placementId in incoming.ids }?.let { vs ->
                val (pos, playing) = position() ?: (0L to false)
                Video(pos, playing && !vs.held, vs.live)
            }
            return NowShowing(
                placementIds = incoming?.ids.orEmpty(),
                startedAtMs = startedAtMs,
                transition = when (stage.kind) {
                    TransitionKind.Crossfade -> "crossfade"
                    TransitionKind.Slide -> "slide"
                    TransitionKind.Cut -> "cut"
                },
                transitionMs = stage.durationMs,
                direction = stage.direction,
                kenBurns = incoming?.kenBurns != null,
                holdMs = stage.holdMs,
                video = v,
                nextPlacementId = next?.takeIf { incoming == null || it !in incoming.ids },
                screen = screen,
            )
        }
    }
}

/**
 * Reports [NowShowing] over the device websocket on every slide change (timer, swipe, remote commands, the "N new"
 * card), when a video starts, pauses or ends, every 15 s while one plays, and when the screen sleeps or wakes. It only
 * reads the slideshow's public state, so the slideshow itself knows nothing about mirroring.
 */
class NowShowingReporter(private val c: FrameController) {
    private var seq = -1L
    private var startedAt = 0L
    private var last: NowShowing? = null

    fun start() {
        c.scope.launch {
            combine(c.slideshow.stage, c.slideshow.video, c.display.mode, c.session) { _, _, _, s -> s }
                .collect { report(it, force = false) }
        }
        c.scope.launch {
            while (true) {
                delay(VIDEO_RESYNC_MS)
                // Playback drifts a little between a frame and a TV: keep the TV's position honest.
                if (c.slideshow.video.value != null) report(c.session.value, force = true)
            }
        }
    }

    private fun report(session: FrameSession?, force: Boolean) {
        session ?: return
        val stage = c.slideshow.stage.value
        if (stage.seq != seq) {
            seq = stage.seq
            startedAt = c.g.clock.nowMs()
        }
        val now = NowShowing.of(
            stage, startedAt, c.slideshow.video.value,
            position = { c.slideshow.player?.let { it.currentPosition to it.isPlaying } },
            next = runCatching { c.slideshow.playlist.peekNext() }.getOrNull(),
            screen = c.display.screenStateName(),
        )
        // The position always moves: compare without it, so only real changes (and the resync) are sent.
        if (!force && now.copy(video = now.video?.copy(positionMs = 0)) == last?.copy(video = last?.video?.copy(positionMs = 0))) return
        last = now
        session.reportNowShowing(now)
    }

    companion object {
        const val VIDEO_RESYNC_MS = 15_000L
    }
}
