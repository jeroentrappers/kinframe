package app.kinframe.family.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.kinframe.family.MainActivity
import app.kinframe.family.R

/** Notification channels, the upload progress notification, and pushes (reactions, replies, join requests). */
object Notifications {
    const val CHANNEL_SENDING = "sending"
    const val CHANNEL_ACTIVITY = "activity"
    const val CHANNEL_PEOPLE = "people"
    const val CHANNEL_FRAMES = "frames"

    const val EXTRA_SEND_ID = "app.kinframe.family.SEND_ID"
    const val EXTRA_FRAME_ID = "app.kinframe.family.FRAME_ID"
    const val EXTRA_PLACEMENT_ID = "app.kinframe.family.PLACEMENT_ID"
    const val EXTRA_TARGET = "app.kinframe.family.TARGET"
    const val TARGET_HISTORY = "history"
    const val TARGET_PEOPLE = "people"
    const val TARGET_FRAME = "frame"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: Int, desc: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(desc) }
        nm.createNotificationChannels(
            listOf(
                ch(CHANNEL_ACTIVITY, R.string.channel_activity, R.string.channel_activity_desc, NotificationManager.IMPORTANCE_DEFAULT),
                ch(CHANNEL_PEOPLE, R.string.channel_people, R.string.channel_people_desc, NotificationManager.IMPORTANCE_DEFAULT),
                ch(CHANNEL_FRAMES, R.string.channel_frames, R.string.channel_frames_desc, NotificationManager.IMPORTANCE_LOW),
                ch(CHANNEL_SENDING, R.string.channel_sending, R.string.channel_sending_desc, NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun openApp(context: Context, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            extras()
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun uploadProgress(context: Context, title: String, text: String, progress: Float?): Notification =
        NotificationCompat.Builder(context, CHANNEL_SENDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
            .setContentIntent(openApp(context, 1) { putExtra(EXTRA_TARGET, TARGET_HISTORY) })
            .build()

    fun uploadFailed(context: Context, sendKey: String, text: String) {
        post(context, sendKey.hashCode(), NotificationCompat.Builder(context, CHANNEL_SENDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.outbox_failed))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 2) { putExtra(EXTRA_TARGET, TARGET_HISTORY) })
            .build())
    }

    /** A push from the server: `{type, title, body, ids}` (README §6.3). */
    fun showPush(context: Context, msg: PushMessage) {
        val channel = when (msg.type) {
            "join_request" -> CHANNEL_PEOPLE
            "reaction", "reply" -> CHANNEL_ACTIVITY
            else -> CHANNEL_FRAMES
        }
        val title = msg.title.ifBlank {
            context.getString(
                when (msg.type) {
                    "reaction" -> R.string.notif_reaction_title
                    "reply" -> R.string.notif_reply_title
                    "join_request" -> R.string.notif_join_title
                    else -> R.string.app_name
                },
            )
        }
        val key = msg.ids["send_id"] ?: msg.ids["placement_id"] ?: msg.ids["request_id"] ?: msg.ids["frame_id"] ?: msg.type
        val intent = openApp(context, key.hashCode()) {
            when (msg.type) {
                "join_request" -> putExtra(EXTRA_TARGET, TARGET_PEOPLE)
                "membership" , "membership.updated" -> putExtra(EXTRA_TARGET, TARGET_FRAME)
                else -> putExtra(EXTRA_TARGET, TARGET_HISTORY)
            }
            msg.ids["send_id"]?.let { putExtra(EXTRA_SEND_ID, it) }
            msg.ids["frame_id"]?.let { putExtra(EXTRA_FRAME_ID, it) }
            msg.ids["placement_id"]?.let { putExtra(EXTRA_PLACEMENT_ID, it) }
        }
        post(context, (msg.type + key).hashCode(), NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(msg.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    private fun post(context: Context, id: Int, n: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
