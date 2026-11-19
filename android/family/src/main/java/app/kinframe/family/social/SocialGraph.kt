package app.kinframe.family.social

import android.net.Uri
import app.kinframe.core.api.Discovery
import app.kinframe.core.social.Badge
import app.kinframe.core.servers.RoutingSocialApi
import app.kinframe.core.social.Mention
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.SocialApi
import app.kinframe.family.AppGraph
import app.kinframe.family.BuildConfig
import app.kinframe.family.auth.AuthState
import app.kinframe.family.data.SocialLive
import app.kinframe.family.notify.SocialNotifications
import app.kinframe.family.send.MediaFiles
import app.kinframe.family.send.OutboxChat
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.SendRunner
import app.kinframe.family.send.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * The family social part of the app's object graph (docs/spec/family-social.md): the social API client, the app
 * badge, chat messages with media through the outbox, and the open conversation (whose pushes are not shown).
 */
class SocialGraph(private val graph: AppGraph) {
    private var cache: Pair<Any, RoutingSocialApi>? = null

    /**
     * The client while the primary account is signed in, else null. Across several accounts it merges Home, Chats
     * and the badge and sends every post or chat call to its own server (multi-server.md §2.2).
     */
    val api: StateFlow<SocialApi?> = graph.api.map { k ->
        val primary = graph.accounts.primary.value.clients.value
        if (k == null || primary == null) null
        else cache?.takeIf { it.first === k }?.second
            ?: RoutingSocialApi(graph.accounts.routes, primary.social).also { cache = k to it }
    }.stateIn(graph.scope, SharingStarted.Eagerly, null)

    /**
     * The merged areas' flags: Home, Chats and the badge show when any signed-in account's server has them
     * (multi-server.md §2.2); each detail screen checks its own server's flags.
     */
    val flags: app.kinframe.family.ui.SocialFlags get() {
        val all: List<Discovery> = graph.accounts.signedIn.mapNotNull { it.server?.discovery }
            .ifEmpty { listOfNotNull(graph.server.value?.discovery) }
        return app.kinframe.family.ui.SocialFlags.any(all)
    }

    val socialOn: Boolean get() = flags.social
    val chatsOn: Boolean get() = flags.chats
    val engagementOn: Boolean get() = flags.engagement

    private val _badge = MutableStateFlow(Badge())
    /** The app badge (`getBadge`, `badge.updated`): unread chats and threads with unread mentions or replies. */
    val badge: StateFlow<Badge> = _badge.asStateFlow()

    /** The conversation on screen: its `message` pushes are dropped and its notification is cleared. */
    @Volatile var openConversation: String? = null

    init {
        graph.scope.launch {
            graph.live.events.collect { e ->
                when (e) {
                    // One account's badge: with several accounts the app badge is their sum (README §5.22).
                    is SocialLive.BadgeUpdated -> if (graph.accounts.several) refreshBadge() else setBadge(e.badge)
                    is SocialLive.MessageCreated, is SocialLive.ConversationUpdated, is SocialLive.ThreadRead -> Unit
                    else -> Unit
                }
            }
        }
        graph.scope.launch { api.collect { if (it != null) refreshBadge() else _badge.value = Badge() } }
        graph.scope.launch { graph.pushes.collect { if (SocialNotifications.handles(it.kind)) refreshBadge() } }
    }

    private fun setBadge(b: Badge) {
        _badge.value = b
        SocialNotifications.badgeChanged(graph.app, b)
    }

    fun refreshBadge() {
        if (!socialOn && !chatsOn) return
        val a = api.value ?: return
        graph.scope.launch { runCatching { a.badge() }.onSuccess { setBadge(it) } }
    }

    /** Posts the message of a chat send whose files are uploaded (the outbox calls it, [SendRunner]). */
    val chatSender: suspend (OutboxSend) -> String = { send ->
        val a = api.value ?: throw java.io.IOException("not signed in")
        val chat = requireNotNull(send.chat)
        a.sendMessage(chat.conversationId, OutboxChat.message(send), idempotencyKey = send.id).id
    }

    /**
     * Queues photos and videos for a chat message: the files stay readable after the screen closes (persisted grants
     * or a private copy) and upload in the background like a send. Returns the outbox id (the message's `client_id`).
     */
    suspend fun queueChatMedia(
        conversationId: String, conversationName: String, uris: List<Uri>, body: String?, mentions: List<Mention>, replyToId: String?,
    ): String? = withContext(Dispatchers.IO) {
        val ctx = graph.app
        val id = UUID.randomUUID().toString()
        val dir = SendRunner.outboxDir(ctx, id)
        val items = uris.mapNotNull { uri ->
            val info = MediaFiles.inspect(ctx, uri) ?: return@mapNotNull null
            val itemId = UUID.randomUUID().toString()
            val base = OutboxItem(id = itemId, uri = uri.toString(), mime = info.mime, name = info.name, size = info.size, kind = info.kind, motionPhoto = info.motionPhoto)
            if (MediaFiles.persist(ctx.contentResolver, uri)) base
            else {
                val ext = info.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.let { ".$it" }.orEmpty()
                val target = File(dir, itemId + ext)
                val n = MediaFiles.copy(ctx.contentResolver, uri, target)
                base.copy(localPath = target.absolutePath, size = n)
            }
        }
        if (items.isEmpty()) return@withContext null
        enqueue(OutboxSend(
            id = id, server = serverOf(conversationId), frameIds = emptyList(), frameNames = emptyList(),
            items = items, createdAt = System.currentTimeMillis(),
            chat = OutboxChat(conversationId, conversationName, body, mentions, replyToId),
        ))
        id
    }

    /** Queues a finished voice recording ([file], AAC in MPEG-4) for a chat. */
    suspend fun queueVoice(conversationId: String, conversationName: String, file: File, durationMs: Long, replyToId: String?): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val dir = SendRunner.outboxDir(graph.app, id).apply { mkdirs() }
            val target = File(dir, "voice.m4a")
            if (!file.renameTo(target)) { file.copyTo(target, overwrite = true); file.delete() }
            val item = OutboxItem(
                id = UUID.randomUUID().toString(), uri = Uri.fromFile(target).toString(), localPath = target.absolutePath,
                mime = OutboxChat.VOICE_MIME, name = "voice.m4a", size = target.length(), kind = OutboxChat.VOICE_KIND,
                // A recording is already what the server stores (AAC mono): nothing to prepare.
                prepState = OutboxItem.PREP_AS_IS,
            )
            enqueue(OutboxSend(
                id = id, server = serverOf(conversationId), frameIds = emptyList(), frameNames = emptyList(),
                items = listOf(item), createdAt = System.currentTimeMillis(),
                chat = OutboxChat(conversationId, conversationName, replyToId = replyToId, voice = true, voiceDurationMs = durationMs),
            ))
            id
        }

    /** A chat lives on one server: its media upload there. */
    private fun serverOf(conversationId: String): String = graph.accounts.forKey(conversationId).server?.url?.toString().orEmpty()

    private fun enqueue(send: OutboxSend) {
        graph.outbox.add(send)
        SendWorker.enqueue(graph.app, send.id)
    }

    /** Where recordings are made before they are queued. */
    fun voiceDir(): File = File(graph.app.cacheDir, "voice").apply { mkdirs() }

    /**
     * The reminder notification buttons (README §6.3): Snooze 1 month, Fewer reminders, on the server the push came
     * from ([accountId]: the push's routed account — its payload `server`, else the endpoint's account).
     */
    suspend fun reminderAction(action: String, accountId: String?): Boolean = reminderAction(graph.accounts, action, accountId)

    companion object {
        /** For tests: the update a reminder button makes. */
        fun updateFor(action: String): ReminderPreferencesUpdate? = SocialNotifications.reminderUpdate(action)

        /**
         * A reminder button's change on [accountId]'s server (the primary one for notifications posted before the app
         * knew a push's account). Never another account's server: reminders are per server.
         */
        suspend fun reminderAction(accounts: app.kinframe.family.accounts.AccountManager, action: String, accountId: String?): Boolean {
            val update = SocialNotifications.reminderUpdate(action) ?: return false
            val account = accounts.byId(accountId?.ifBlank { null }) ?: accounts.primary.value
            val social = account.route()?.social ?: return false
            return runCatching { social.updateReminderPreferences(update) }.isSuccess
        }
    }
}
