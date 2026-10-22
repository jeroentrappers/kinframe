package app.kinframe.frame.join

import android.util.Log
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.KinJson
import app.kinframe.frame.FrameController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A join request as the frame shows it. */
data class JoinRequestUi(
    val id: String,
    val userId: String?,
    val name: String,
    val avatarUrl: String?,
    val invitedBy: String?,
    val note: String?,
    val createdAtMs: Long?,
) {
    companion object {
        fun of(r: JoinRequest, fallbackName: String) = JoinRequestUi(
            id = r.id,
            userId = r.user.id,
            name = r.user.displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
            avatarUrl = r.user.avatarUrl,
            invitedBy = r.invitedByName?.takeIf { r.via == "invite" && it.isNotBlank() },
            note = r.note?.takeIf { it.isNotBlank() },
            createdAtMs = r.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }
}

/**
 * Join requests waiting for a decision on the frame ("ask on the frame before someone new joins", product spec 4.2).
 *
 * A `join_request` websocket event shows the request as a full-screen card ([focused]); "Not now" leaves it in the
 * notifications list ([pending]) until someone decides, here or in the app. The list is re-read from the server on
 * connect and after each sync, so requests are neither lost while offline nor kept after the owner decided elsewhere.
 * Decisions go through the outbox, so they survive a dropped connection.
 */
class JoinRequests(private val c: FrameController, private val fallbackName: () -> String) {
    private val _pending = MutableStateFlow<List<JoinRequestUi>>(emptyList())
    val pending: StateFlow<List<JoinRequestUi>> = _pending
    private val _focused = MutableStateFlow<JoinRequestUi?>(null)
    val focused: StateFlow<JoinRequestUi?> = _focused
    /** Decided here but maybe not yet confirmed by the server: never show these again. */
    private val decided = HashSet<String>()

    /** A `join_request` websocket event. */
    fun onEvent(request: JsonObject) {
        val r = runCatching { KinJson.decodeFromJsonElement(JoinRequest.serializer(), request) }.getOrNull() ?: return
        if (r.id in decided) return
        val ui = JoinRequestUi.of(r, fallbackName())
        _pending.update { list -> list.filter { it.id != ui.id } + ui }
        _focused.value = ui
        c.display.onNewPhotos() // wakes an idle screen (never during scheduled sleep)
    }

    /** Replaces the list with the server's pending requests (README §6.1). */
    fun refresh() {
        val api = c.g.api
        c.scope.launch {
            val server = try { api.joinRequests() } catch (e: Exception) { Log.i(TAG, "join requests: ${e.message}"); return@launch }
            val ids = server.map { it.id }.toSet()
            decided.retainAll(ids)
            val fresh = server.filter { it.id !in decided }.map { JoinRequestUi.of(it, fallbackName()) }
            val before = _pending.value.map { it.id }.toSet()
            _pending.value = fresh
            when {
                _focused.value?.id?.let { it !in ids } == true -> _focused.value = null
                // Something arrived while the frame was offline: show it once.
                _focused.value == null -> fresh.firstOrNull { it.id !in before && it.id !in seenCards }?.let { _focused.value = it }
            }
            fresh.forEach { seenCards += it.id }
        }
    }

    private val seenCards = HashSet<String>()

    fun decide(id: String, approve: Boolean) {
        decided += id
        _pending.update { list -> list.filter { it.id != id } }
        if (_focused.value?.id == id) _focused.value = null
        c.scope.launch { c.session.value?.outbox?.joinDecision(id, approve) }
    }

    /** "Not now": close the card, keep it in the notifications list. */
    fun later() {
        _focused.value?.let { seenCards += it.id }
        _focused.value = null
    }

    fun open(id: String) { _focused.value = _pending.value.firstOrNull { it.id == id } }

    fun clear() {
        _pending.value = emptyList()
        _focused.value = null
        decided.clear()
    }

    private companion object { const val TAG = "KinJoin" }
}
