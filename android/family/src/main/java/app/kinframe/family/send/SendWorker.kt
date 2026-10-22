package app.kinframe.family.send

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kinframe.family.FamilyApp
import app.kinframe.family.R
import app.kinframe.family.notify.Notifications
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Uploads a queued send in the background (product spec §5: "a send continues after the app is closed"). Runs as an
 * expedited job with a foreground notification showing progress; WorkManager retries with back-off when the network
 * drops, and the tus state in the outbox makes each retry resume where the last one stopped.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as FamilyApp).graph
    private val sendId get() = inputData.getString(KEY_SEND) ?: ""

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null)

    private fun foreground(fraction: Float?): ForegroundInfo {
        val ctx = applicationContext
        val send = graph.outbox.get(sendId)
        val names = send?.frameNames?.joinToString(", ").orEmpty()
        val count = send?.items?.size ?: 0
        val p = graph.outbox.progress.value[sendId]
        val text = if (p != null) {
            ctx.getString(R.string.outbox_uploading, p.itemIndex + 1, p.itemCount, (p.fraction * 100).toInt())
        } else {
            ctx.resources.getQuantityString(R.plurals.notif_sending_items, count, count)
        }
        val n = Notifications.uploadProgress(ctx, ctx.getString(R.string.notif_sending_title, names), text, fraction)
        val id = NOTIFICATION_BASE + sendId.hashCode() % 1000
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    override suspend fun doWork(): Result {
        val runner = graph.sendRunner() ?: return Result.retry()
        runCatching { setForeground(foreground(null)) }
        val ticker = graph.scope.launch {
            var last = -1
            graph.outbox.progress.collectLatest { m ->
                val f = m[sendId]?.fraction ?: return@collectLatest
                val pct = (f * 100).toInt()
                if (pct != last) {
                    last = pct
                    runCatching { setForeground(foreground(f)) }
                }
            }
        }
        val before = graph.outbox.get(sendId)
        val outcome = try {
            runner.run(sendId)
        } finally {
            ticker.cancel()
        }
        return when (outcome) {
            is SendRunner.Outcome.Sent -> {
                before?.items?.filter { it.localPath == null }?.forEach { MediaFiles.release(applicationContext.contentResolver, Uri.parse(it.uri)) }
                graph.onSent(outcome.sendId)
                Result.success()
            }
            SendRunner.Outcome.Retry -> if (runAttemptCount >= MAX_ATTEMPTS) {
                graph.outbox.update(sendId) { it.copy(state = OutboxState.FAILED, error = "network") }
                Result.failure()
            } else Result.retry()
            is SendRunner.Outcome.Failed -> {
                val msg = Problems.byCode(outcome.code) ?: R.string.problem_server_error
                Notifications.uploadFailed(applicationContext, sendId, applicationContext.getString(msg))
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_SEND = "send_id"
        private const val NOTIFICATION_BASE = 4000
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context, sendId: String) {
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf(KEY_SEND to sendId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("send-$sendId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, sendId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("send-$sendId")
        }

        const val TAG = "kinframe-send"
    }
}
