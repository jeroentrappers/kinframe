@file:OptIn(ExperimentalSerializationApi::class)

package app.kinframe.core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject

/*
 * Hand-written models for the device subset of docs/api/openapi.yaml. Class names follow the schema names there;
 * fields map to snake_case through the naming strategy. Enum-like values are kept as strings so that new values
 * from a newer server never break parsing (README §7: clients ignore what they don't know).
 */

/** The JSON configuration every kinframe client uses. */
val KinJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

@Serializable
data class Discovery(
    val name: String,
    val version: String = "",
    val api: String = "",
    val web: String? = null,
    val auth: DiscoveryAuth? = null,
    val limits: Limits? = null,
    /** Development servers only: `POST /dev/token` works instead of Logto. */
    val devAuth: Boolean = false,
    /** The server fetches weather and serves `/v1/geocode`. */
    val weather: Boolean = false,
    /** The server syncs calendars for frames (ICS/webcal and CalDAV). */
    val calendar: Boolean = false,
    /** The server supports feeds and subscriptions (docs/spec/feeds.md); apps hide them otherwise. */
    val feeds: Boolean = false,
    /** Feeds have birthday lists (docs/spec/birthdays.md); only true together with [feeds]. */
    val birthdays: Boolean = false,
    /** Albums and feeds can sync from Immich or iCloud (docs/spec/photo-sources.md); apps hide sources otherwise. */
    val photoSources: Boolean = false,
    /** The providers this server allows ([PhotoSourceProvider]); empty without [photoSources]. */
    val photoSourceProviders: List<String> = emptyList(),
    /** Sources may reach home-network addresses (a LAN Immich); false: the connect sheet asks for a public address. */
    val photoSourcesPrivateNetworks: Boolean = false,
    /** The operator configured a Google OAuth client: apps offer "Add from Google Photos". */
    val googlePhotosImport: Boolean = false,
    /** Family app version policy per platform (README §5.15); null on servers without one. */
    val appVersions: AppVersions? = null,
    /** Frames and feeds can have inbox addresses (docs/spec/email-inbox.md); apps hide "Email photos" otherwise. */
    val emailInbox: Boolean = false,
    /** The mail domain of inbox addresses; null without [emailInbox]. */
    val emailInboxDomain: String? = null,
    /** The operator configured a Telegram bot (docs/spec/telegram.md); apps hide every Telegram section otherwise. */
    val telegram: Boolean = false,
    /** The bot's username without "@"; null without [telegram]. */
    val telegramBotUsername: String? = null,
    /** A group must make the bot an admin before it sees photos: the connect sheet says so up front. */
    val telegramPrivacyMode: Boolean = false,
    /** The largest file the bot can download (20 MB on Telegram's cloud Bot API); null without [telegram]. */
    val telegramMaxFileBytes: Long? = null,
    // ---- family social (docs/spec/family-social.md, README §5.18–§5.20)
    /** Home timeline, threads, people's reactions, mentions, the `member` role and circles; only with [feeds]. */
    val social: Boolean = false,
    /** Conversations (direct, group, family, circle), blocks and reports. */
    val chats: Boolean = false,
    val voiceMessages: Boolean = false,
    /** The operator turned on link previews; otherwise links show without a preview. */
    val linkPreviews: Boolean = false,
    /** Freshness, "Ask for new photos", reminders, streaks, prompts, On this day, summaries, recaps, milestones. */
    val engagement: Boolean = false,
    /** The server can send mail: people can choose an email digest. */
    val emailDigest: Boolean = false,
    // ---- leaving and other servers (docs/spec/leaving.md, docs/spec/multi-server.md, README §5.21, §5.22)
    /** Stable random id of the server: apps signed in to several servers deduplicate accounts by it. */
    val serverId: String? = null,
    /** The server chip's label ("Janssens"); default the host name. */
    val serverName: String? = null,
    /** The public base URL (the `server` field of push payloads). */
    val baseUrl: String? = null,
    /** Leave sheets with a photo choice, take away, keep requests, memories (README §5.21). */
    val leaving: Boolean = false,
    /** Frames here can follow families on other servers, families here can allow them (README §5.22). */
    val remoteFamilies: Boolean = false,
) {
    /** The photo source providers to offer: none when the server has sources off. */
    val sourceProviders: List<String>
        get() = if (!photoSources) emptyList() else photoSourceProviders.filter { it in PhotoSourceProvider.all }
}

/** Discovery `app_versions`: a platform with nothing set is left out (README §5.15). */
@Serializable
data class AppVersions(
    val android: AppVersionPolicy? = null,
    val ios: AppVersionPolicy? = null,
    val macos: AppVersionPolicy? = null,
)

/** Version policy for one family app platform (`AppVersionPolicy`, README §5.15). */
@Serializable
data class AppVersionPolicy(
    /** Lowest build the server still serves; below it requests get `426 app_update_required`. Null: no minimum. */
    val minimumBuild: Int? = null,
    /** Apps below this build show an "update available" banner. Null: none. */
    val recommendedBuild: Int? = null,
    /** Where to update; null: the app's own store page. */
    val storeUrl: String? = null,
)

@Serializable
data class DiscoveryAuth(
    val issuer: String = "",
    val mobileClientId: String = "",
    val webClientId: String = "",
    val apiResource: String = "",
)

@Serializable
data class Limits(
    val maxItemsPerSend: Int = 200,
    val maxVideoSeconds: Int = 300,
    val maxUploadBytes: Long = 2147483648,
    val trashRetentionDays: Int = 30,
    /** Feeds one account may own. */
    val maxFeedsPerUser: Int = 20,
    val maxSubscriptionsPerFeed: Int = 100,
    /** Cap of `history: all` per subscription (newest first). */
    val maxFeedBackfillItems: Int = 5000,
    /** Birthday people per feed (pending and approved). */
    val maxBirthdaysPerFeed: Int = 200,
    /** Photo sources per album or feed. */
    val maxPhotoSourcesPerTarget: Int = 5,
    /** Photo sources one account owns. */
    val maxPhotoSourcesPerUser: Int = 20,
    /** Live imported items per photo source; newer upstream items beyond it are skipped. */
    val maxPhotoSourceItems: Int = 10000,
    /** Bytes one sync run downloads; the rest follows in the next runs. */
    val maxPhotoSourceRunBytes: Long = 2147483648,
    /** Items per Google Photos import. */
    val maxGoogleImportItems: Int = 500,
    /** Inbox addresses per frame or feed. */
    val maxEmailAddressesPerTarget: Int = 5,
    /** Allowed and blocked senders per inbox address. */
    val maxEmailSendersPerAddress: Int = 200,
    /** One mail as received. */
    val maxEmailMessageBytes: Long = 52428800,
    /** Mails processed per inbox address per day; more are dropped. */
    val maxEmailMessagesPerAddressDay: Int = 200,
    /** Held mails per inbox address (10 per pending sender). */
    val maxEmailHeldMessages: Int = 100,
    /** Connected Telegram chats per frame or feed. */
    val maxTelegramChatsPerTarget: Int = 5,
    /** Connected Telegram chats one account owns. */
    val maxTelegramChatsPerUser: Int = 20,
    /** Photos and videos accepted per Telegram chat per hour. */
    val maxTelegramItemsPerChatHour: Int = 300,
    // ---- family social (README §5.18–§5.20)
    val maxCommentChars: Int = 2000,
    val maxMessageChars: Int = 4000,
    val maxMentions: Int = 20,
    val maxMessageItems: Int = 30,
    val maxGroupMembers: Int = 100,
    val maxCirclesPerFeed: Int = 20,
    val maxVoiceSeconds: Int = 300,
    val messageEditHours: Int = 24,
)

@Serializable
data class Screen(
    val width: Int,
    val height: Int,
    val densityDpi: Int? = null,
    val orientation: String? = null,
)

@Serializable
data class DeviceCapabilities(
    val lightSensor: Boolean = false,
    val motionSensor: Boolean = false,
    val accelerometer: Boolean = false,
    val battery: Boolean = false,
    val speaker: Boolean = true,
    val sdCard: Boolean = false,
    val usbHost: Boolean = false,
    val root: Boolean = false,
)

@Serializable
data class DeviceHardware(
    val brand: String? = null,
    val model: String,
    val board: String? = null,
    val androidSdk: Int? = null,
    val abis: List<String> = emptyList(),
    val ramMb: Int? = null,
    val storageTotalBytes: Long? = null,
    val maxVideoDecode: Screen? = null,
    val capabilities: DeviceCapabilities? = null,
)

@Serializable
data class DeviceAppInfo(val version: String, val versionCode: Int, val channel: String = "stable")

@Serializable
data class DeviceEnroll(
    val hardware: DeviceHardware,
    val screen: Screen,
    val app: DeviceAppInfo,
    val locale: String? = null,
    val timezone: String? = null,
)

@Serializable
data class EnrollResult(val deviceId: String, val deviceSecret: String)

@Serializable
data class DeviceTokenRequest(val deviceId: String, val deviceSecret: String)

@Serializable
data class DeviceToken(val accessToken: String, val expiresIn: Long)

@Serializable
data class DeviceState(
    val deviceId: String,
    val claimed: Boolean,
    val frameId: String? = null,
    val frameName: String? = null,
    val ownerName: String? = null,
    val latestSeq: Long = 0,
    val serverTime: String? = null,
)

@Serializable
data class FrameCode(
    val code: String,
    val expiresAt: String,
    val joinUrl: String,
    val claimed: Boolean? = null,
)

@Serializable
data class FocalPoint(val x: Double, val y: Double)

@Serializable
data class Item(
    val id: String,
    val kind: String = MediaKind.PHOTO,
    val status: String? = null,
    val failureCode: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val caption: String? = null,
    val captionEdited: Boolean = false,
    val focalPoint: FocalPoint? = null,
    val fit: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val hasAudio: Boolean? = null,
    val capturedAt: String? = null,
    val placeName: String? = null,
    val createdAt: String? = null,
    val thumbUrl: String? = null,
    /** Live items only: the motion clip (README §5.3 "Live Photos"). */
    val motion: ItemMotion? = null,
    /** Imported on the frame from another app's leftover data ([ItemOrigin]); the sender is then the frame. */
    val importedFrom: String? = null,
    /** The original sender's name in that app, shown instead of the frame ("Anna · via Frameo"). */
    val importedSenderName: String? = null,
    /** Synced by a photo source or copied by a Google Photos import: "Anna via Immich" (photo-sources.md §9.5). */
    val via: ItemVia? = null,
    // ---- leaving and other servers (README §5.21, §5.22)
    /** Copied from a family on another server: credit [app.kinframe.core.servers.ItemRemoteSender.name]. */
    val remoteSender: app.kinframe.core.servers.ItemRemoteSender? = null,
    /** Handed over at account deletion: credit "<name> (former member)". */
    val formerSenderName: String? = null,
) {
    /** A Live/Motion Photo: a still plus a short clip. Every photo field describes the still. */
    val isLive: Boolean get() = kind == MediaKind.LIVE
}

@Serializable
data class ItemMotion(
    val status: String = "processing",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val hasAudio: Boolean? = null,
)

/** Media kinds. Unknown kinds from a newer server are shown as photos. */
object MediaKind {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val LIVE = "live"
}

object Reaction {
    const val LOVE = "love"
    const val LAUGH = "laugh"
    const val WOW = "wow"
    const val SAD = "sad"
    val all = listOf(LOVE, LAUGH, WOW, SAD)
}

/** Frame rendition to download; present on placement.upsert when the placement is ready. */
@Serializable
data class PlacementMedia(
    val url: String,
    val sha256: String,
    val bytes: Long = 0,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val posterUrl: String? = null,
)

/** The frame-sized motion clip of a live placement (`placement.motion`), once ready. */
@Serializable
data class PlacementMotion(
    val url: String,
    val sha256: String,
    val bytes: Long = 0,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val hasAudio: Boolean = false,
)

@Serializable
data class Placement(
    val id: String,
    val frameId: String? = null,
    val item: Item,
    val status: String = "ready",
    val hidden: Boolean = false,
    val trashed: Boolean = false,
    val trashedAt: String? = null,
    val pendingModeration: Boolean = false,
    val fitOverride: String? = null,
    val focalOverride: FocalPoint? = null,
    val rotation: Int = 0,
    val reaction: String? = null,
    val deliveredAt: String? = null,
    val seenAt: String? = null,
    val receivedAt: String? = null,
    val albumIds: List<String> = emptyList(),
    val replies: List<PlacementReply> = emptyList(),
    val media: PlacementMedia? = null,
    val motion: PlacementMotion? = null,
    /** Set when a member shared this placement from another frame's library (README §5.3); the item keeps its sender. */
    val sharedByUserId: String? = null,
    val sharedByName: String? = null,
    /** How it got here ([PlacementOrigin]): `send`, `share`, `feed` or `import`. */
    val origin: String = PlacementOrigin.SEND,
    /** `origin: feed`: the feed that placed it, and its current name (frames show "Sender · Feed name"). */
    val originFeedId: String? = null,
    val originFeedName: String? = null,
    /** Feeds whose active subscriptions hold this placement (feed mode, like [albumIds] for album mode). */
    val feedIds: List<String> = emptyList(),
    /** Placed as feed history: not new on the frame (no "N new" card, no screen wake); `received_at` is the post time. */
    val backfill: Boolean = false,
    /** Feed placements whose post answers a weekly prompt ([FramePrompt.promptId]): the title card shows before them. */
    val promptId: String? = null,
    // ---- leaving and other servers (README §5.21, §5.22)
    /** Taken away by its sender, waiting in the trash ("Taken away by Tom"). */
    val takenAway: app.kinframe.core.leaving.PlacementTakenAway? = null,
    /** A copy of a post of a family on another server. */
    val originRemote: app.kinframe.core.servers.PlacementRemote? = null,
) {
    /** The feed to credit ("Anna · Peeters kids"), when the placement came through one. */
    val creditFeedName: String? get() = originFeedName?.takeIf { origin == PlacementOrigin.FEED && it.isNotBlank() }
}

@Serializable
data class PlacementReply(val preset: String? = null, val at: String? = null)

@Serializable
data class ChangeMember(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
)

@Serializable
data class ChangeFrame(
    val name: String? = null,
    val location: String? = null,
    val ownerName: String? = null,
)

object ChangeType {
    const val PLACEMENT_UPSERT = "placement.upsert"
    const val PLACEMENT_REMOVE = "placement.remove"
    const val SETTINGS = "settings"
    const val ALBUM_UPSERT = "album.upsert"
    const val ALBUM_REMOVE = "album.remove"
    const val MEMBER_UPSERT = "member.upsert"
    const val MEMBER_REMOVE = "member.remove"
    const val FRAME = "frame"
    const val WEATHER = "weather"
    const val CALENDAR = "calendar"
    /** The frame's birthday people ([FrameBirthdays]); `birthdays: null` means drop them. */
    const val BIRTHDAYS = "birthdays"
    // Family social (README §5.20): entity = the placement, the frame, the frame.
    const val COMMENTS = "comments"
    const val PHOTO_REQUEST = "photo_request"
    const val PROMPTS = "prompts"
    const val RESET = "reset"
}

@Serializable
data class Change(
    val seq: Long,
    val type: String,
    val placement: Placement? = null,
    val id: String? = null,
    val settings: JsonObject? = null,
    val album: JsonObject? = null,
    val member: ChangeMember? = null,
    val frame: ChangeFrame? = null,
    val weather: JsonObject? = null,
    /** The merged calendar ([FrameCalendar]); null means "drop your calendar". */
    val calendar: JsonObject? = null,
    /** The frame's birthday people ([FrameBirthdays]); null means "drop your birthdays". */
    val birthdays: JsonObject? = null,
    /** Family social ([PlacementComments], [FramePhotoRequest], [FramePrompts]); null means "drop it". */
    val comments: JsonObject? = null,
    val photoRequest: JsonObject? = null,
    val prompts: JsonObject? = null,
)

@Serializable
data class ChangesPage(val changes: List<Change> = emptyList(), val nextSeq: Long, val hasMore: Boolean = false)

@Serializable
data class SeenAck(val placementId: String, val at: String)

@Serializable
data class AckRequest(val delivered: List<String> = emptyList(), val seen: List<SeenAck> = emptyList())

@Serializable
data class Heartbeat(
    val appVersion: String,
    val appVersionCode: Int? = null,
    val androidSdk: Int? = null,
    val uptimeS: Long,
    val wifiSsid: String? = null,
    val wifiRssiDbm: Int? = null,
    val wifiLinkMbps: Int? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val cacheBytes: Long? = null,
    val placementsCached: Int? = null,
    val placementsMissing: Int? = null,
    /** Removed from the cache to free space; downloaded again when they are about to show. */
    val placementsEvicted: Int? = null,
    /** `ok`, `low` (evicting) or `critical` (nothing left to evict). */
    val storageState: String? = null,
    val screenState: String? = null,
    val lastShutdownClean: Boolean? = null,
    val crashCount24h: Int? = null,
    val localTime: String? = null,
    val timezone: String? = null,
    /** Self-update state (README §5.7): idle, downloading, waiting, installing, needs_user, updated, failed, rolled_back. */
    val updateState: String? = null,
    val lastUpdateError: String? = null,
    /** What this frame app understands beyond the base protocol ([FrameFeatures]); the server adapts to it. */
    val features: List<String>? = null,
)

/** `Heartbeat.features` values. */
object FrameFeatures {
    /** Feed history placements (`Placement.backfill`) are shown quietly: no "N new" card, no screen wake. */
    const val FEED_BACKFILL = "feed_backfill"

    /** The frame shows the `birthdays` change (informational: the server withholds nothing). */
    const val BIRTHDAYS = "birthdays"

    /**
     * Informational: the frame app was installed from Google Play (docs/play-frame.md). It updates through Play, not
     * from the server's release mirror, and runs without root. Owners and the server can tell the two builds apart.
     */
    const val PLAY = "play"

    /** Family social (README §5.20, informational): the comment card, "Ask for new photos" and prompt title cards. */
    const val COMMENTS = "comments"
    const val PHOTO_REQUESTS = "photo_requests"
    const val PROMPTS = "prompts"

    /** Everything this build supports, sent in every heartbeat. */
    val SUPPORTED: List<String> = listOf(FEED_BACKFILL, BIRTHDAYS, COMMENTS, PHOTO_REQUESTS, PROMPTS)

    /** [SUPPORTED] plus the distribution marker: [PLAY] for the Google Play build, nothing for the GitHub build. */
    fun forDistribution(distribution: String): List<String> = if (distribution == "play") SUPPORTED + PLAY else SUPPORTED
}

/** `FrameUpdate`: a frame app release mirrored by the server (`GET /device/update`). */
@Serializable
data class FrameUpdate(
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int = 1,
    val url: String,
    val sha256: String,
    val size: Long,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val prerelease: Boolean = false,
)

/** `JoinRequest`: someone wants to join a frame whose owner asked to approve joins on the frame. */
@Serializable
data class JoinRequest(
    val id: String,
    val user: JoinRequestUser = JoinRequestUser(),
    /** [JoinVia]: `frame_code`, `invite`, or an outside channel (`email`, `telegram`). */
    val via: String = "frame_code",
    val invitedByName: String? = null,
    val note: String? = null,
    /** Channel requests only: who the channel invited, as managers should see it ("Jan · jan.peeters@telenet.be"). */
    val viaName: String? = null,
    /** Channel requests only: the requester's verified identity is the one the link went to; null when unknown. */
    val viaMatches: Boolean? = null,
    val createdAt: String? = null,
) {
    /** From an outside channel's join link (always needs approval). */
    val isChannel: Boolean get() = via == JoinVia.EMAIL || via == JoinVia.TELEGRAM
}

@Serializable
data class JoinRequestUser(val id: String? = null, val displayName: String? = null, val avatarUrl: String? = null)

@Serializable
data class Problem(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
    /** Field-level validation errors (422): `{"field": "plugins.instances.0.settings.grid", "code": "required"}`. */
    val errors: List<FieldError> = emptyList(),
)

@Serializable
data class FieldError(val field: String = "", val code: String = "")

/** Stable machine codes from `ProblemCode` that clients branch on. */
object ProblemCode {
    const val UNAUTHORIZED = "unauthorized"
    const val TOKEN_EXPIRED = "token_expired"
    const val DEVICE_REVOKED = "device_revoked"
    const val NOT_FOUND = "not_found"
    const val VERSION_CONFLICT = "version_conflict"
    const val RATE_LIMITED = "rate_limited"
    const val APP_UPDATE_REQUIRED = "app_update_required"
    const val FORBIDDEN = "forbidden"
    const val MISSING_PERMISSION = "missing_permission"
    const val CODE_INVALID = "code_invalid"
    const val CODE_EXPIRED = "code_expired"
    const val INVITE_INVALID = "invite_invalid"
    const val INVITE_EXPIRED = "invite_expired"
    const val ALREADY_MEMBER = "already_member"
    const val VALIDATION_FAILED = "validation_failed"
    const val TOO_MANY_ITEMS = "too_many_items"
    const val VIDEO_TOO_LONG = "video_too_long"
    const val UPLOAD_TOO_LARGE = "upload_too_large"
    const val UPLOAD_NOT_FOUND = "upload_not_found"
    const val UNSUPPORTED_MEDIA = "unsupported_media"
    const val CORRUPT_MEDIA = "corrupt_media"
    const val FRAME_OFFLINE = "frame_offline"
    const val COMMAND_TIMEOUT = "command_timeout"
    const val OWNER_CANNOT_LEAVE = "owner_cannot_leave"
    const val UPSTREAM_UNAVAILABLE = "upstream_unavailable"
    const val FEATURE_DISABLED = "feature_disabled"
    const val ALREADY_DECIDED = "already_decided"
    const val ALREADY_SUBSCRIBED = "already_subscribed"
    const val INVALID_TRANSITION = "invalid_transition"
    const val LIMIT_REACHED = "limit_reached"
    /** A pending or approved birthday entry with the same normalized name, day and month exists (id in `detail`). */
    const val DUPLICATE_BIRTHDAY = "duplicate_birthday"
    /** A photo source create or connection change could not read the album (`detail`: the `PhotoSourceErrorCode`). */
    const val SOURCE_TEST_FAILED = "source_test_failed"
    /** The same outside album already feeds this target (`detail`: the existing source id). */
    const val DUPLICATE_SOURCE = "duplicate_source"
    /** The address is already on the inbox address's sender list (`detail`: its id). */
    const val DUPLICATE_SENDER = "duplicate_sender"
    /** Linking Telegram when the account already has a link: unlink first. */
    const val TELEGRAM_ALREADY_LINKED = "telegram_already_linked"
    const val SERVER_ERROR = "server_error"
}

/** The four reply presets a frame can send back (README §5.5). */
object ReplyPreset {
    const val BEAUTIFUL = "beautiful"
    const val CALL_ME = "call_me"
    const val MISS_YOU = "miss_you"
    const val THANKS = "thanks"
}

/** Result of `POST /device/imports`: the placement, and whether the frame already had this file (`200`, not `202`). */
data class ImportResult(val placement: Placement, val duplicate: Boolean)

@Serializable
data class ImportCheckRequest(
    val sha256: List<String> = emptyList(),
    val source: String? = null,
    val sourceKeys: List<String>? = null,
)

@Serializable
data class ImportCheckResponse(val known: List<String> = emptyList(), val knownSourceKeys: List<String> = emptyList())

/** Apps a frame can import leftover photos from (`ItemOrigin`, docs/spec/frameo-import.md). */
object ItemOrigin {
    const val FRAMEO = "frameo"
}

/**
 * The other-app part of `POST /device/imports`: [key] makes the import idempotent per frame; the rest is kept on the
 * item and placement. Times are RFC 3339.
 */
data class ImportOrigin(
    val source: String,
    val key: String,
    val senderName: String? = null,
    val receivedAt: String? = null,
    val fit: String? = null,
    val focal: FocalPoint? = null,
    val hidden: Boolean = false,
    val rotation: Int = 0,
)
