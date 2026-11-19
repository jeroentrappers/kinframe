package app.kinframe.frame.social

import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.FramePrompt
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementOrigin
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/** The "Ask for new photos" button in the quick menu (family-social.md §8, README §5.20). */
sealed interface AskState {
    /** Not asked in the last 24 hours: the button asks. */
    data object Ready : AskState
    /** "Asked today 💛" until `next_allowed_at`. */
    data object Asked : AskState
    /** Asked, and someone already answered with photos: "Ben sent new photos 💛". */
    data class Answered(val name: String) : AskState
}

/** Pure rules of family social on the frame, kept apart from the controller so they are easy to test. */
object SocialLogic {
    const val DAY_MS = 24 * 3_600_000L

    /** How long the comment card (and a prompt's title card) stays: 8 s (family-social.md §8). */
    const val CARD_MS = 8_000L

    /** How long "New photos from Ben" stays after the wake. */
    const val NOTE_MS = 10_000L

    /** At most this many names are remembered for the note; it names two and says "and others" for the rest. */
    const val MAX_NAMES = 6

    /**
     * The button's state at [nowMs]: asked until the server's `next_allowed_at`, or 24 h after a tap on this frame
     * that may still be waiting in the outbox ([askedAtMs]), whichever is later.
     */
    fun askState(request: FramePhotoRequest?, askedAtMs: Long?, nowMs: Long): AskState {
        val serverUntil = request?.nextAllowedAt?.let { parseTime(it) } ?: 0L
        val localUntil = askedAtMs?.plus(DAY_MS) ?: 0L
        if (nowMs >= maxOf(serverUntil, localUntil)) return AskState.Ready
        val name = request?.answeredByName?.trim()
        return if (request?.isAnswered == true && !name.isNullOrEmpty()) AskState.Answered(name) else AskState.Asked
    }

    /**
     * Who to name for a new placement in "New photos from Ben": a person's own photo (a send, a feed post, a photo
     * mailed or posted in Telegram), never a share between frames, an import or a photo-source sync.
     */
    fun senderName(p: Placement): String? {
        if (p.origin != PlacementOrigin.SEND && p.origin != PlacementOrigin.FEED) return null
        if (p.item.importedFrom != null) return null
        val via = p.item.via
        val name = when {
            via == null -> p.item.senderName
            via.isChannel -> via.personLabel ?: p.item.senderName
            else -> return null
        }
        return name?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun names(json: String?): List<String> =
        json?.let { runCatching { KinJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun encode(names: List<String>): String? =
        if (names.isEmpty()) null else KinJson.encodeToString(ListSerializer(String.serializer()), names)

    /** Adds [more] (at the front with [first], e.g. who answered the request), once per name, at most [MAX_NAMES]. */
    fun addNames(json: String?, more: List<String>, first: Boolean = false): String? {
        if (more.isEmpty()) return json
        val now = names(json)
        val merged = (if (first) more + now else now + more).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.take(MAX_NAMES)
        return encode(merged)
    }

    /** Removes the names that were announced; names that arrived meanwhile stay for the next wake. */
    fun removeNames(json: String?, shown: List<String>): String? {
        val gone = shown.map { it.lowercase() }.toSet()
        return encode(names(json).filter { it.lowercase() !in gone })
    }

    /**
     * Whether a prompt's title card comes before this answer: prompt cards are on, the prompt is one of the frame's
     * current prompts, and the photo before it was not an answer to the same prompt (so a run of answers gets one card).
     */
    fun promptCard(enabled: Boolean, promptId: String?, previousPromptId: String?, prompts: Map<String, FramePrompt>): FramePrompt? {
        if (!enabled || promptId == null || promptId == previousPromptId) return null
        return prompts[promptId]?.takeIf { it.title.isNotBlank() }
    }

    fun parseTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
}
