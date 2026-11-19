package app.kinframe.family.notify

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kinframe.core.leaving.LeavingPush
import app.kinframe.family.R

/**
 * Leaving's push keys (README §6.3, `LeavingPushKey`): photos taken away, keep requests and their answers, ownership
 * moved by the operator. A tap opens the frame's trash, the family, or the keep request.
 */
object LeavingNotifications {
    /** The tap target ([app.kinframe.family.OpenTarget.target]) of every leaving push: the key follows in `ids["leaving_key"]`. */
    const val TARGET = "leaving"
    const val EXTRA_KEY = "app.kinframe.family.leaving.KEY"
    const val EXTRA_REQUEST_ID = "app.kinframe.family.leaving.REQUEST_ID"

    fun handles(kind: String): Boolean = kind in LeavingPush.all

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun show(context: Context, msg: PushMessage) {
        val title = msg.title.ifBlank {
            context.getString(when (msg.kind) {
                LeavingPush.PHOTOS_TAKEN_AWAY -> R.string.leaving_notif_taken_title
                LeavingPush.KEEP_REQUEST -> R.string.leaving_notif_keep_request_title
                LeavingPush.KEEP_ANSWERED -> R.string.leaving_notif_keep_answered_title
                else -> R.string.leaving_notif_ownership_title
            })
        }
        val key = msg.ids["request_id"] ?: msg.ids["taking_id"] ?: msg.ids["frame_id"] ?: msg.ids["feed_id"] ?: msg.kind
        val intent = Notifications.openApp(context, (msg.kind + key).hashCode()) {
            putExtra(Notifications.EXTRA_TARGET, TARGET)
            putExtra(EXTRA_KEY, msg.kind)
            msg.account.takeIf { it.isNotBlank() }?.let { putExtra(Notifications.EXTRA_ACCOUNT, it) }
            msg.ids["request_id"]?.let { putExtra(EXTRA_REQUEST_ID, it) }
            msg.ids["frame_id"]?.let { putExtra(Notifications.EXTRA_FRAME_ID, it) }
            msg.ids["feed_id"]?.let { putExtra(Notifications.EXTRA_FEED_ID, it) }
        }
        val n = NotificationCompat.Builder(context, if (msg.kind == LeavingPush.KEEP_REQUEST) Notifications.CHANNEL_PEOPLE else Notifications.CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(msg.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        if (!Notifications.canPost(context)) return
        val tag = msg.tag.ifBlank { null } ?: if (msg.kind == LeavingPush.PHOTOS_TAKEN_AWAY) "${msg.kind}:${msg.ids["taking_id"]}:${msg.ids["frame_id"] ?: msg.ids["feed_id"]}" else null
        try {
            if (tag != null) NotificationManagerCompat.from(context).notify(tag, 1, n)
            else NotificationManagerCompat.from(context).notify((msg.kind + key).hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
