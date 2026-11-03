package app.kinframe.family.ui.calendar

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarSource
import app.kinframe.core.api.CalendarSourceCreate
import app.kinframe.core.api.CalendarStatus
import app.kinframe.core.api.CalendarTestResult
import app.kinframe.core.api.CalendarView
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.KinApi
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

data class CalendarState(
    /** Calendars of the frame (managers only). */
    val sources: List<CalendarSource>? = null,
    /** What the frame shows, with its weather (managers only). */
    val view: CalendarView? = null,
    /** Events sent to the frame (senders see their own). */
    val invitations: List<EventInvitation>? = null,
    @StringRes val error: Int? = null,
    val busyCountdown: Boolean = false,
    val sending: Boolean = false,
    /** Upload progress of the event photo, 0..1. */
    val sendProgress: Float = 0f,
    @StringRes val sendError: Int? = null,
)

/** The add/edit calendar dialog. [source] is null when adding. */
data class SourceForm(
    val source: CalendarSource? = null,
    val kind: String = "ics",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val name: String = "",
    val color: String = "coral",
    val enabled: Boolean = true,
    val urlTouched: Boolean = false,
    val testing: Boolean = false,
    val result: CalendarTestResult? = null,
    val picked: List<String> = emptyList(),
    val saving: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val editing: Boolean get() = source != null
    val isLocal: Boolean get() = source?.isLocal == true
    val urlOk: Boolean get() = CalendarLogic.urlOk(url)
    val urlError: Boolean get() = urlTouched && url.isNotBlank() && !urlOk
    val calendars get() = if (result?.ok == true) result.calendars else emptyList()
    val multi: Boolean get() = kind == "caldav" && calendars.isNotEmpty()
    val canTest: Boolean get() = urlOk && (kind == "ics" || username.isNotBlank()) && !testing
    val canSave: Boolean get() = when {
        saving -> false
        editing -> url.isBlank() || urlOk
        !urlOk -> false
        kind == "caldav" -> username.isNotBlank() && password.isNotEmpty() && (!multi || picked.isNotEmpty())
        else -> true
    }
}

sealed interface CalendarNotice {
    /** Toast a string resource, formatted with [args]. */
    data class Message(@StringRes val message: Int, val args: List<Any> = emptyList()) : CalendarNotice
    data class Added(val count: Int) : CalendarNotice
    data class Error(@StringRes val message: Int) : CalendarNotice
}

/**
 * A frame's calendars (web `FrameCalendar.svelte`): the sources managers add, the merged preview with countdowns, and
 * the events people send to the frame. [uploadPhoto] prepares and uploads an event photo, returning its upload id.
 */
class CalendarViewModel(
    private val api: KinApi,
    private val frameId: String,
    private val manage: Boolean,
    private val mayPut: Boolean,
    live: Flow<LiveEvent>,
    private val uploadPhoto: suspend (Uri, (Float) -> Unit) -> String,
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()
    private val _form = MutableStateFlow<SourceForm?>(null)
    val form: StateFlow<SourceForm?> = _form.asStateFlow()
    private val _events = MutableSharedFlow<CalendarNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<CalendarNotice> = _events.asSharedFlow()
    private var poll: Job? = null

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                if (e !is LiveEvent.InvitationDecided || e.frameId != frameId) return@collect
                val inv = e.invitation
                _state.update { s ->
                    val list = s.invitations ?: return@update s
                    s.copy(invitations = if (list.any { it.id == inv.id }) list.map { if (it.id == inv.id) inv else it } else listOf(inv) + list)
                }
                if (manage && inv.status == "accepted") refresh()
            }
        }
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                val jobs = buildList {
                    if (manage) {
                        add(async { _state.update { s -> s.copy(sources = api.calendars(frameId)) } })
                        add(async { _state.update { s -> s.copy(view = api.frameCalendar(frameId)) } })
                    }
                    if (mayPut || manage) add(async { _state.update { s -> s.copy(invitations = api.eventInvitations(frameId)) } })
                }
                jobs.awaitAll()
                watchSyncing()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = Problems.message(e)) }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        try {
            val sources = api.calendars(frameId)
            val view = api.frameCalendar(frameId)
            _state.update { it.copy(sources = sources, view = view) }
            watchSyncing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
        }
    }

    private fun reloadView() = viewModelScope.launch {
        runCatching { api.frameCalendar(frameId) }.onSuccess { v -> _state.update { it.copy(view = v) } }
    }

    /** While something syncs, check back every few seconds (the server finishes within a minute). */
    private fun watchSyncing() {
        if (state.value.sources.orEmpty().none { it.status == CalendarStatus.SYNCING } || poll?.isActive == true) return
        poll = viewModelScope.launch {
            repeat(20) {
                delay(3000)
                val sources = runCatching { api.calendars(frameId) }.getOrNull() ?: return@repeat
                _state.update { it.copy(sources = sources) }
                if (sources.none { it.status == CalendarStatus.SYNCING }) {
                    reloadView()
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------------ sources

    fun openAdd() {
        _form.value = SourceForm(color = CalendarLogic.nextColor(state.value.sources.orEmpty().map { it.color }))
    }

    fun openEdit(s: CalendarSource) {
        _form.value = SourceForm(
            source = s, kind = if (s.kind == "caldav") "caldav" else "ics", username = s.username.orEmpty(), name = s.name,
            color = s.color, enabled = s.enabled,
        )
    }

    fun closeForm() { _form.value = null }

    /** Edits a field; a new test is needed once the address or credentials change. */
    fun editForm(f: (SourceForm) -> SourceForm) = _form.update { old ->
        old ?: return@update null
        val n = f(old)
        if (n.url != old.url || n.username != old.username || n.password != old.password || n.kind != old.kind) n.copy(result = null, picked = emptyList()) else n
    }

    private fun body(f: SourceForm) = CalendarSourceCreate(
        kind = f.kind, url = f.url.trim(),
        username = if (f.kind == "caldav") f.username.trim() else null,
        password = if (f.kind == "caldav") f.password else null,
    )

    fun test() {
        val f = _form.value ?: return
        _form.update { it?.copy(urlTouched = true) }
        if (!f.canTest) return
        _form.update { it?.copy(testing = true, error = null) }
        viewModelScope.launch {
            try {
                val r = api.testCalendar(frameId, body(f))
                _form.update { cur ->
                    cur?.copy(
                        testing = false, result = r,
                        picked = if (r.ok && r.calendars.isNotEmpty()) listOf(r.calendars.first().url) else emptyList(),
                        name = if (r.ok && cur.name.isBlank() && r.calendarName != null) r.calendarName!! else cur.name,
                    )
                }
            } catch (e: Exception) {
                _form.update { it?.copy(testing = false, error = Problems.message(e)) }
            }
        }
    }

    fun togglePick(url: String) = _form.update { f -> f?.copy(picked = if (url in f.picked) f.picked - url else f.picked + url) }

    fun save() {
        val f = _form.value ?: return
        _form.update { it?.copy(urlTouched = true) }
        if (!f.canSave) return
        _form.update { it?.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved: List<CalendarSource> = when {
                    f.editing -> {
                        val src = f.source!!
                        val patch = buildJsonObject {
                            put("name", JsonPrimitive(f.name.trim())); put("color", JsonPrimitive(f.color)); put("enabled", JsonPrimitive(f.enabled))
                            if (!f.isLocal) {
                                if (f.url.isNotBlank()) put("url", JsonPrimitive(f.url.trim()))
                                if (src.kind == "caldav") {
                                    if (f.username.isNotBlank() && f.username.trim() != src.username) put("username", JsonPrimitive(f.username.trim()))
                                    if (f.password.isNotEmpty()) put("password", JsonPrimitive(f.password))
                                }
                            }
                        }
                        listOf(api.updateCalendar(frameId, src.id, patch))
                    }
                    f.multi -> {
                        val used = state.value.sources.orEmpty().map { it.color }.toMutableList()
                        f.picked.map { u ->
                            val cal = f.calendars.first { it.url == u }
                            // One picked calendar keeps the colour chosen above; several get their own.
                            val color = if (f.picked.size == 1) f.color else CalendarLogic.nextColor(used)
                            used += color
                            api.createCalendar(frameId, body(f).copy(calendarUrl = u, name = f.name.trim().takeIf { f.picked.size == 1 && it.isNotEmpty() } ?: cal.name, color = color), UUID.randomUUID().toString())
                        }
                    }
                    else -> listOf(api.createCalendar(frameId, body(f).copy(name = f.name.trim().ifEmpty { null }, color = f.color), UUID.randomUUID().toString()))
                }
                _state.update { s ->
                    val list = s.sources.orEmpty().toMutableList()
                    saved.forEach { n -> val i = list.indexOfFirst { it.id == n.id }; if (i >= 0) list[i] = n else list += n }
                    s.copy(sources = list)
                }
                _form.value = null
                _events.tryEmit(if (f.editing) CalendarNotice.Message(app.kinframe.family.R.string.cal_saved) else CalendarNotice.Added(saved.size))
                reloadView()
                watchSyncing()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it?.copy(saving = false, error = Problems.message(e)) }
            }
        }
    }

    fun syncNow(s: CalendarSource, label: String) = viewModelScope.launch {
        try {
            val r = api.syncCalendar(frameId, s.id)
            _state.update { st -> st.copy(sources = st.sources.orEmpty().map { if (it.id == s.id) r else it }) }
            _events.tryEmit(CalendarNotice.Message(app.kinframe.family.R.string.cal_sync_started, listOf(label)))
            watchSyncing()
        } catch (e: Exception) {
            _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
        }
    }

    fun setEnabled(s: CalendarSource, on: Boolean) = viewModelScope.launch {
        try {
            val r = api.updateCalendar(frameId, s.id, buildJsonObject { put("enabled", JsonPrimitive(on)) })
            _state.update { st -> st.copy(sources = st.sources.orEmpty().map { if (it.id == s.id) r else it }) }
            reloadView()
        } catch (e: Exception) {
            _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
        }
    }

    fun remove(s: CalendarSource, label: String) = viewModelScope.launch {
        try {
            api.deleteCalendar(frameId, s.id)
            _state.update { st -> st.copy(sources = st.sources.orEmpty().filter { it.id != s.id }) }
            _events.tryEmit(CalendarNotice.Message(app.kinframe.family.R.string.cal_removed, listOf(label)))
            reloadView()
        } catch (e: Exception) {
            _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
        }
    }

    // ------------------------------------------------------------------ countdowns

    fun toggleCountdown(ev: CalendarEvent, onDone: (CalendarEvent) -> Unit = {}) {
        if (state.value.busyCountdown) return
        val on = !ev.countdown
        _state.update { it.copy(busyCountdown = true) }
        viewModelScope.launch {
            try {
                if (on) api.setCountdown(frameId, ev.id) else api.clearCountdown(frameId, ev.id)
                _state.update { s ->
                    s.copy(busyCountdown = false, view = s.view?.copy(events = s.view.events.map { if (it.id == ev.id) it.copy(countdown = on) else it }))
                }
                onDone(ev.copy(countdown = on))
                _events.tryEmit(CalendarNotice.Message(if (on) app.kinframe.family.R.string.cal_countdown_on else app.kinframe.family.R.string.cal_countdown_off))
                reloadView()
            } catch (e: Exception) {
                _state.update { it.copy(busyCountdown = false) }
                _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
            }
        }
    }

    // ------------------------------------------------------------------ sent events

    fun clearSendError() = _state.update { it.copy(sendError = null) }

    /** Sends an event to the frame, with an optional photo (prepared and uploaded first). */
    fun send(form: CalendarLogic.EventForm, photo: Uri?, placement: String, frameName: String, onSent: () -> Unit) {
        val (err, body) = CalendarLogic.build(form)
        if (err != null || body == null || state.value.sending) return
        _state.update { it.copy(sending = true, sendProgress = 0f, sendError = null) }
        viewModelScope.launch {
            try {
                val photoId = photo?.let { uploadPhoto(it) { p -> _state.update { s -> s.copy(sendProgress = p) } } }
                val inv = api.sendEvent(
                    frameId, if (photoId != null) body.copy(photoUploadId = photoId, photoPlacement = placement) else body, UUID.randomUUID().toString(),
                )
                _state.update { s -> s.copy(sending = false, invitations = listOf(inv) + s.invitations.orEmpty()) }
                _events.tryEmit(CalendarNotice.Message(app.kinframe.family.R.string.cal_ev_sent, listOf(frameName)))
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, sendError = Problems.message(e)) }
            }
        }
    }

    fun withdraw(inv: EventInvitation) = viewModelScope.launch {
        try {
            api.withdrawEvent(frameId, inv.id)
            _state.update { s -> s.copy(invitations = s.invitations.orEmpty().map { if (it.id == inv.id) it.copy(status = "withdrawn") else it }) }
            _events.tryEmit(CalendarNotice.Message(app.kinframe.family.R.string.cal_withdrawn))
        } catch (e: Exception) {
            _events.tryEmit(CalendarNotice.Error(Problems.message(e)))
            runCatching { api.eventInvitations(frameId) }.onSuccess { l -> _state.update { it.copy(invitations = l) } }
        }
    }
}
