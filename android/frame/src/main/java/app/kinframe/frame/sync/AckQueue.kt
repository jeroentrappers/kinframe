package app.kinframe.frame.sync

import android.util.Log
import app.kinframe.core.api.AckRequest
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.SeenAck
import app.kinframe.frame.data.AckEntity
import app.kinframe.frame.data.FrameDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Batched, durable acks (README §5.4): `delivered` when a rendition is stored, `seen` when first displayed.
 * Sent every [intervalMs] or as soon as [batchSize] are waiting. Rows are deleted only after the server accepted
 * them, so acks survive restarts and offline periods.
 */
class AckQueue(
    private val db: FrameDatabase,
    private val now: () -> Long,
    private val intervalMs: Long = 10_000,
    private val batchSize: Int = 50,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    suspend fun enqueueDeliveredInTx(placementId: String) {
        db.acks().insert(AckEntity(placementId = placementId, kind = AckEntity.DELIVERED, at = now()))
    }

    suspend fun enqueueSeen(placementId: String) {
        db.acks().insert(AckEntity(placementId = placementId, kind = AckEntity.SEEN, at = now()))
        nudge()
    }

    /** Wakes the flusher early if a full batch is waiting. */
    fun nudge() { wake.trySend(Unit) }

    suspend fun run(api: DeviceApi) {
        while (coroutineContext.isActive) {
            withTimeoutOrNull(intervalMs) {
                while (true) {
                    wake.receive()
                    if (db.acks().count() >= batchSize) break
                }
            }
            try {
                flush(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "ack flush failed: ${e.message}")
            }
        }
    }

    /** Sends everything queued (in batches of up to 500). */
    suspend fun flush(api: DeviceApi) {
        while (true) {
            val batch = db.acks().batch(500)
            if (batch.isEmpty()) return
            val req = AckRequest(
                delivered = batch.filter { it.kind == AckEntity.DELIVERED }.map { it.placementId },
                seen = batch.filter { it.kind == AckEntity.SEEN }.map { SeenAck(it.placementId, Instant.ofEpochMilli(it.at).toString()) },
            )
            api.acks(req)
            db.acks().delete(batch.map { it.id })
            if (batch.size < 500) return
        }
    }

    private companion object { const val TAG = "KinAcks" }
}
