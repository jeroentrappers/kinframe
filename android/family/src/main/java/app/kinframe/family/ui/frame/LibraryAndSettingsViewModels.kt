package app.kinframe.family.ui.frame

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.Album
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BulkAction
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Place
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementCounts
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.SettingsDocument
import app.kinframe.core.api.SleepScheduleDto
import app.kinframe.family.R
import app.kinframe.family.ui.Load
import app.kinframe.family.util.Problems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

enum class LibraryTab(val visibility: String) { Visible("visible"), Hidden("hidden"), Trash("trashed") }

data class LibraryState(
    val tab: LibraryTab = LibraryTab.Visible,
    val placements: Load<List<Placement>> = Load.Loading,
    val counts: PlacementCounts = PlacementCounts(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    /** The frame's albums, and the one being looked at (null: all photos). */
    val albums: List<Album> = emptyList(),
    val albumId: String? = null,
    /** The album the frame plays (`slideshow.album_id`), known to people who may change settings. */
    val playingAlbumId: String? = null,
    /** "On this day": earlier years' photos taken on today's date. */
    val memories: List<Placement> = emptyList(),
    /** While arranging an album: the new order. */
    val arranging: List<String>? = null,
) {
    val album: Album? get() = albums.firstOrNull { it.id == albumId }
}

sealed interface LibraryEvent {
    /** [action] done on [count] items; [undo] reverts it. */
    data class Done(val action: String, val count: Int, val failed: Int, val undo: (() -> Unit)?) : LibraryEvent
    data class Error(@StringRes val message: Int) : LibraryEvent
    /** A toast: [message] with [args]; a plural when [count] is set. */
    /** A string, or a plurals resource when [plural] is set. */
    data class Message(val message: Int, val args: List<Any> = emptyList(), val count: Int? = null, val plural: Boolean = false, val undo: (() -> Unit)? = null) : LibraryEvent
}

/**
 * A frame's library (product spec 4.6): what's on it, hidden items and the 30-day trash, albums (web
 * `FrameLibrary.svelte`) and "On this day" memories.
 */
class LibraryViewModel(
    private val api: KinApi,
    private val frameId: String,
    /** May change settings: knows and changes which album the frame plays. */
    private val canSettings: Boolean = false,
    private val today: () -> java.time.LocalDate = { java.time.LocalDate.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()
    private var loadJob: Job? = null

    init {
        load()
        loadAlbums()
        loadMemories()
        if (canSettings) loadPlaying()
    }

    fun setTab(t: LibraryTab) {
        if (t == state.value.tab && state.value.albumId == null) return
        _state.update { LibraryState(tab = t, counts = it.counts, albums = it.albums, playingAlbumId = it.playingAlbumId, memories = it.memories) }
        load()
    }

    fun load() {
        loadJob?.cancel()
        val tab = state.value.tab
        val album = state.value.album
        loadJob = viewModelScope.launch {
            try {
                if (album != null) {
                    // Every page of the album, in the album's own order.
                    val all = ArrayList<Placement>()
                    var cursor: String? = null
                    do {
                        val page = api.placements(frameId, "visible", cursor, 200, albumId = album.id)
                        all += page.placements
                        cursor = page.nextCursor
                    } while (cursor != null && all.size < 5000)
                    val order = album.placementIds.withIndex().associate { (i, id) -> id to i }
                    _state.update { it.copy(placements = Load.Ready(all.sortedBy { p -> order[p.id] ?: Int.MAX_VALUE }), nextCursor = null) }
                    return@launch
                }
                val page = api.placements(frameId, tab.visibility)
                _state.update { it.copy(placements = Load.Ready(page.placements), counts = page.counts, nextCursor = page.nextCursor) }
            } catch (e: Exception) {
                if (state.value.placements !is Load.Ready) _state.update { it.copy(placements = Load.Error(Problems.message(e))) }
            }
        }
    }

    fun loadMore() {
        val s = state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = api.placements(frameId, s.tab.visibility, cursor)
                _state.update { st ->
                    st.copy(placements = Load.Ready((st.placements as? Load.Ready)?.value.orEmpty() + page.placements), nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (_: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    // ------------------------------------------------------------------ albums

    fun loadAlbums() = viewModelScope.launch {
        runCatching { api.albums(frameId) }.onSuccess { list -> _state.update { it.copy(albums = list) } }
    }

    private fun loadPlaying() = viewModelScope.launch {
        runCatching { api.settings(frameId) }.onSuccess { doc -> _state.update { it.copy(playingAlbumId = FrameSettings.parse(doc.raw).slideshow.albumId) } }
    }

    private fun loadMemories() = viewModelScope.launch {
        val d = today()
        val md = "%02d-%02d".format(d.monthValue, d.dayOfMonth)
        runCatching { api.placements(frameId, "visible", limit = 24, onThisDay = md, sort = "taken_desc") }
            .onSuccess { page -> _state.update { it.copy(memories = page.placements) } }
    }

    /** Look at one album, or (null) at all photos. */
    fun selectAlbum(id: String?) {
        _state.update { it.copy(albumId = id, tab = LibraryTab.Visible, selected = emptySet(), arranging = null, placements = Load.Loading) }
        load()
    }

    private fun replaceAlbum(a: Album) = _state.update { s -> s.copy(albums = s.albums.map { if (it.id == a.id) a else it }) }

    private fun albumFailed(e: Exception) {
        if ((e as? ApiException)?.status == 412 || (e as? ApiException)?.code == ProblemCode.VERSION_CONFLICT) {
            _events.tryEmit(LibraryEvent.Error(R.string.albums_conflict))
            viewModelScope.launch { runCatching { api.albums(frameId) }.onSuccess { l -> _state.update { it.copy(albums = l) }; load() } }
        } else {
            _events.tryEmit(LibraryEvent.Error(Problems.message(e)))
        }
    }

    /** A new album, optionally with the selection in it; opens it. */
    fun createAlbum(name: String, ids: List<String> = emptyList(), open: Boolean = ids.isEmpty()) = viewModelScope.launch {
        try {
            val a = api.createAlbum(frameId, name.trim(), ids)
            _state.update { it.copy(albums = it.albums + a, selected = emptySet()) }
            if (ids.isEmpty()) _events.tryEmit(LibraryEvent.Message(R.string.albums_created, listOf(a.name)))
            else _events.tryEmit(LibraryEvent.Message(R.plurals.albums_added, listOf(ids.size, a.name), count = ids.size, plural = true))
            if (open) selectAlbum(a.id)
        } catch (e: Exception) {
            albumFailed(e)
        }
    }

    private fun patchAlbum(a: Album, patch: kotlinx.serialization.json.JsonObject, onDone: (Album) -> Unit = {}) = viewModelScope.launch {
        try {
            val n = api.updateAlbum(frameId, a.id, a.version, patch)
            replaceAlbum(n)
            onDone(n)
        } catch (e: Exception) {
            albumFailed(e)
        }
    }

    fun renameAlbum(a: Album, name: String) = patchAlbum(a, buildJsonObject { put("name", JsonPrimitive(name.trim())) }) {
        _events.tryEmit(LibraryEvent.Message(R.string.common_saved))
    }

    /** [from]/[to] as `MM-DD`; null clears the schedule. */
    fun scheduleAlbum(a: Album, from: String?, to: String?) = patchAlbum(a, buildJsonObject {
        if (from == null || to == null) put("schedule", kotlinx.serialization.json.JsonNull)
        else put("schedule", buildJsonObject { put("from_month_day", JsonPrimitive(from)); put("to_month_day", JsonPrimitive(to)) })
    }) {
        _events.tryEmit(if (from == null || to == null) LibraryEvent.Message(R.string.albums_schedule_cleared) else LibraryEvent.Message(R.string.albums_schedule_saved, listOf(from, to)))
    }

    fun makeCover(a: Album, placementId: String) = patchAlbum(a, buildJsonObject { put("cover_placement_id", JsonPrimitive(placementId)) }) {
        _state.update { it.copy(selected = emptySet()) }
        _events.tryEmit(LibraryEvent.Message(R.string.albums_cover_set))
    }

    fun startArranging() = _state.update { s -> s.copy(arranging = (s.placements as? Load.Ready)?.value?.map { it.id }, selected = emptySet()) }
    fun cancelArranging() = _state.update { it.copy(arranging = null) }

    /** Moves one photo [by] places in the new order. */
    fun move(id: String, by: Int) = _state.update { s ->
        val list = s.arranging?.toMutableList() ?: return@update s
        val i = list.indexOf(id)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return@update s
        list.add(j, list.removeAt(i))
        s.copy(arranging = list)
    }

    fun saveOrder() {
        val s = state.value
        val a = s.album ?: return
        val order = s.arranging ?: return
        patchAlbum(a, buildJsonObject { put("placement_ids", kotlinx.serialization.json.JsonArray(order.map { JsonPrimitive(it) })) }) {
            _state.update { st ->
                val byId = (st.placements as? Load.Ready)?.value.orEmpty().associateBy { p -> p.id }
                st.copy(arranging = null, placements = Load.Ready(order.mapNotNull { id -> byId[id] }))
            }
            _events.tryEmit(LibraryEvent.Message(R.string.albums_order_saved))
        }
    }

    fun deleteAlbum(a: Album) = viewModelScope.launch {
        try {
            api.deleteAlbum(frameId, a.id)
            _state.update { s -> s.copy(albums = s.albums.filter { it.id != a.id }, playingAlbumId = s.playingAlbumId.takeIf { it != a.id }) }
            if (state.value.albumId == a.id) selectAlbum(null)
            _events.tryEmit(LibraryEvent.Message(R.string.albums_deleted, listOf(a.name)))
        } catch (e: Exception) {
            albumFailed(e)
        }
    }

    /** Adds [ids] to an existing album, or to a new one named [newName]. */
    fun addToAlbum(ids: List<String>, albumId: String?, newName: String?) {
        if (ids.isEmpty()) return
        if (albumId == null) { if (!newName.isNullOrBlank()) createAlbum(newName, ids, open = false); return }
        viewModelScope.launch {
            try {
                val r = api.bulkPlacements(frameId, ids, BulkAction.ADD_TO_ALBUM, albumId)
                val name = state.value.albums.firstOrNull { it.id == albumId }?.name.orEmpty()
                _state.update { it.copy(selected = emptySet()) }
                _events.tryEmit(LibraryEvent.Message(R.plurals.albums_added, listOf(r.ok.size, name), count = r.ok.size, plural = true))
                loadAlbums()
            } catch (e: Exception) {
                _events.tryEmit(LibraryEvent.Error(Problems.message(e)))
            }
        }
    }

    /** Takes the selection out of the album being looked at (the photos stay on the frame), with undo. */
    fun removeFromAlbum(ids: Collection<String> = state.value.selected) {
        val a = state.value.album ?: return
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val r = api.bulkPlacements(frameId, ids.toList(), BulkAction.REMOVE_FROM_ALBUM, a.id)
                val ok = r.ok.toSet()
                _state.update { s -> s.copy(selected = emptySet(), placements = Load.Ready((s.placements as? Load.Ready)?.value.orEmpty().filterNot { it.id in ok })) }
                _events.tryEmit(LibraryEvent.Message(R.plurals.albums_removed, listOf(ok.size, a.name), count = ok.size, plural = true, undo = {
                    viewModelScope.launch { runCatching { api.bulkPlacements(frameId, ok.toList(), BulkAction.ADD_TO_ALBUM, a.id) }; loadAlbums(); load() }
                }))
                loadAlbums()
            } catch (e: Exception) {
                _events.tryEmit(LibraryEvent.Error(Problems.message(e)))
            }
        }
    }

    /** Play this album on the frame, or (null) all photos again: `slideshow.album_id`. */
    fun play(albumId: String?, frameName: String) = viewModelScope.launch {
        try {
            var doc = api.settings(frameId)
            val patch = buildJsonObject { put("slideshow", buildJsonObject { put("album_id", albumId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }) }
            doc = try {
                api.patchSettings(frameId, doc.version, patch)
            } catch (e: ApiException) {
                if (e.status != 412) throw e
                api.patchSettings(frameId, api.settings(frameId).version, patch)
            }
            _state.update { it.copy(playingAlbumId = FrameSettings.parse(doc.raw).slideshow.albumId) }
            val name = state.value.albums.firstOrNull { it.id == albumId }?.name.orEmpty()
            _events.tryEmit(if (albumId != null) LibraryEvent.Message(R.string.albums_now_playing, listOf(frameName, name)) else LibraryEvent.Message(R.string.albums_playing_all, listOf(frameName)))
        } catch (e: Exception) {
            _events.tryEmit(LibraryEvent.Error(Problems.message(e)))
        }
    }

    fun toggle(id: String) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun selectAll() = _state.update { s -> s.copy(selected = (s.placements as? Load.Ready)?.value?.map { it.id }?.toSet().orEmpty()) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Hide, unhide, trash or restore the selection (or [ids]), with undo. */
    fun act(action: String, ids: Collection<String> = state.value.selected, undoable: Boolean = true) {
        if (ids.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val r = api.bulkPlacements(frameId, ids.toList(), action)
                val ok = r.ok.toSet()
                _state.update { s ->
                    val list = (s.placements as? Load.Ready)?.value.orEmpty()
                    s.copy(placements = Load.Ready(list.filterNot { it.id in ok }), selected = emptySet(), busy = false)
                }
                val inverse = when (action) { BulkAction.HIDE -> BulkAction.UNHIDE; BulkAction.TRASH -> BulkAction.RESTORE; else -> null }
                _events.tryEmit(LibraryEvent.Done(action, ok.size, r.failed.size,
                    if (undoable && inverse != null && ok.isNotEmpty()) ({ act(inverse, ok, undoable = false); load() }) else null))
                if (!undoable) load()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                _events.tryEmit(LibraryEvent.Error(Problems.message(e)))
            }
        }
    }
}

data class SettingsFormState(
    val base: SettingsDocument? = null,
    val draft: JsonObject? = null,
    val load: Load<Unit> = Load.Loading,
    val saving: Boolean = false,
    val conflict: Boolean = false,
    val placeQuery: String = "",
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    @StringRes val placeError: Int? = null,
    /** The frame's albums, for "Show on the frame". */
    val albums: List<Album> = emptyList(),
) {
    val settings: FrameSettings get() = FrameSettings.parse(draft)
    val dirty: Boolean get() = base != null && draft != null && SettingsMerge.diff(base.raw, draft).isNotEmpty()
}

sealed interface SettingsEvent {
    data object Saved : SettingsEvent
    data object MergedSaved : SettingsEvent
    data class Error(@StringRes val message: Int) : SettingsEvent
}

/** Remote settings (README §5.6): edit a copy, send the merge patch with If-Match, rebase on conflicts. */
class FrameSettingsViewModel(private val api: KinApi, private val frameId: String, private val lang: () -> String) : ViewModel() {
    private val _state = MutableStateFlow(SettingsFormState())
    val state: StateFlow<SettingsFormState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()
    private var searchJob: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        launch { runCatching { api.albums(frameId) }.onSuccess { l -> _state.update { it.copy(albums = l) } } }
        try {
            val doc = api.settings(frameId)
            _state.update { it.copy(base = doc, draft = doc.raw, load = Load.Ready(Unit), conflict = false) }
        } catch (e: Exception) {
            _state.update { it.copy(load = Load.Error(Problems.message(e))) }
        }
    }

    fun set(path: String, value: JsonElement?) = _state.update { s ->
        val d = s.draft ?: return@update s
        s.copy(draft = SettingsMerge.set(d, path.split('.'), value))
    }

    fun set(path: String, value: Int) = set(path, JsonPrimitive(value))
    fun set(path: String, value: Boolean) = set(path, JsonPrimitive(value))
    fun set(path: String, value: String) = set(path, JsonPrimitive(value))

    fun setSchedules(list: List<SleepScheduleDto>) =
        set("display.sleep_schedules", KinJson.encodeToJsonElement(ListSerializer(SleepScheduleDto.serializer()), list))

    fun addSchedule() = setSchedules(state.value.settings.display.sleepSchedules + SleepScheduleDto(id = UUID.randomUUID().toString()))

    fun discard() = _state.update { it.copy(draft = it.base?.raw, conflict = false) }

    fun save() {
        val s = state.value
        val base = s.base ?: return
        val draft = s.draft ?: return
        val patch = SettingsMerge.diff(base.raw, draft)
        if (patch.isEmpty() || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val doc = api.patchSettings(frameId, base.version, patch)
                _state.update { it.copy(base = doc, draft = doc.raw, saving = false, conflict = false) }
                _events.tryEmit(SettingsEvent.Saved)
            } catch (e: ApiException) {
                if (e.status == 412 || e.code == ProblemCode.VERSION_CONFLICT) rebase(base, patch) else fail(e)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun rebase(base: SettingsDocument, ours: JsonObject) {
        try {
            val latest = api.settings(frameId)
            when (val r = SettingsMerge.rebase(base.raw, latest.raw, ours)) {
                is SettingsMerge.Rebase.Retry -> {
                    val doc = api.patchSettings(frameId, latest.version, r.patch)
                    _state.update { it.copy(base = doc, draft = doc.raw, saving = false, conflict = false) }
                    _events.tryEmit(SettingsEvent.MergedSaved)
                }
                is SettingsMerge.Rebase.Review -> _state.update { it.copy(base = latest, draft = r.merged, saving = false, conflict = true) }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        _state.update { it.copy(saving = false) }
        _events.tryEmit(SettingsEvent.Error(Problems.message(e)))
    }

    fun onPlaceQuery(q: String) {
        _state.update { it.copy(placeQuery = q, placeError = null) }
        searchJob?.cancel()
        if (q.trim().length < 2) { _state.update { it.copy(places = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(searching = true) }
            try {
                val r = api.geocode(q.trim(), lang())
                _state.update { it.copy(places = r, searching = false) }
            } catch (e: Exception) {
                val msg = when ((e as? ApiException)?.status) {
                    429 -> R.string.location_error_busy
                    502 -> R.string.location_error_upstream
                    503 -> R.string.location_error_disabled
                    else -> Problems.message(e)
                }
                _state.update { it.copy(searching = false, places = emptyList(), placeError = msg) }
            }
        }
    }

    fun choosePlace(p: Place) {
        val label = listOfNotNull(p.name, p.admin1?.takeIf { it != p.name }, p.country).joinToString(", ").take(100)
        set("location", buildJsonObject {
            put("name", JsonPrimitive(label)); put("lat", JsonPrimitive(p.lat)); put("lon", JsonPrimitive(p.lon))
        })
        p.timezone?.let { set("locale.timezone", it) }
        _state.update { it.copy(placeQuery = "", places = emptyList()) }
    }

    fun removePlace() {
        set("location", null)
        set("widgets.weather", false)
    }
}
