package app.kinframe.family.send

import app.kinframe.core.social.Mention
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.MessageItemCreate
import app.kinframe.core.social.MessageTrim
import app.kinframe.core.social.VoiceCreate
import kotlinx.serialization.Serializable

/**
 * A chat message whose photos, videos or voice recording go through the outbox (README §5.19: "the normal tus uploads
 * and prepared uploads"). The outbox id is the message's `client_id` and `Idempotency-Key`, so a retry after a crash
 * returns the first message instead of sending twice.
 */
@Serializable
data class OutboxChat(
    val conversationId: String,
    val conversationName: String,
    val body: String? = null,
    val mentions: List<Mention> = emptyList(),
    val replyToId: String? = null,
    /** The single item is a voice recording (AAC in MPEG-4), not a photo. */
    val voice: Boolean = false,
    val voiceDurationMs: Long? = null,
) {
    companion object {
        const val VOICE_KIND = "audio"
        const val VOICE_MIME = "audio/mp4"

        /** The message for a send whose files are all uploaded. */
        fun message(send: OutboxSend): MessageCreate {
            val chat = requireNotNull(send.chat)
            val body = chat.body?.takeIf { it.isNotBlank() }
            val mentions = chat.mentions.takeIf { it.isNotEmpty() && body != null }
            return if (chat.voice) {
                MessageCreate(
                    clientId = send.id, body = body, mentions = mentions, replyToId = chat.replyToId,
                    voice = VoiceCreate(requireNotNull(send.items.single().uploadId), chat.voiceDurationMs?.takeIf { it > 0 }),
                )
            } else {
                MessageCreate(
                    clientId = send.id, body = body, mentions = mentions, replyToId = chat.replyToId,
                    items = send.items.map { item ->
                        val s = item.toSendItem()
                        MessageItemCreate(
                            uploadId = s.uploadId, caption = s.caption, capturedAt = s.capturedAt,
                            trim = s.trim?.let { MessageTrim(it.startMs, it.endMs) }, includeAudio = s.includeAudio,
                            width = s.width, height = s.height, durationMs = s.durationMs,
                            liveVideoUploadId = s.liveVideoUploadId, live = s.live,
                        )
                    },
                )
            }
        }
    }
}
