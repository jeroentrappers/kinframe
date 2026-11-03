package app.kinframe.frame.calendar

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.FrameCalendar
import app.kinframe.core.api.KinJson
import app.kinframe.core.util.AtomicFile
import app.kinframe.frame.FrameController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections

/**
 * The frame side of calendars (product spec 4.9, README §5.8). The server syncs and merges every calendar; this keeps
 * the last `calendar` change (persisted with the change feed), opens and closes the full-screen calendar (by hand,
 * from the clock or quick menu, or by a timed-view schedule), and handles event invitations and countdowns. Writes
 * go through the outbox, so they survive a dropped connection.
 */
class CalendarController(private val c: FrameController) {
    private val g = c.g

    /** Local countdown choices until the server's next `calendar` change confirms them. */
    private val countdownOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val parsed: StateFlow<CalendarData?> by lazy {
        combine(
            c.syncState.map { it?.calendarJson }.distinctUntilChanged(),
            g.settings.settings.map { it.locale.timezone }.distinctUntilChanged(),
        ) { json, _ ->
            countdownOverrides.value = emptyMap()
            FrameCalendar.parse(json)?.let { CalendarData.from(it, g.clock.zone) }
        }.flowOn(Dispatchers.Default).stateIn(c.scope, SharingStarted.Eagerly, null)
    }

    /** The merged calendar to show; null when the frame has no calendars (or none synced yet). */
    val data: StateFlow<CalendarData?> by lazy {
        combine(parsed, countdownOverrides) { d, o -> o.entries.fold(d ?: return@combine null) { acc, (id, on) -> acc.withCountdown(id, on) } }
            .stateIn(c.scope, SharingStarted.Eagerly, null)
    }

    val zone: ZoneId get() = g.clock.zone

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open
    private var openByTimer = false
    /** Start of a timed-view window the person closed by hand: it stays closed until the next window. */
    private var dismissedWindow: LocalDateTime? = null

    private val _detail = MutableStateFlow<CalEvent?>(null)
    val detail: StateFlow<CalEvent?> = _detail

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _invitations = MutableStateFlow<List<EventInvitation>>(emptyList())
    val invitations: StateFlow<List<EventInvitation>> = _invitations
    private val _focused = MutableStateFlow<EventInvitation?>(null)
    val focused: StateFlow<EventInvitation?> = _focused
    private val decided = HashSet<String>()
    private val seenCards = HashSet<String>()

    private val nudge = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        c.scope.launch {
            while (true) {
                runCatching { evaluateTimedView() }.onFailure { Log.w(TAG, "timed view", it) }
                withTimeoutOrNull(30_000) { nudge.receive() }
            }
        }
        c.scope.launch { g.settings.settings.map { it.calendar.timedViews }.distinctUntilChanged().collect { nudge.trySend(Unit) } }
    }

    // ------------------------------------------------------------------------------------ full-screen calendar

    fun openCalendar() {
        openByTimer = false
        _open.value = true
    }

    fun closeCalendar() {
        if (openByTimer) {
            val now = LocalDateTime.ofInstant(g.clock.now(), zone)
            dismissedWindow = TimedView.active(g.settings.settings.value.calendar.timedViews, now)?.start
        }
        openByTimer = false
        _open.value = false
        _detail.value = null
    }

    private fun evaluateTimedView() {
        val schedules = g.settings.settings.value.calendar.timedViews
        val now = LocalDateTime.ofInstant(g.clock.now(), zone)
        val window = TimedView.active(schedules, now)
        if (window == null) dismissedWindow = null
        val touched = c.display.lastTouchElapsed
        val since = if (touched == 0L) null else SystemClock.elapsedRealtime() - touched
        when (TimedView.decide(window != null, openByTimer, _open.value, since, dismissed = window != null && window.start == dismissedWindow)) {
            TimedView.Action.Open -> {
                openByTimer = true
                _open.value = true
            }
            TimedView.Action.Close -> {
                openByTimer = false
                _open.value = false
                _detail.value = null
            }
            TimedView.Action.None -> Unit
        }
    }

    fun showEvent(e: CalEvent?) { _detail.value = e }

    /** "Sync now": the server re-syncs every calendar; a `calendar` change follows when something changed. */
    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        c.scope.launch {
            try {
                runCatching { g.api.syncCalendars() }.onFailure { Log.i(TAG, "calendar sync: ${it.message}") }
                c.session.value?.requestPull()
                delay(4_000)
                c.session.value?.requestPull()
            } finally {
                _syncing.value = false
            }
        }
    }

    fun setCountdown(e: CalEvent, on: Boolean) {
        countdownOverrides.update { it + (e.id to on) }
        _detail.update { d -> if (d?.id == e.id) d.copy(countdown = on) else d }
        c.scope.launch { c.session.value?.outbox?.countdown(e.id, on) }
    }

    // ------------------------------------------------------------------------------------ invitations

    /** A websocket `event_invitation`: show the card (and wake an idle screen, never during scheduled sleep). */
    fun onInvitationEvent(json: JsonObject) {
        val inv = runCatching { KinJson.decodeFromJsonElement(EventInvitation.serializer(), json) }.getOrNull() ?: return
        if (inv.id in decided || inv.status != "pending") return
        _invitations.update { list -> list.filter { it.id != inv.id } + inv }
        _focused.value = inv
        seenCards += inv.id
        c.display.onNewPhotos()
    }

    /** Re-reads pending invitations (on connect and after syncing), so none is lost while offline. */
    fun refreshInvitations() {
        c.scope.launch {
            val server = try { g.api.invitations() } catch (e: Exception) { Log.i(TAG, "invitations: ${e.message}"); return@launch }
            val ids = server.map { it.id }.toSet()
            decided.retainAll(ids)
            val fresh = server.filter { it.id !in decided && it.status == "pending" }
            val before = _invitations.value.map { it.id }.toSet()
            _invitations.value = fresh
            when {
                _focused.value?.id?.let { it !in ids } == true -> _focused.value = null
                _focused.value == null -> fresh.firstOrNull { it.id !in before && it.id !in seenCards }?.let { _focused.value = it }
            }
            fresh.forEach { seenCards += it.id }
        }
    }

    fun decide(inv: EventInvitation, accept: Boolean) {
        decided += inv.id
        _invitations.update { list -> list.filter { it.id != inv.id } }
        if (_focused.value?.id == inv.id) _focused.value = null
        c.scope.launch { c.session.value?.outbox?.invitationDecision(inv.id, accept) }
    }

    fun later() { _focused.value = null }

    fun reopen() { _focused.value = _invitations.value.firstOrNull() }

    fun clear() {
        _invitations.value = emptyList()
        _focused.value = null
        decided.clear()
        _open.value = false
        _detail.value = null
    }

    // ------------------------------------------------------------------------------------ photos

    private val photos = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
    })

    /** The photo of an invitation (or received event), cached on disk and decoded at most 1280 px wide. */
    suspend fun photo(invitationId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        photos[invitationId]?.let { return@withContext it }
        val dir = File(g.app.filesDir, "invitations").apply { mkdirs() }
        val f = File(dir, "$invitationId.webp")
        if (!f.exists()) {
            runCatching {
                g.api.media(g.api.invitationPhotoUrl(invitationId)).use { r -> if (r.isSuccessful) AtomicFile.write(f, r.body.bytes()) }
            }.onFailure { Log.i(TAG, "invitation photo: ${it.message}") }
        }
        if (!f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { photos[invitationId] = it }
    }

    private companion object { const val TAG = "KinCalendar" }
}
