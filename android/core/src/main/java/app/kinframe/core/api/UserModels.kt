package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Hand-written models for the user (people) part of docs/api/openapi.yaml, used by the family app.
 * Class names follow the schema names; fields map to snake_case through KinJson's naming strategy.
 * Enum-like values stay strings so a newer server never breaks parsing (README §7).
 */

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val shareLocationNames: Boolean = false,
    val locale: String? = null,
    val isOperator: Boolean = false,
    val createdAt: String? = null,
) {
    companion object {
        /** The server's name for accounts that have no name yet (server NOTES "Auth"). */
        const val PLACEHOLDER_NAME = "New member"
    }
}

@Serializable
data class WebPushKeys(val p256dh: String, val auth: String)

@Serializable
data class PushEndpointCreate(
    val kind: String,
    val endpoint: String,
    val webpushKeys: WebPushKeys? = null,
    val deviceName: String? = null,
    val locale: String? = null,
)

@Serializable
data class PushEndpoint(
    val id: String,
    val kind: String,
    val endpoint: String,
    val deviceName: String? = null,
    val locale: String? = null,
    val createdAt: String? = null,
)

object Role {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

@Serializable
data class Permissions(
    val send: Boolean = false,
    val invite: Boolean = false,
    val seeLibrary: Boolean = false,
    val manageLibrary: Boolean = false,
    val changeSettings: Boolean = false,
) {
    companion object {
        val ALL = Permissions(send = true, invite = true, seeLibrary = true, manageLibrary = true, changeSettings = true)
    }
}

/** `FrameSummary` and `Frame` in one class (Frame = FrameSummary + management fields). */
@Serializable
data class Frame(
    val id: String,
    val name: String,
    val location: String? = null,
    val ownerName: String? = null,
    val role: String = Role.MEMBER,
    val permissions: Permissions = Permissions(),
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val screen: Screen? = null,
    val appVersion: String? = null,
    val needsUpdate: Boolean = false,
    val defaultPermissions: Permissions? = null,
    val approveJoinsOnFrame: Boolean? = null,
    val inviteRequiresApproval: Boolean? = null,
    val moderation: Boolean? = null,
    val memberCount: Int? = null,
    val placementCount: Int? = null,
    val createdAt: String? = null,
) {
    val isManager: Boolean get() = role == Role.OWNER || role == Role.ADMIN

    /** Owner and admin have every permission (README §4). */
    fun can(permission: (Permissions) -> Boolean): Boolean = isManager || permission(permissions)
}

@Serializable
data class FrameHealth(
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val lastHeartbeat: Heartbeat? = null,
    val lastUncleanShutdownAt: String? = null,
)

object CommandType {
    const val SLEEP = "sleep"
    const val WAKE = "wake"
    const val RESTART = "restart"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val SHOW_PLACEMENT = "show_placement"
    const val SYNC_NOW = "sync_now"
    const val IDENTIFY = "identify"
}

@Serializable
data class FrameCommand(val type: String, val placementId: String? = null)

@Serializable
data class CommandResult(val ok: Boolean, val errorCode: String? = null)

object RedeemStatus {
    const val CLAIMED = "claimed"
    const val JOINED = "joined"
    const val PENDING = "pending"
    const val ALREADY_MEMBER = "already_member"
}

@Serializable
data class RedeemResult(val status: String, val frame: Frame, val joinRequestId: String? = null)

@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String = Role.MEMBER,
    val permissions: Permissions = Permissions(),
    val joinedAt: String? = null,
    val lastSentAt: String? = null,
)

@Serializable
data class JoinRequestUser(val id: String? = null, val displayName: String? = null, val avatarUrl: String? = null)

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
data class Invite(
    val id: String,
    val token: String,
    val url: String,
    val createdByName: String? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
    val note: String? = null,
    val expiresAt: String,
)

@Serializable
data class InviteCreate(val maxUses: Int?, val expiresInHours: Int = 168, val note: String? = null)

@Serializable
data class InvitePreview(
    val frameName: String,
    val frameLocation: String? = null,
    val ownerName: String? = null,
    val invitedByName: String = "",
    val requiresApproval: Boolean = false,
    val alreadyMember: Boolean = false,
)

@Serializable
data class Trim(val startMs: Long, val endMs: Long)

@Serializable
data class SendItemCreate(
    val uploadId: String,
    val caption: String? = null,
    val focalPoint: FocalPoint? = null,
    val fit: String? = null,
    val trim: Trim? = null,
    val includeAudio: Boolean? = null,
    val capturedAt: String? = null,
)

@Serializable
data class SendCreate(val frameIds: List<String>, val items: List<SendItemCreate>)

object PlacementStatus {
    const val PENDING = "pending"
    const val READY = "ready"
    const val DELIVERED = "delivered"
    const val SEEN = "seen"
    const val FAILED = "failed"
    const val REMOVED = "removed"
}

@Serializable
data class SendPlacement(
    val placementId: String,
    val itemId: String,
    val status: String = PlacementStatus.PENDING,
    val reaction: String? = null,
    val replies: List<String> = emptyList(),
)

@Serializable
data class SendFrame(
    val frameId: String,
    val frameName: String,
    val online: Boolean = false,
    val placements: List<SendPlacement> = emptyList(),
)

@Serializable
data class SendSummary(
    val processing: Int = 0,
    val pending: Int = 0,
    val delivered: Int = 0,
    val seen: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class Send(
    val id: String,
    val createdAt: String,
    val items: List<Item> = emptyList(),
    val frames: List<SendFrame> = emptyList(),
    val summary: SendSummary? = null,
)

@Serializable
data class SendsPage(val sends: List<Send> = emptyList(), val nextCursor: String? = null)

@Serializable
data class PlacementCounts(val photos: Int = 0, val videos: Int = 0, val hidden: Int = 0, val trashed: Int = 0)

@Serializable
data class PlacementsPage(
    val placements: List<Placement> = emptyList(),
    val nextCursor: String? = null,
    val counts: PlacementCounts = PlacementCounts(),
)

@Serializable
data class BulkFailure(val id: String, val code: String? = null)

@Serializable
data class BulkResult(val ok: List<String> = emptyList(), val failed: List<BulkFailure> = emptyList())

object BulkAction {
    const val HIDE = "hide"
    const val UNHIDE = "unhide"
    const val TRASH = "trash"
    const val RESTORE = "restore"
    const val PURGE = "purge"
}

@Serializable
data class Place(
    val name: String,
    val admin1: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val lat: Double,
    val lon: Double,
    val timezone: String? = null,
)

@Serializable
data class DevTokenRequest(val email: String, val name: String? = null)

@Serializable
data class AccessTokenResponse(val accessToken: String, val expiresIn: Long = 3600)

/** A frame's settings as the server sent them: the raw document (source of truth) plus a typed view. */
data class SettingsDocument(val raw: JsonObject) {
    val typed: FrameSettings = FrameSettings.parse(raw)
    val version: Int get() = typed.version
}
