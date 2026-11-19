package app.kinframe.family.data

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.BirthdayPerson
import app.kinframe.core.api.FeedBirthdayGreeting
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.FeedReceiptSummary
import app.kinframe.core.api.GoogleImport
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceEvents
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Send
import app.kinframe.core.ws.ReconnectingWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Messages on the user websocket (README §6.2). Unknown types are ignored. */
sealed interface LiveEvent {
    data class SendUpdated(val send: Send) : LiveEvent
    /** [feedId]/[postId] are set when the placement came through a feed. */
    data class Reaction(
        val placementId: String, val frameId: String, val reaction: String?, val byFrameName: String?,
        val feedId: String? = null, val postId: String? = null,
    ) : LiveEvent
    data class Reply(val placementId: String, val frameId: String, val preset: String, val feedId: String? = null, val postId: String? = null) : LiveEvent
    data class FrameStatus(val frameId: String, val online: Boolean, val lastSeenAt: String?) : LiveEvent
    data class JoinRequested(val frameId: String, val request: JoinRequest?) : LiveEvent
    data class MembershipUpdated(val frameId: String) : LiveEvent
    /** A frame accepted or declined an event someone sent it. */
    data class InvitationDecided(val frameId: String, val invitation: EventInvitation) : LiveEvent
    /** Delivery or seen counts of a feed post changed (the poster and feed owner/admins). */
    data class FeedPostUpdated(val feedId: String, val postId: String, val summary: FeedReceiptSummary) : LiveEvent
    /** A frame asks to subscribe to a feed (feed owner/admins). */
    data class SubscriptionRequested(val feedId: String, val subscription: Subscription) : LiveEvent
    /** Approved, rejected, paused, resumed, history changed or removed. */
    data class SubscriptionUpdated(val feedId: String, val frameId: String, val subscription: Subscription?) : LiveEvent
    /** Someone asks to join a feed (owner/admins). */
    data class FeedJoinRequested(val feedId: String, val request: JoinRequest?) : LiveEvent
    /** Your feed role changed, you were added or removed, or the feed was deleted. */
    data class FeedMembershipUpdated(val feedId: String) : LiveEvent
    /** A poster suggested a birthday person (feed owner/admins). */
    data class BirthdaySuggested(val feedId: String, val birthday: BirthdayPerson) : LiveEvent
    /** Your suggestion was approved or rejected. */
    data class BirthdayDecided(val feedId: String, val birthday: BirthdayPerson) : LiveEvent
    /** A birthday person was added, changed, approved or rejected. */
    data class BirthdayUpdated(val feedId: String, val birthday: BirthdayPerson) : LiveEvent
    data class BirthdayRemoved(val feedId: String, val birthdayId: String) : LiveEvent
    /** The morning notification: today's birthdays as feed and birthday ids. */
    data class BirthdayToday(val date: String, val birthdays: List<Pair<String, String>>) : LiveEvent
    /** A frame said happy birthday. */
    data class BirthdayGreeting(val feedId: String, val greeting: FeedBirthdayGreeting) : LiveEvent
    /** A photo source's status, counts or last run changed (the target's managers and the owner). */
    data class PhotoSourceUpdated(val source: PhotoSource) : LiveEvent
    /** A photo source was deleted, also with its album or feed. */
    data class PhotoSourceRemoved(val sourceId: String, val albumId: String?, val feedId: String?) : LiveEvent
    /** Status or progress of the caller's Google Photos import changed. */
    data class GoogleImportUpdated(val import: GoogleImport) : LiveEvent
    /** An inbox address was created, changed, paused, regenerated or taken over, or its counts changed. */
    data class EmailAddressUpdated(val address: app.kinframe.core.api.EmailAddress) : LiveEvent
    data class EmailAddressRemoved(val addressId: String, val frameId: String?, val feedId: String?) : LiveEvent
    /** Someone not yet allowed mailed the address, or mailed again. */
    data class EmailSenderPending(val addressId: String, val pending: app.kinframe.core.api.EmailPendingSender) : LiveEvent
    /** A pending sender was decided or the sender list changed ([app.kinframe.core.api.EmailSenderDecision]). */
    data class EmailSenderDecided(val addressId: String, val senderId: String, val decision: String) : LiveEvent
    /** A Telegram chat was connected, changed, paused, taken over or disconnected, or its status or counts changed. */
    data class TelegramChatUpdated(val chat: app.kinframe.core.api.TelegramChat) : LiveEvent
    data class TelegramChatRemoved(val chatId: String, val frameId: String?, val feedId: String?) : LiveEvent
    /** The caller's connect or link code was used, or redeeming it failed. */
    data class TelegramCodeUpdated(val code: app.kinframe.core.api.TelegramCode) : LiveEvent
    /** The caller's Telegram account was linked ([link]), unlinked (null), or blocked the bot. */
    data class TelegramLinkUpdated(val link: app.kinframe.core.api.TelegramLink?) : LiveEvent

    companion object {
        fun parse(text: String): LiveEvent? = runCatching {
            val o = KinJson.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            when (s("type")) {
                "send.updated" -> SendUpdated(KinJson.decodeFromJsonElement(Send.serializer(), o["send"]!!))
                "reaction" -> Reaction(s("placement_id")!!, s("frame_id")!!, s("reaction"), s("by_frame_name"), s("feed_id"), s("post_id"))
                "reply" -> Reply(s("placement_id")!!, s("frame_id")!!, s("preset")!!, s("feed_id"), s("post_id"))
                "feed_post.updated" -> FeedPostUpdated(s("feed_id")!!, s("post_id")!!, KinJson.decodeFromJsonElement(FeedReceiptSummary.serializer(), o["summary"]!!))
                "subscription.request" -> SubscriptionRequested(s("feed_id")!!, KinJson.decodeFromJsonElement(Subscription.serializer(), o["subscription"]!!))
                "subscription.updated" -> SubscriptionUpdated(
                    s("feed_id")!!, s("frame_id")!!,
                    (o["subscription"] as? JsonObject)?.let { KinJson.decodeFromJsonElement(Subscription.serializer(), it) },
                )
                "feed.join_request" -> FeedJoinRequested(
                    s("feed_id")!!,
                    (o["request"] as? JsonObject)?.let { KinJson.decodeFromJsonElement(JoinRequest.serializer(), it) },
                )
                "feed.membership_updated" -> FeedMembershipUpdated(s("feed_id")!!)
                "birthday.suggested" -> BirthdaySuggested(s("feed_id")!!, KinJson.decodeFromJsonElement(BirthdayPerson.serializer(), o["birthday"]!!))
                "birthday.decided" -> BirthdayDecided(s("feed_id")!!, KinJson.decodeFromJsonElement(BirthdayPerson.serializer(), o["birthday"]!!))
                "birthday.updated" -> BirthdayUpdated(s("feed_id")!!, KinJson.decodeFromJsonElement(BirthdayPerson.serializer(), o["birthday"]!!))
                "birthday.removed" -> BirthdayRemoved(s("feed_id")!!, s("birthday_id")!!)
                "birthday.today" -> BirthdayToday(
                    s("date").orEmpty(),
                    (o["birthdays"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                        val b = e as? JsonObject ?: return@mapNotNull null
                        val f = b["feed_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val id = b["birthday_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        f to id
                    },
                )
                "birthday.greeting" -> BirthdayGreeting(s("feed_id")!!, KinJson.decodeFromJsonElement(FeedBirthdayGreeting.serializer(), o["greeting"]!!))
                PhotoSourceEvents.UPDATED -> PhotoSourceUpdated(KinJson.decodeFromJsonElement(PhotoSource.serializer(), o["source"]!!))
                PhotoSourceEvents.REMOVED -> PhotoSourceRemoved(s("source_id")!!, s("album_id"), s("feed_id"))
                PhotoSourceEvents.GOOGLE_IMPORT_UPDATED -> GoogleImportUpdated(KinJson.decodeFromJsonElement(GoogleImport.serializer(), o["import"]!!))
                app.kinframe.core.api.EmailEvents.ADDRESS_UPDATED -> EmailAddressUpdated(KinJson.decodeFromJsonElement(app.kinframe.core.api.EmailAddress.serializer(), o["address"]!!))
                app.kinframe.core.api.EmailEvents.ADDRESS_REMOVED -> EmailAddressRemoved(s("address_id")!!, s("frame_id"), s("feed_id"))
                app.kinframe.core.api.EmailEvents.SENDER_PENDING -> EmailSenderPending(s("address_id")!!, KinJson.decodeFromJsonElement(app.kinframe.core.api.EmailPendingSender.serializer(), o["pending"]!!))
                app.kinframe.core.api.EmailEvents.SENDER_DECIDED -> EmailSenderDecided(s("address_id")!!, s("sender_id")!!, s("decision").orEmpty())
                app.kinframe.core.api.TelegramEvents.CHAT_UPDATED -> TelegramChatUpdated(KinJson.decodeFromJsonElement(app.kinframe.core.api.TelegramChat.serializer(), o["chat"]!!))
                app.kinframe.core.api.TelegramEvents.CHAT_REMOVED -> TelegramChatRemoved(s("chat_id")!!, s("frame_id"), s("feed_id"))
                app.kinframe.core.api.TelegramEvents.CODE_UPDATED -> TelegramCodeUpdated(KinJson.decodeFromJsonElement(app.kinframe.core.api.TelegramCode.serializer(), o["code"]!!))
                app.kinframe.core.api.TelegramEvents.LINK_UPDATED -> TelegramLinkUpdated((o["link"] as? JsonObject)?.let { KinJson.decodeFromJsonElement(app.kinframe.core.api.TelegramLink.serializer(), it) })
                "frame.status" -> FrameStatus(s("frame_id")!!, o["online"]?.jsonPrimitive?.booleanOrNull ?: false, s("last_seen_at"))
                "join_request" -> JoinRequested(
                    s("frame_id")!!,
                    (o["request"] as? JsonObject)?.let { KinJson.decodeFromJsonElement(JoinRequest.serializer(), it) },
                )
                "membership.updated" -> MembershipUpdated(s("frame_id")!!)
                "event_invitation.decided" -> InvitationDecided(s("frame_id")!!, KinJson.decodeFromJsonElement(EventInvitation.serializer(), o["invitation"]!!))
                // Family social (README §6.2): data/SocialLiveEvents.kt.
                else -> SocialLive.parse(s("type"), o)
            }
        }.getOrNull()
    }
}

/**
 * The user websocket while the app is in the foreground. When it is closed the server sends push notifications
 * instead (README §6.3), so it is only open while someone looks at the app.
 */
class LiveEvents(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val tokens: AccessTokenSource,
) {
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private var socket: ReconnectingWebSocket? = null
    private var api: KinApi? = null

    val status: StateFlow<ReconnectingWebSocket.Status>? get() = socket?.status

    @Synchronized
    fun start(api: KinApi) {
        if (this.api === api && socket != null) return
        stop()
        this.api = api
        val ws = ReconnectingWebSocket(http, scope, { api.webSocketRequest() }, object : ReconnectingWebSocket.Listener {
            override fun onMessage(text: String) {
                LiveEvent.parse(text)?.let { _events.tryEmit(it) }
            }

            override fun onUnauthorized() {
                // 4401 at token expiry: reconnect with a fresh token.
                scope.launch {
                    if (runCatching { tokens.accessToken(forceRefresh = true) }.isSuccess) socket?.start()
                }
            }
        }, tag = "KinUserWs")
        socket = ws
        ws.start()
    }

    @Synchronized
    fun stop() {
        socket?.stop()
        socket = null
        api = null
    }

    fun reconnectNow() = socket?.reconnectNow()

    /** For tests and previews. */
    fun emit(event: LiveEvent) = _events.tryEmit(event)
}
