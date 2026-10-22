package app.kinframe.family.ui.frame

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

sealed interface LibraryEvent {
    /** [action] done on [count] items; [undo] reverts it. */
    data class Done(val action: String, val count: Int, val failed: Int, val undo: (() -> Unit)?) : LibraryEvent
    data class Error(@StringRes val message: Int) : LibraryEvent
}

/** A frame's library (product spec 4.6): what's on it, hidden items and the 30-day trash. */
class LibraryViewModel(private val api: KinApi, private val frameId: String) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()
    private var loadJob: Job? = null

    init { load() }

    fun setTab(t: LibraryTab) {
        if (t == state.value.tab) return
        _state.update { LibraryState(tab = t, counts = it.counts) }
        load()
    }

    fun load() {
        loadJob?.cancel()
        val tab = state.value.tab
        loadJob = viewModelScope.launch {
            try {
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
