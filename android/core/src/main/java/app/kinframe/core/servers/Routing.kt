package app.kinframe.core.servers

import app.kinframe.core.api.Discovery
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.FeedPostsPage
import app.kinframe.core.api.Frame
import app.kinframe.core.api.Item
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementsPage
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendsPage
import app.kinframe.core.api.Subscription
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.social.Circle
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.Message
import app.kinframe.core.social.MessagePage
import app.kinframe.core.social.Person
import app.kinframe.core.social.SocialApi
import app.kinframe.core.social.TimelinePage
import app.kinframe.core.social.TimelinePost
import kotlinx.coroutines.async

/**
 * One signed-in server as the routing clients see it (docs/spec/multi-server.md §2.1): its clients and discovery.
 * [id] is the app's local account id.
 */
interface ServerRoute {
    val id: String
    val kin: KinApi
    val social: SocialApi?
    val leaving: LeavingApi?
    val remote: RemoteApi?
    val discovery: Discovery
}

/**
 * Which account each id belongs to (frames, families, sends, posts, conversations, people…), learned from what the
 * servers answer, so a detail screen opened from a merged list talks to its own server. Ids are random UUIDs, so ids
 * of two servers don't collide in practice; when one is unknown the primary account answers. Bounded (least
 * recently used out first); [onChange] lets the app keep it across restarts (notification taps after the process died).
 */
class AccountIndex(private val capacity: Int = 5000, var onChange: (() -> Unit)? = null) {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized fun get(id: String?): String? = id?.let { map[it] }

    fun put(id: String?, account: String) {
        if (id.isNullOrBlank()) return
        val changed = synchronized(this) { map.put(id, account) != account }
        if (changed) onChange?.invoke()
    }

    fun putAll(ids: Collection<String?>, account: String) {
        var changed = false
        synchronized(this) { ids.forEach { if (!it.isNullOrBlank() && map.put(it, account) != account) changed = true } }
        if (changed) onChange?.invoke()
    }

    /** Forgets everything of an account (signed out). */
    fun forget(account: String) {
        synchronized(this) { map.values.removeAll { it == account } }
        onChange?.invoke()
    }

    @Synchronized fun snapshot(): Map<String, String> = LinkedHashMap(map)

    @Synchronized fun restore(entries: Map<String, String>) { map.clear(); map.putAll(entries) }

    /** Records the ids in an answer of [account]'s server. Unknown types are ignored. */
    fun learn(account: String, value: Any?) {
        val ids = mutableListOf<String?>()
        collect(value, ids, 0)
        if (ids.isNotEmpty()) putAll(ids, account)
    }

    private fun collect(v: Any?, out: MutableList<String?>, depth: Int) {
        if (depth > 3) return
        when (v) {
            null -> Unit
            is Collection<*> -> v.forEach { collect(it, out, depth + 1) }
            is Frame -> out += v.id
            is Feed -> out += v.id
            is Send -> { out += v.id; v.items.forEach { out += it.id }; v.frames.forEach { out += it.frameId } }
            is SendsPage -> collect(v.sends, out, depth + 1)
            is Item -> out += v.id
            is Placement -> { out += v.id; out += v.item.id }
            is PlacementsPage -> collect(v.placements, out, depth + 1)
            is Subscription -> out += v.id
            is FeedPost -> { out += v.id; v.items.forEach { out += it.id } }
            is FeedPostsPage -> collect(v.posts, out, depth + 1)
            is TimelinePost -> { out += v.id; out += v.feedId; out += v.frameId; out += v.circleId; out += v.poster.userId; v.items.forEach { out += it.id } }
            is TimelinePage -> collect(v.posts, out, depth + 1)
            is Conversation -> { out += v.id; out += v.other?.userId; v.members.forEach { out += it.person.userId } }
            is Message -> { out += v.id; out += v.author?.userId }
            is MessagePage -> collect(v.messages, out, depth + 1)
            is Person -> out += v.person.userId
            is Circle -> out += v.id
            is app.kinframe.core.social.Report -> { out += v.id; out += v.postId; out += v.conversationId; out += v.feedId; out += v.author?.userId }
            is app.kinframe.core.social.Block -> out += v.person.userId
            is app.kinframe.core.api.Album -> out += v.id
            is app.kinframe.core.api.PhotoSource -> { out += v.id; out += v.target.albumId; out += v.target.feedId; out += v.target.frameId }
            is app.kinframe.core.api.GoogleImport -> out += v.id
            is app.kinframe.core.social.PhotoRequest -> { out += v.id; out += v.frameId; out.addAll(v.feedIds) }
            is app.kinframe.core.social.OnThisDay -> collect(v.groups, out, depth + 1)
            is app.kinframe.core.social.OnThisDayGroup -> { out += v.feedId; out.addAll(v.postIds); v.items.forEach { out += it.id } }
            else -> Unit
        }
    }
}

/** The accounts the routing clients choose from. */
class Routes(
    /** Signed-in accounts, primary first. */
    val all: () -> List<ServerRoute>,
    val index: AccountIndex,
) {
    val primary: ServerRoute? get() = all().firstOrNull()

    /** The account that [key] belongs to, else the primary one. */
    fun forKey(key: String?): ServerRoute {
        val list = all()
        val id = index.get(key)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: throw java.io.IOException("not signed in")
    }

    fun byId(account: String?): ServerRoute? = all().firstOrNull { it.id == account }

    /** More than one server: lists show a server chip. */
    val several: Boolean get() = all().size > 1

    /**
     * Only [account] (for an action that names no id of its server yet: a new family, a typed frame code, a photo
     * source of that account). Everything goes to that account and what it answers is recorded for it.
     */
    fun only(account: String): Routes = Routes({ all().filter { it.id == account } }, index)
}

/**
 * Runs [block] on every account and keeps the answers that came back; throws only when every account failed (the
 * first error), so one server being down never empties a merged list.
 */
suspend fun <T> Routes.each(accounts: List<ServerRoute> = all(), block: suspend (ServerRoute) -> T): List<Pair<ServerRoute, T>> =
    kotlinx.coroutines.coroutineScope {
        val jobs = accounts.map { r -> r to async { runCatching { block(r) } } }
        val results = jobs.map { (r, d) -> r to d.await() }
        val ok = results.mapNotNull { (r, res) -> res.getOrNull()?.let { r to it } }
        if (ok.isEmpty() && results.isNotEmpty()) throw results.first().second.exceptionOrNull()!!
        ok.onEach { (r, v) -> index.learn(r.id, v) }
    }
