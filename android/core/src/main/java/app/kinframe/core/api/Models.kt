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
)

object MediaKind {
    const val PHOTO = "photo"
    const val VIDEO = "video"
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
    val media: PlacementMedia? = null,
)

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
)

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
    val via: String = "frame_code",
    val invitedByName: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class JoinRequestUser(val id: String? = null, val displayName: String? = null, val avatarUrl: String? = null)

@Serializable
data class Problem(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
)

/** Stable machine codes from `ProblemCode` that clients branch on. */
object ProblemCode {
    const val UNAUTHORIZED = "unauthorized"
    const val TOKEN_EXPIRED = "token_expired"
    const val DEVICE_REVOKED = "device_revoked"
    const val NOT_FOUND = "not_found"
    const val VERSION_CONFLICT = "version_conflict"
    const val RATE_LIMITED = "rate_limited"
    const val APP_UPDATE_REQUIRED = "app_update_required"
}

/** Result of `POST /device/imports`: the placement, and whether the frame already had this file (`200`, not `202`). */
data class ImportResult(val placement: Placement, val duplicate: Boolean)

@Serializable
data class ImportCheckRequest(val sha256: List<String>)

@Serializable
data class ImportCheckResponse(val known: List<String> = emptyList())
