package app.kinframe.frame.sync

import android.util.Log
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MergePatch
import app.kinframe.core.api.ProblemCode
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Durable queue for actions taken at the frame. The local database is updated immediately (optimistic UI); the
 * server call happens here, in order, with retries. Hide/delete wait for their undo window via `notBefore`.
 */
class Outbox(
    private val db: FrameDatabase,
    private val now: () -> Long,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Called when settings were rejected with 412: pull the change feed so the next attempt has the new version. */
    var onVersionConflict: suspend () -> Unit = {}
    var onRevoked: () -> Unit = {}

    fun kick() { wake.trySend(Unit) }

    suspend fun reaction(placementId: String, reaction: String?) {
        db.tx {
            db.outbox().deleteKindFor(OutboxEntity.REACTION, placementId)
            db.placements().setReaction(placementId, reaction)
            val payload = buildJsonObject { put("reaction", reaction?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }
            db.outbox().insert(OutboxEntity(kind = OutboxEntity.REACTION, placementId = placementId, payload = payload.toString(), notBefore = now(), createdAt = now()))
        }
        kick()
    }

    /** Hides or trashes locally now and sends after [delayMs] unless [cancel]led (undo). Returns the outbox id. */
    suspend fun placementPatch(placementId: String, hidden: Boolean? = null, trashed: Boolean? = null, delayMs: Long = 0): Long {
        val id = db.tx {
            hidden?.let { db.placements().setHidden(placementId, it) }
            trashed?.let { db.placements().setTrashed(placementId, it) }
            val payload = buildJsonObject {
                hidden?.let { put("hidden", JsonPrimitive(it)) }
                trashed?.let { put("trashed", JsonPrimitive(it)) }
            }
            db.outbox().insert(OutboxEntity(kind = OutboxEntity.PLACEMENT_PATCH, placementId = placementId, payload = payload.toString(), notBefore = now() + delayMs, createdAt = now()))
        }
        kick()
        return id
    }

    /** Undo: drops the queued action and restores the local flags. Returns false if it was already sent. */
    suspend fun cancel(outboxId: Long, placementId: String, restoreHidden: Boolean? = null, restoreTrashed: Boolean? = null): Boolean = db.tx {
        val pending = db.outbox().forPlacement(placementId).any { it.id == outboxId }
        if (pending) db.outbox().delete(outboxId)
        restoreHidden?.let { db.placements().setHidden(placementId, it) }
        restoreTrashed?.let { db.placements().setTrashed(placementId, it) }
        if (!pending) {
            // Already sent: queue the reverse action.
            val payload = buildJsonObject {
                restoreHidden?.let { put("hidden", JsonPrimitive(it)) }
                restoreTrashed?.let { put("trashed", JsonPrimitive(it)) }
            }
            db.outbox().insert(OutboxEntity(kind = OutboxEntity.PLACEMENT_PATCH, placementId = placementId, payload = payload.toString(), notBefore = now(), createdAt = now()))
        }
        pending
    }.also { kick() }

    /** Merges [patch] into the single pending settings patch. */
    suspend fun settingsPatch(patch: JsonObject) {
        db.tx {
            val existing = db.outbox().ofKind(OutboxEntity.SETTINGS_PATCH).firstOrNull()
            if (existing == null) {
                db.outbox().insert(OutboxEntity(kind = OutboxEntity.SETTINGS_PATCH, placementId = null, payload = patch.toString(), notBefore = now() + 800, createdAt = now()))
            } else {
                val merged = MergePatch.apply(KinJson.parseToJsonElement(existing.payload), patch)
                db.outbox().reschedule(existing.id, 0, now() + 800, merged.toString())
            }
        }
        kick()
    }

    suspend fun run(api: DeviceApi) {
        while (coroutineContext.isActive) {
            val next = db.outbox().nextDue(now())
            if (next == null) {
                val earliest = db.outbox().earliest()
                val wait = earliest?.let { (it - now()).coerceIn(200, 3_600_000) }
                if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            val done = try {
                send(api, next)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceRevokedException) {
                onRevoked(); return
            } catch (e: ApiException) {
                when {
                    e.code == ProblemCode.VERSION_CONFLICT || e.status == 412 -> {
                        runCatching { onVersionConflict() }
                        false
                    }
                    e.status in 400..499 && e.status != 408 && e.status != 429 -> {
                        Log.w(TAG, "dropping ${next.kind} after ${e.status} ${e.code}")
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.i(TAG, "${next.kind} failed: ${e.message}")
                false
            }
            if (done) {
                db.outbox().delete(next.id)
            } else {
                val attempts = next.attempts + 1
                val delay = min(15 * 60_000L, 5_000L * (1L shl min(attempts, 8)))
                db.outbox().reschedule(next.id, attempts, now() + delay, next.payload)
                if (attempts > 1) withTimeoutOrNull(2_000) { wake.receive() }
            }
        }
    }

    private suspend fun send(api: DeviceApi, o: OutboxEntity) {
        val payload = KinJson.parseToJsonElement(o.payload).jsonObject
        when (o.kind) {
            OutboxEntity.REACTION -> api.setReaction(o.placementId!!, payload["reaction"]?.jsonPrimitive?.let { if (it.isString) it.content else null })
            OutboxEntity.PLACEMENT_PATCH -> api.patchPlacement(o.placementId!!, payload)
            OutboxEntity.REPLY -> api.sendReply(o.placementId!!, payload["preset"]!!.jsonPrimitive.content)
            OutboxEntity.SETTINGS_PATCH -> {
                val version = currentSettingsVersion()
                val result = api.patchSettings(version, payload)
                // Store the server's merged document right away; the change feed will confirm it.
                db.tx {
                    val st = db.syncState().get() ?: app.kinframe.frame.data.SyncStateEntity()
                    val serverVersion = result["version"]?.jsonPrimitive?.int ?: version
                    val currentVersion = st.settingsJson?.let { runCatching { KinJson.parseToJsonElement(it).jsonObject["version"]?.jsonPrimitive?.int }.getOrNull() } ?: -1
                    if (serverVersion >= currentVersion) db.syncState().put(st.copy(settingsJson = result.toString()))
                }
            }
        }
    }

    private suspend fun currentSettingsVersion(): Int {
        val s = db.syncState().get()?.settingsJson ?: return 0
        return runCatching { KinJson.parseToJsonElement(s).jsonObject["version"]?.jsonPrimitive?.int ?: 0 }.getOrDefault(0)
    }

    private companion object { const val TAG = "KinOutbox" }
}
