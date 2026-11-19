package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * Family social on the frame (docs/spec/family-social.md §8, §9.3; README §5.20): the device side only.
 * openapi schemas `PlacementComments`, `FramePhotoRequest`, `PhotoRequestState`, `FramePrompts` and
 * `FrameSettings.social`. The change types are in [ChangeType] (`comments`, `photo_request`, `prompts`).
 */

/** Payload of the `comments` change (entity = the placement): the three newest person comments, newest first. */
@Serializable
data class PlacementComments(
    val placementId: String = "",
    val comments: List<PlacementComment> = emptyList(),
) {
    /** The comment the frame shows as a card: the newest one with text. */
    val newest: PlacementComment? get() = comments.firstOrNull { it.body.isNotBlank() }

    companion object {
        fun parse(raw: JsonObject?): PlacementComments? =
            raw?.let { runCatching { KinJson.decodeFromJsonElement<PlacementComments>(it) }.getOrNull() }
    }
}

/** One comment as the frame gets it: plain text (at most 280 characters) and the author's first name. */
@Serializable
data class PlacementComment(
    val id: String = "",
    val authorName: String = "",
    val body: String = "",
    val createdAt: String? = null,
)

/** `PhotoRequestState`. */
object PhotoRequestState {
    const val OPEN = "open"
    const val ANSWERED = "answered"
    const val EXPIRED = "expired"
}

/**
 * The frame's view of its latest "Ask for new photos" (`deviceRequestPhotos` and the `photo_request` change). Until
 * [nextAllowedAt] the quick menu shows "Asked today 💛"; [answeredByName] is who answered ("New photos from Ben").
 */
@Serializable
data class FramePhotoRequest(
    val id: String = "",
    val state: String = PhotoRequestState.OPEN,
    val createdAt: String? = null,
    val nextAllowedAt: String? = null,
    val answeredAt: String? = null,
    val answeredByName: String? = null,
) {
    val isAnswered: Boolean get() = state == PhotoRequestState.ANSWERED

    companion object {
        fun parse(raw: JsonObject?): FramePhotoRequest? =
            raw?.let { runCatching { KinJson.decodeFromJsonElement<FramePhotoRequest>(it) }.getOrNull() }
    }
}

/** Payload of the `prompts` change (entity = the frame): the current weekly prompts of its families. */
@Serializable
data class FramePrompts(
    val generatedAt: String? = null,
    val prompts: List<FramePrompt> = emptyList(),
) {
    companion object {
        fun parse(raw: JsonObject?): FramePrompts? =
            raw?.let { runCatching { KinJson.decodeFromJsonElement<FramePrompts>(it) }.getOrNull() }
    }
}

/** One family's prompt; [title] is already in the frame's `locale.language`. */
@Serializable
data class FramePrompt(
    val promptId: String = "",
    val feedId: String = "",
    val feedName: String = "",
    val title: String = "",
    /** Monday of the prompt's week, `YYYY-MM-DD`. */
    val weekOf: String? = null,
)

/**
 * `FrameSettings.social` (owner, admins and `change_settings` members). Read from the raw settings document, so the
 * typed [FrameSettings] stays as it is; defaults are the server's (README §5.20).
 */
@Serializable
data class FrameSocialSettings(
    /** A card with the newest comment for 8 s when a photo appears (once per photo per day). */
    val showComments: Boolean = false,
    /** "Ask for new photos" in the quick menu. */
    val askForPhotos: Boolean = true,
    /** A weekly prompt's title card before its answers. */
    val promptCards: Boolean = true,
    /** "New photos from Ben" on the next wake after new photos arrived. */
    val newPhotosFrom: Boolean = true,
) {
    companion object {
        val DEFAULT = FrameSocialSettings()

        /** From the whole settings document ([raw] is `FrameSettings`; its `social` object is read). */
        fun of(raw: JsonObject?): FrameSocialSettings {
            val social = raw?.get("social") as? JsonObject ?: return DEFAULT
            return runCatching { KinJson.decodeFromJsonElement<FrameSocialSettings>(social) }.getOrDefault(DEFAULT)
        }
    }
}
