package app.kinframe.family.data

import app.kinframe.core.api.KinJson
import app.kinframe.core.leaving.KeepRequest
import app.kinframe.core.leaving.LeavingEvents
import app.kinframe.core.servers.RemoteEvents
import app.kinframe.core.servers.RemoteSubscription
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Leaving and other servers on the user websocket (README §6.2). */
sealed interface LeavingLive : LiveEvent {
    /** Placements were taken away, restored after a keep request, or purged after 30 days. */
    data class PlacementUpdated(val frameId: String, val placementIds: List<String>, val reason: String, val takingId: String?) : LeavingLive
    /** A keep request was made, answered or expired. */
    data class KeepRequestUpdated(val request: KeepRequest) : LeavingLive
    /** A frame's follow of a family on another server changed. */
    data class RemoteSubscriptionUpdated(val frameId: String, val subscription: RemoteSubscription) : LeavingLive

    companion object {
        fun parse(type: String?, o: JsonObject): LeavingLive? = runCatching {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            when (type) {
                LeavingEvents.PLACEMENT_UPDATED -> PlacementUpdated(
                    s("frame_id")!!, (o["placement_ids"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }, s("reason").orEmpty(), s("taking_id"),
                )
                LeavingEvents.KEEP_REQUEST_UPDATED -> KeepRequestUpdated(KinJson.decodeFromJsonElement(KeepRequest.serializer(), o["request"]!!))
                RemoteEvents.REMOTE_SUBSCRIPTION_UPDATED -> RemoteSubscriptionUpdated(
                    s("frame_id")!!, KinJson.decodeFromJsonElement(RemoteSubscription.serializer(), o["remote_subscription"]!!),
                )
                else -> null
            }
        }.getOrNull()
    }
}
