package app.kinframe.core.servers

import kotlinx.serialization.Serializable

/*
 * Hand-written models for families on other servers (openapi tag `remote`, README §5.22, docs/spec/multi-server.md).
 * Enum-like values stay strings so a newer server never breaks parsing (README §7).
 */

object RemoteStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val REJECTED = "rejected"
    const val REMOVED = "removed"
    const val ERROR = "error"
}

/** `RemoteOnEnd`: what the frame does with the copies when the subscription ends. */
object RemoteOnEnd {
    const val REMOVE = "remove"
    const val KEEP = "keep"
}

object RemoteEndReason {
    const val UNSUBSCRIBED = "unsubscribed"
    const val REMOVED_BY_FEED = "removed_by_feed"
    const val FEED_DELETED = "feed_deleted"
    const val SERVER_BLOCKED = "server_blocked"
    const val WITHDRAWN = "withdrawn"
    const val REJECTED = "rejected"
}

object RemoteEvents {
    const val REMOTE_SUBSCRIPTION_UPDATED = "remote_subscription.updated"
}

/** Another kinframe server as this one knows it. */
@Serializable
data class RemoteServer(
    val serverId: String? = null,
    val serverName: String,
    val serverUrl: String,
    val host: String = "",
    val lastContactAt: String? = null,
    val lastError: String? = null,
    val blocked: Boolean = false,
)

@Serializable
data class RemoteInviteRef(val inviteUrl: String)

@Serializable
data class RemoteInvitePreview(
    val server: RemoteServer,
    val feedName: String,
    val coverUrl: String? = null,
    val frameCount: Int = 0,
    val requiresApproval: Boolean = false,
    /** For "Photos from this family will be copied to this server (…)". */
    val thisServerName: String,
    val thisServerHost: String = "",
)

@Serializable
data class RemoteBackfill(val state: String = "idle", val placed: Int = 0, val total: Int = 0)

/** A frame on this server following a family on another server (B's side). */
@Serializable
data class RemoteSubscription(
    val id: String,
    val frameId: String,
    val status: String = RemoteStatus.PENDING,
    val feedName: String,
    val coverUrl: String? = null,
    val server: RemoteServer,
    val history: String = "none",
    val historyCount: Int? = null,
    val birthdays: Boolean = true,
    val onEnd: String = RemoteOnEnd.REMOVE,
    val itemCount: Int = 0,
    val requestedByName: String? = null,
    val createdAt: String = "",
    val activatedAt: String? = null,
    val endedAt: String? = null,
    val removedReason: String? = null,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
    val backfill: RemoteBackfill? = null,
) {
    val isLive: Boolean get() = status == RemoteStatus.ACTIVE || status == RemoteStatus.PENDING || status == RemoteStatus.ERROR
}

@Serializable
data class RemoteSubscriptionCreate(
    val inviteUrl: String,
    /** `none`, `last_n` or `all` (`SubscriptionHistory`). */
    val history: String = "none",
    val historyCount: Int? = null,
    val birthdays: Boolean = true,
    val onEnd: String = RemoteOnEnd.REMOVE,
)

@Serializable
data class RemoteSubscriptionUpdate(val onEnd: String? = null, val birthdays: Boolean? = null)

@Serializable
data class BlockedServer(
    val id: String,
    val host: String,
    val serverName: String? = null,
    val reason: String? = null,
    val feedId: String? = null,
    val createdByName: String? = null,
    val createdAt: String = "",
)

@Serializable
data class BlockedServerCreate(val host: String? = null, val serverUrl: String? = null, val reason: String? = null)

/** `Subscription.remote`: the subscriber is a frame on another server (A's side). */
@Serializable
data class SubscriptionRemote(
    val serverId: String? = null,
    val serverName: String,
    val serverUrl: String,
    val host: String = "",
    val requestedByName: String? = null,
    val showComments: Boolean = false,
)

/** `Placement.origin_remote`. */
@Serializable
data class PlacementRemote(
    val remoteSubscriptionId: String,
    val serverName: String,
    val serverUrl: String,
    val feedName: String,
    val senderName: String? = null,
)

/** `Item.remote_sender`. */
@Serializable
data class ItemRemoteSender(val name: String, val serverName: String, val serverUrl: String)
