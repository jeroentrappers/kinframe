package app.kinframe.family.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.kinframe.core.social.Badge
import app.kinframe.core.social.MessageCreate
import app.kinframe.core.social.ReminderLevel
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.SocialPush
import app.kinframe.family.FamilyApp
import app.kinframe.family.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

/**
 * Family social pushes (README §6.3): comments, replies, mentions, reactions, chat messages, reports, frame requests
 * and reminders. Each gets the server's `tag` (a newer push with the same tag replaces the older one), chats are
 * grouped per conversation with their recent lines, reminders get Snooze 1 month and Fewer reminders, and chat
 * messages can be answered from the notification.
 */
object SocialNotifications {
    const val CHANNEL_CHATS = "chats"
    const val CHANNEL_COMMENTS = "comments"
    const val CHANNEL_REMINDERS = "reminders"

    /** Where a tap goes (`OpenTarget.target`). */
    const val TARGET_POST = "social_post"
    const val TARGET_CONVERSATION = "social_conversation"
    const val TARGET_HOME = "social_home"
    const val TARGET_REPORTS = "social_reports"
    const val TARGET_WEEKLY_SUMMARY = "social_weekly_summary"
    const val TARGET_FRAME_REQUEST = "social_frame_request"

    /** Push `ids` beyond the ones the app already forwards; each goes into the tap's intent as `social.<key>`. */
    val EXTRA_ID_KEYS = setOf(
        "comment_id", "parent_id", "conversation_id", "message_id", "target_kind", "target_id", "circle_id", "report_id",
        "request_id", "week_of", "month", "date", "post_id", "feed_id", "frame_id", "birthday_id",
    )
    private const val EXTRA_PREFIX = "app.kinframe.family.social."

    const val ACTION_REMINDER = "app.kinframe.family.action.REMINDER"
    const val ACTION_REPLY = "app.kinframe.family.action.REPLY"
    const val ACTION_MARK_READ = "app.kinframe.family.action.MARK_READ"
    const val EXTRA_ACTION = "app.kinframe.family.social.ACTION"
    const val EXTRA_TAG = "app.kinframe.family.social.TAG"
    const val EXTRA_CONVERSATION = "app.kinframe.family.social.CONVERSATION"
    const val EXTRA_CONVERSATION_NAME = "app.kinframe.family.social.CONVERSATION_NAME"
    const val EXTRA_MESSAGE = "app.kinframe.family.social.MESSAGE"
    const val KEY_REPLY = "reply_text"

    /** Every notification of ours uses this id with its own tag. */
    private const val ID = 7100

    @Volatile private var badgeTotal = 0

    fun handles(kind: String): Boolean = kind in SocialPush.ALL

    fun channels(context: Context): List<NotificationChannel> {
        fun ch(id: String, name: Int, desc: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(desc) }
        return listOf(
            ch(CHANNEL_CHATS, R.string.chat_channel, R.string.chat_channel_desc, NotificationManager.IMPORTANCE_HIGH),
            ch(CHANNEL_COMMENTS, R.string.social_channel, R.string.social_channel_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CHANNEL_REMINDERS, R.string.engagement_channel, R.string.engagement_channel_desc, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(channels(context))
    }

    fun channelOf(kind: String): String = when (kind) {
        SocialPush.MESSAGE, SocialPush.CONVERSATION_ADDED -> CHANNEL_CHATS
        SocialPush.MENTION -> CHANNEL_CHATS
        SocialPush.FRAME_REQUEST, SocialPush.REMINDER_QUIET, SocialPush.REMINDER_STREAK, SocialPush.REMINDER_BIRTHDAY,
        SocialPush.WEEKLY_SUMMARY -> CHANNEL_REMINDERS
        else -> CHANNEL_COMMENTS
    }

    /** The server's tag, else the one README §6.3 gives the key. */
    fun tagOf(msg: PushMessage): String? {
        if (msg.tag.isNotBlank()) return msg.tag
        val ids = msg.ids
        return when (msg.kind) {
            SocialPush.COMMENT -> ids["post_id"]?.let { "comment:$it" }
            SocialPush.COMMENT_REPLY -> ids["parent_id"]?.let { "comment_reply:$it" }
            SocialPush.REACTION_PERSON -> ids["target_id"]?.let { "reaction:${ids["target_kind"]}:$it" }
            SocialPush.REACTION_DIGEST -> "reaction_digest"
            SocialPush.MESSAGE -> ids["conversation_id"]?.let { "conversation:$it" }
            SocialPush.FRAME_REQUEST -> ids["frame_id"]?.let { "frame_request:$it" }
            SocialPush.REMINDER_QUIET -> ids["frame_id"]?.let { "reminder_quiet:$it" }
            SocialPush.REMINDER_STREAK -> ids["feed_id"]?.let { "reminder_streak:$it" }
            SocialPush.REMINDER_BIRTHDAY -> ids["birthday_id"]?.let { "reminder_birthday:$it" }
            SocialPush.WEEKLY_SUMMARY -> "weekly_summary"
            // No tag: every mention, report and "added to a group" stands on its own.
            else -> null
        } ?: "${msg.kind}:${ids["comment_id"] ?: ids["message_id"] ?: ids["report_id"] ?: ids["conversation_id"] ?: UUID.randomUUID()}"
    }

    /** Where a tap on the push goes. */
    fun targetOf(msg: PushMessage): String = when (msg.kind) {
        SocialPush.COMMENT, SocialPush.COMMENT_REPLY -> TARGET_POST
        SocialPush.MENTION -> if (msg.ids["conversation_id"] != null) TARGET_CONVERSATION else TARGET_POST
        SocialPush.REACTION_PERSON -> when {
            msg.ids["conversation_id"] != null -> TARGET_CONVERSATION
            msg.ids["post_id"] != null -> TARGET_POST
            else -> TARGET_HOME
        }
        SocialPush.MESSAGE, SocialPush.CONVERSATION_ADDED -> TARGET_CONVERSATION
        SocialPush.REPORT -> TARGET_REPORTS
        SocialPush.WEEKLY_SUMMARY -> if (msg.ids["week_of"] != null) TARGET_WEEKLY_SUMMARY else TARGET_HOME
        SocialPush.FRAME_REQUEST, SocialPush.REMINDER_QUIET -> TARGET_FRAME_REQUEST
        else -> TARGET_HOME
    }

    /** The push's ids carried by a notification tap, back out of the intent. */
    fun idsFrom(intent: Intent): Map<String, String> =
        EXTRA_ID_KEYS.mapNotNull { k -> intent.getStringExtra(EXTRA_PREFIX + k)?.let { k to it } }.toMap()

    /** FCM data carries `actions` as a JSON array or a comma-separated list. */
    fun parseActions(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        if (s.startsWith("[")) return runCatching {
            val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        return s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** What a reminder button changes (README §5.20): 30 days of snooze, or the `fewer` level. */
    fun reminderUpdate(action: String): ReminderPreferencesUpdate? = when (action) {
        SocialPush.ACTION_SNOOZE_MONTH -> ReminderPreferencesUpdate(snoozeDays = 30)
        SocialPush.ACTION_FEWER_REMINDERS -> ReminderPreferencesUpdate(level = ReminderLevel.FEWER)
        else -> null
    }

    fun badgeChanged(context: Context, badge: Badge) {
        badgeTotal = badge.total
        // Nothing unread any more (read on another device): the chat notifications are stale.
        if (badge.conversations == 0) clearChats(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("kinframe_social_notifications", Context.MODE_PRIVATE)

    /** The recent lines shown in a conversation's notification, newest last. */
    private fun lines(context: Context, tag: String): List<String> = runCatching {
        val a = JSONArray(prefs(context).getString(tag, "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveLines(context: Context, tag: String, lines: List<String>) {
        val p = prefs(context).edit()
        if (lines.isEmpty()) p.remove(tag) else p.putString(tag, JSONArray(lines.takeLast(MAX_LINES)).toString())
        p.apply()
    }

    /** The conversation was opened: its notification and remembered lines go. */
    fun clearConversation(context: Context, conversationId: String) {
        val tag = "conversation:$conversationId"
        saveLines(context, tag, emptyList())
        runCatching { NotificationManagerCompat.from(context).cancel(tag, ID) }
    }

    /** A post's thread was opened: its comment and reply notifications go. */
    fun clearPost(context: Context, postId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel("comment:$postId", ID) }
    }

    private fun clearChats(context: Context) {
        val tags = prefs(context).all.keys.filter { it.startsWith("conversation:") }
        tags.forEach { t -> runCatching { NotificationManagerCompat.from(context).cancel(t, ID) } }
        prefs(context).edit().apply { tags.forEach { remove(it) } }.apply()
    }

    private fun broadcast(context: Context, action: String, code: Int, extras: Intent.() -> Unit): PendingIntent {
        val i = Intent(context, SocialActionReceiver::class.java).setAction(action).apply(extras)
        val mutable = if (action == ACTION_REPLY && android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** Shows a family social push. [openConversation] is the chat on screen (its messages aren't shown). */
    fun show(context: Context, msg: PushMessage, openConversation: String? = null) {
        val conversation = msg.ids["conversation_id"]
        if (msg.kind == SocialPush.MESSAGE && conversation != null && conversation == openConversation) return
        val tag = tagOf(msg) ?: return
        val title = msg.title.ifBlank { context.getString(titleOf(msg.kind)) }
        val intent = Notifications.openApp(context, tag.hashCode()) {
            putExtra(Notifications.EXTRA_TARGET, targetOf(msg))
            msg.ids.forEach { (k, v) -> if (k in EXTRA_ID_KEYS) putExtra(EXTRA_PREFIX + k, v) }
            msg.ids["post_id"]?.let { putExtra(Notifications.EXTRA_POST_ID, it) }
            msg.ids["feed_id"]?.let { putExtra(Notifications.EXTRA_FEED_ID, it) }
            msg.ids["frame_id"]?.let { putExtra(Notifications.EXTRA_FRAME_ID, it) }
        }
        val b = NotificationCompat.Builder(context, channelOf(msg.kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(msg.body)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .setNumber(badgeTotal)
            .setGroup(groupOf(msg))
        if (msg.kind == SocialPush.MESSAGE && conversation != null) {
            // One notification per conversation with its recent lines; the newest push replaces the older one.
            val lines = (lines(context, tag) + msg.body).takeLast(MAX_LINES)
            saveLines(context, tag, lines)
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.forEach { style.addLine(it) }
            if (lines.size > 1) style.setSummaryText(context.resources.getQuantityString(R.plurals.chat_notif_new, lines.size, lines.size))
            b.setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            val remote = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.chat_notif_reply_hint)).build()
            val reply = broadcast(context, ACTION_REPLY, ("reply$tag").hashCode()) {
                putExtra(EXTRA_TAG, tag); putExtra(EXTRA_CONVERSATION, conversation); putExtra(EXTRA_CONVERSATION_NAME, title)
            }
            b.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.chat_notif_reply), reply)
                .addRemoteInput(remote).setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
            msg.ids["message_id"]?.let { mid ->
                val read = broadcast(context, ACTION_MARK_READ, ("read$tag").hashCode()) {
                    putExtra(EXTRA_TAG, tag); putExtra(EXTRA_CONVERSATION, conversation); putExtra(EXTRA_MESSAGE, mid)
                }
                b.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.chat_notif_mark_read), read)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
            }
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
        }
        if (msg.kind in SocialPush.REMINDERS) {
            // The four reminder keys carry both buttons; older servers may not say so in `actions`.
            val actions = msg.actions.ifEmpty { listOf(SocialPush.ACTION_SNOOZE_MONTH, SocialPush.ACTION_FEWER_REMINDERS) }
            actions.forEach { a ->
                val label = when (a) {
                    SocialPush.ACTION_SNOOZE_MONTH -> R.string.engagement_action_snooze
                    SocialPush.ACTION_FEWER_REMINDERS -> R.string.engagement_action_fewer
                    else -> return@forEach
                }
                val pi = broadcast(context, ACTION_REMINDER, ("$a$tag").hashCode()) { putExtra(EXTRA_ACTION, a); putExtra(EXTRA_TAG, tag) }
                b.addAction(0, context.getString(label), pi)
            }
        }
        post(context, tag, b)
    }

    private fun groupOf(msg: PushMessage): String = when (msg.kind) {
        SocialPush.MESSAGE -> "kinframe.chat.${msg.ids["conversation_id"]}"
        SocialPush.CONVERSATION_ADDED -> "kinframe.chat"
        SocialPush.COMMENT, SocialPush.COMMENT_REPLY, SocialPush.MENTION, SocialPush.REACTION_PERSON ->
            msg.ids["conversation_id"]?.let { "kinframe.chat.$it" } ?: "kinframe.post.${msg.ids["post_id"]}"
        in SocialPush.REMINDERS, SocialPush.FRAME_REQUEST -> "kinframe.reminders"
        else -> "kinframe.social"
    }

    private fun titleOf(kind: String): Int = when (kind) {
        SocialPush.COMMENT -> R.string.social_notif_comment
        SocialPush.COMMENT_REPLY -> R.string.social_notif_reply
        SocialPush.MENTION -> R.string.social_notif_mention
        SocialPush.REACTION_PERSON, SocialPush.REACTION_DIGEST -> R.string.social_notif_reaction
        SocialPush.MESSAGE, SocialPush.CONVERSATION_ADDED -> R.string.chat_notif_message
        SocialPush.REPORT -> R.string.chat_notif_report
        SocialPush.FRAME_REQUEST -> R.string.engagement_notif_frame_request
        SocialPush.WEEKLY_SUMMARY -> R.string.engagement_notif_summary
        else -> R.string.engagement_notif_reminder
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    private fun post(context: Context, tag: String, b: NotificationCompat.Builder) {
        if (!Notifications.canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    /** After a reply from the notification: the line shows as sent and the reply field goes away. */
    internal fun replied(context: Context, tag: String, name: String, text: String, ok: Boolean) {
        val lines = (lines(context, tag) + context.getString(R.string.chat_notif_you, text)).takeLast(MAX_LINES)
        saveLines(context, tag, lines)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(name)
        lines.forEach { style.addLine(it) }
        val b = NotificationCompat.Builder(context, CHANNEL_CHATS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(if (ok) context.getString(R.string.chat_notif_you, text) else context.getString(R.string.chat_notif_reply_failed))
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 4000 else 0)
        post(context, tag, b)
    }

    internal fun cancel(context: Context, tag: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(tag, ID) }
    }

    private const val MAX_LINES = 6
}

/** Notification buttons: reminder Snooze / Fewer, chat Reply and Mark as read. */
class SocialActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as? FamilyApp)?.graph ?: return
        val tag = intent.getStringExtra(SocialNotifications.EXTRA_TAG).orEmpty()
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    SocialNotifications.ACTION_REMINDER -> {
                        val action = intent.getStringExtra(SocialNotifications.EXTRA_ACTION).orEmpty()
                        if (graph.social.reminderAction(action)) SocialNotifications.cancel(context, tag)
                    }
                    SocialNotifications.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SocialNotifications.KEY_REPLY)?.toString()?.trim().orEmpty()
                        val conversation = intent.getStringExtra(SocialNotifications.EXTRA_CONVERSATION) ?: return@launch
                        val name = intent.getStringExtra(SocialNotifications.EXTRA_CONVERSATION_NAME).orEmpty()
                        if (text.isEmpty()) return@launch
                        val id = UUID.randomUUID().toString()
                        val ok = runCatching {
                            graph.social.api.value!!.sendMessage(conversation, MessageCreate(clientId = id, body = text), idempotencyKey = id)
                        }.isSuccess
                        SocialNotifications.replied(context, tag, name, text, ok)
                    }
                    SocialNotifications.ACTION_MARK_READ -> {
                        val conversation = intent.getStringExtra(SocialNotifications.EXTRA_CONVERSATION) ?: return@launch
                        val message = intent.getStringExtra(SocialNotifications.EXTRA_MESSAGE) ?: return@launch
                        runCatching { graph.social.api.value?.markConversationRead(conversation, message) }
                        SocialNotifications.clearConversation(context, conversation)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
