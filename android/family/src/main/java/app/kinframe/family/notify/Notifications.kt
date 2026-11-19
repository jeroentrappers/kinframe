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
    const val TARGET_CALENDAR = "calendar"
    const val EXTRA_FEED_ID = "app.kinframe.family.FEED_ID"
    const val EXTRA_POST_ID = "app.kinframe.family.POST_ID"
    /** A feed (its posts, or its people / frames for requests). */
    const val TARGET_FEED = "feed"
    const val TARGET_FEED_PEOPLE = "feed_people"
    const val TARGET_FEED_FRAMES = "feed_frames"
    /** A frame's Feeds tab (its subscriptions). */
    const val TARGET_FRAME_FEEDS = "frame_feeds"
    /** A feed's Birthdays tab (docs/spec/birthdays.md §8). */
    const val TARGET_FEED_BIRTHDAYS = "feed_birthdays"

    /** Push keys for feeds (README §6.3). */
    const val PUSH_SUBSCRIPTION_REQUEST = "subscription_request"
    const val PUSH_SUBSCRIPTION_DECIDED = "subscription_decided"
    const val PUSH_SUBSCRIPTION_REMOVED = "subscription_removed"
    const val PUSH_FEED_JOIN_REQUEST = "feed_join_request"
    const val PUSH_FEED_MEMBERSHIP = "feed_membership"

    const val EXTRA_SOURCE_ID = "app.kinframe.family.SOURCE_ID"
    const val EXTRA_IMPORT_ID = "app.kinframe.family.IMPORT_ID"
    /** Settings → My photo sources (the owner of a source that needs attention). */
    const val TARGET_PHOTO_SOURCES = "photo_sources"
    /** A finished Google Photos import's result. */
    const val TARGET_GOOGLE_IMPORT = "google_import"

    /** Push keys for photo sources (README §6.3, photo-sources.md §10). */
    const val PUSH_PHOTO_SOURCE_ATTENTION = app.kinframe.core.api.PhotoSourcePush.ATTENTION
    const val PUSH_GOOGLE_IMPORT_DONE = app.kinframe.core.api.PhotoSourcePush.GOOGLE_IMPORT_DONE

    /** Push keys for birthdays (README §6.3, birthdays.md §8). */
    const val PUSH_BIRTHDAY_SUGGESTION = "birthday_suggestion"
    const val PUSH_BIRTHDAY_DECIDED = "birthday_decided"
    const val PUSH_BIRTHDAY_TODAY = "birthday_today"
    const val PUSH_BIRTHDAY_GREETING = "birthday_greeting"

    fun isBirthday(kind: String): Boolean =
        kind == PUSH_BIRTHDAY_SUGGESTION || kind == PUSH_BIRTHDAY_DECIDED || kind == PUSH_BIRTHDAY_TODAY || kind == PUSH_BIRTHDAY_GREETING ||
            kind == "birthday_approved" || kind == "birthday_rejected"

    /**
     * Greetings for the same person and day replace each other on the phone (birthdays.md §7): the server writes the
     * running count in the body ("Opa's frame and 2 other frames…"), so only the newest is kept. The server's top-level
     * `tag` wins; otherwise it is built from `ids` (the push's `date`, else today's local date).
     */
    fun tagOf(msg: PushMessage, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        if (msg.tag.isNotBlank()) return msg.tag
        if (msg.kind != PUSH_BIRTHDAY_GREETING) return null
        val id = msg.ids["birthday_id"] ?: return null
        return "birthday:$id:${msg.ids["date"] ?: today}"
    }

    /** Where a tap on a push goes. */
    fun targetOf(msg: PushMessage): String = when (msg.kind) {
        "join_request" -> TARGET_PEOPLE
        "membership", "membership.updated" -> TARGET_FRAME
        "event_invitation.decided" -> TARGET_CALENDAR
        PUSH_SUBSCRIPTION_REQUEST -> TARGET_FEED_FRAMES
        PUSH_FEED_JOIN_REQUEST -> TARGET_FEED_PEOPLE
        PUSH_FEED_MEMBERSHIP -> TARGET_FEED
        PUSH_BIRTHDAY_SUGGESTION, PUSH_BIRTHDAY_DECIDED, PUSH_BIRTHDAY_TODAY, PUSH_BIRTHDAY_GREETING, "birthday_approved", "birthday_rejected" ->
            if (msg.ids["feed_id"] != null) TARGET_FEED_BIRTHDAYS else TARGET_HISTORY
        // The requester (decided) and the frame side (removed) look at the frame's subscriptions.
        PUSH_SUBSCRIPTION_DECIDED, PUSH_SUBSCRIPTION_REMOVED -> if (msg.ids["frame_id"] != null) TARGET_FRAME_FEEDS else TARGET_FEED
        // Reactions and replies on a feed post open the post's receipts; others the send.
        "reaction", "reply" -> if (msg.ids["feed_id"] != null && msg.ids["post_id"] != null) TARGET_FEED else TARGET_HISTORY
        PUSH_PHOTO_SOURCE_ATTENTION -> TARGET_PHOTO_SOURCES
        PUSH_GOOGLE_IMPORT_DONE -> TARGET_GOOGLE_IMPORT
        else -> TARGET_HISTORY
    }

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
        val channel = when (msg.kind) {
            "join_request", PUSH_SUBSCRIPTION_REQUEST, PUSH_FEED_JOIN_REQUEST, PUSH_FEED_MEMBERSHIP, PUSH_BIRTHDAY_SUGGESTION -> CHANNEL_PEOPLE
            "reaction", "reply", PUSH_BIRTHDAY_DECIDED, PUSH_BIRTHDAY_TODAY, PUSH_BIRTHDAY_GREETING, "birthday_approved", "birthday_rejected",
            PUSH_GOOGLE_IMPORT_DONE -> CHANNEL_ACTIVITY
            else -> CHANNEL_FRAMES
        }
        val title = msg.title.ifBlank {
            context.getString(
                when (msg.kind) {
                    "reaction" -> R.string.notif_reaction_title
                    "reply" -> R.string.notif_reply_title
                    "join_request" -> R.string.notif_join_title
                    PUSH_SUBSCRIPTION_REQUEST -> R.string.notif_subscription_request_title
                    PUSH_SUBSCRIPTION_DECIDED -> R.string.notif_subscription_decided_title
                    PUSH_SUBSCRIPTION_REMOVED -> R.string.notif_subscription_removed_title
                    PUSH_FEED_JOIN_REQUEST -> R.string.notif_feed_join_title
                    PUSH_FEED_MEMBERSHIP -> R.string.notif_feed_membership_title
                    PUSH_BIRTHDAY_SUGGESTION -> R.string.notif_birthday_suggestion_title
                    PUSH_BIRTHDAY_DECIDED, "birthday_approved", "birthday_rejected" -> R.string.notif_birthday_decided_title
                    PUSH_BIRTHDAY_TODAY -> R.string.notif_birthday_today_title
                    PUSH_BIRTHDAY_GREETING -> R.string.notif_birthday_greeting_title
                    PUSH_PHOTO_SOURCE_ATTENTION -> R.string.notif_photo_source_title
                    PUSH_GOOGLE_IMPORT_DONE -> R.string.notif_google_done_title
                    else -> R.string.app_name
                },
            )
        }
        val key = msg.ids["send_id"] ?: msg.ids["placement_id"] ?: msg.ids["request_id"] ?: msg.ids["subscription_id"]
            ?: msg.ids["source_id"] ?: msg.ids["import_id"]
            ?: msg.ids["greeting_id"] ?: msg.ids["birthday_id"]
            ?: msg.ids["post_id"] ?: msg.ids["feed_id"] ?: msg.ids["frame_id"] ?: msg.kind
        val intent = openApp(context, key.hashCode()) {
            putExtra(EXTRA_TARGET, targetOf(msg))
            msg.ids["send_id"]?.let { putExtra(EXTRA_SEND_ID, it) }
            msg.ids["frame_id"]?.let { putExtra(EXTRA_FRAME_ID, it) }
            msg.ids["placement_id"]?.let { putExtra(EXTRA_PLACEMENT_ID, it) }
            msg.ids["feed_id"]?.let { putExtra(EXTRA_FEED_ID, it) }
            msg.ids["post_id"]?.let { putExtra(EXTRA_POST_ID, it) }
            msg.ids["source_id"]?.let { putExtra(EXTRA_SOURCE_ID, it) }
            msg.ids["import_id"]?.let { putExtra(EXTRA_IMPORT_ID, it) }
        }
        val tag = tagOf(msg)
        post(context, if (tag != null) 1 else (msg.kind + key).hashCode(), tag = tag, n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(msg.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    private fun post(context: Context, id: Int, n: Notification, tag: String? = null) {
        if (!canPost(context)) return
        try {
            if (tag != null) NotificationManagerCompat.from(context).notify(tag, id, n) else NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
