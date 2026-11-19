package app.kinframe.family.ui

/** Leaving and other servers (docs/spec/leaving.md, docs/spec/multi-server.md); routed by `LeavingRoot`. */
sealed interface LeavingRoute : Route

/** Settings → Memories of one account ([accountId] null: the primary one). */
data class MemoriesRoute(val accountId: String? = null) : LeavingRoute

/** The person's keep requests; [requestId] opens one (from a `keep_request` push). */
data class KeepRequestsRoute(val requestId: String? = null) : LeavingRoute

/** Families and frames the person left, where their photos still are. */
data object FormerPlacesRoute : LeavingRoute

/** "Add a family from another server" for a frame, prefilled from an invite link of another server. */
data class RemoteFollowRoute(val frameId: String, val inviteUrl: String? = null) : LeavingRoute
