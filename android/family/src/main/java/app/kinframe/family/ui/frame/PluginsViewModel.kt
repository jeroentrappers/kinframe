package app.kinframe.family.ui.frame

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.FramePlugins
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.SettingsDocument
import app.kinframe.core.plugin.settings.FormPlugin
import app.kinframe.core.plugin.settings.InstanceFieldError
import app.kinframe.core.plugin.settings.PluginInstanceDraft
import app.kinframe.core.plugin.settings.PluginLayout
import app.kinframe.core.plugin.settings.PluginSettings
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The instance being edited: [errors] from the client check or the server's 422, shown at their fields. */
data class PluginEditor(
    val draft: PluginInstanceDraft,
    val isNew: Boolean,
    val errors: List<InstanceFieldError> = emptyList(),
    val confirmRemove: Boolean = false,
)

data class PluginsState(
    val doc: SettingsDocument? = null,
    val plugins: Load<FramePlugins> = Load.Loading,
    val editor: PluginEditor? = null,
    val adding: Boolean = false,
    val saving: Boolean = false,
) {
    val layout: PluginLayout get() = PluginSettings.layout(doc?.raw)
    val instances: List<PluginInstanceDraft> get() = PluginSettings.instances(doc?.raw)
    val report: FramePlugins? get() = (plugins as? Load.Ready)?.value
    val catalog: List<FormPlugin> get() = report?.catalog.orEmpty().mapNotNull(FormPlugin::read)
    /** The frame app reports no catalog yet: "Update the frame app". */
    val needsFrameUpdate: Boolean get() = report != null && report!!.catalogAt == null
    fun plugin(id: String): FormPlugin? = catalog.firstOrNull { it.id == id }
}

sealed interface PluginsEvent {
    data object Saved : PluginsEvent
    data object Removed : PluginsEvent
    data class Error(@StringRes val message: Int) : PluginsEvent
}

/**
 * The frame settings' Plugins section (docs/plugins.md §6, §6.1). Every change is saved at once as a merge patch with
 * `If-Match`: the layout field, or the whole `instances` array. A `412` reloads the settings and applies the same
 * change to the newer list once more. While an editor is open, the catalog and status are polled every 3 s.
 */
class PluginsViewModel(private val api: KinApi, private val frameId: String) : ViewModel() {
    private val _state = MutableStateFlow(PluginsState())
    val state: StateFlow<PluginsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PluginsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PluginsEvent> = _events.asSharedFlow()
    private var poll: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val doc = api.settings(frameId)
            _state.update { it.copy(doc = doc) }
        } catch (e: Exception) {
            if (state.value.doc == null) _state.update { it.copy(plugins = Load.Error(Problems.message(e))) }
            return@launch
        }
        refreshPlugins()
    }

    private suspend fun refreshPlugins() {
        try {
            val p = api.framePlugins(frameId)
            _state.update { it.copy(plugins = Load.Ready(p)) }
        } catch (e: Exception) {
            if (state.value.plugins !is Load.Ready) _state.update { it.copy(plugins = Load.Error(Problems.message(e))) }
        }
    }

    // ---------------------------------------------------------------- layout and list

    fun setLayout(field: String, value: String) = save(PluginSettings.layoutPatch(field, value)) { null }

    fun setEnabled(instanceId: String, on: Boolean) {
        val s = state.value
        val d = s.instances.firstOrNull { it.instanceId == instanceId } ?: return
        val plugin = s.plugin(d.pluginId)
        if (on && plugin != null && !PluginSettings.complete(plugin, d)) {
            // Incomplete: open the form with what's missing instead.
            openEditor(PluginEditor(d.copy(enabled = true), isNew = false, errors = PluginSettings.check(plugin, d.copy(enabled = true))))
            return
        }
        saveInstances { list -> list.map { if (it.instanceId == instanceId) it.copy(enabled = on) else it } }
    }

    fun openAdd() = _state.update { it.copy(adding = true) }
    fun closeAdd() = _state.update { it.copy(adding = false) }

    fun add(plugin: FormPlugin) {
        val s = state.value
        if (s.instances.size >= PluginSettings.MAX_INSTANCES) return
        _state.update { it.copy(adding = false) }
        openEditor(PluginEditor(PluginSettings.newInstance(plugin, s.instances.map { it.instanceId }), isNew = true))
    }

    fun edit(instanceId: String) {
        val d = state.value.instances.firstOrNull { it.instanceId == instanceId } ?: return
        openEditor(PluginEditor(d, isNew = false))
    }

    // ---------------------------------------------------------------- editor

    private fun openEditor(e: PluginEditor) {
        _state.update { it.copy(editor = e) }
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                refreshPlugins()
                delay(POLL_MS)
            }
        }
    }

    fun closeEditor() {
        poll?.cancel()
        _state.update { it.copy(editor = null) }
    }

    /** Edits the open instance; errors of the fields that changed are cleared. */
    fun update(change: (PluginInstanceDraft) -> PluginInstanceDraft) = _state.update { s ->
        val e = s.editor ?: return@update s
        val next = change(e.draft)
        val changed = (next.settings.keys + e.draft.settings.keys).filter { next.settings[it] != e.draft.settings[it] }.toSet() +
            (next.secretWrites.keys + e.draft.secretWrites.keys).filter { next.secretWrites[it] != e.draft.secretWrites[it] }
        val slotChanged = next.slot != e.draft.slot
        s.copy(editor = e.copy(draft = next, errors = e.errors.filterNot { it.key in changed || (slotChanged && it.field == "slot") }))
    }

    fun saveEditor() {
        val s = state.value
        val e = s.editor ?: return
        if (s.saving) return
        var d = e.draft
        val plugin = s.plugin(d.pluginId)
        if (plugin != null) {
            // A new instance is switched on once its form is complete.
            if (e.isNew) d = d.copy(enabled = PluginSettings.complete(plugin, d))
            val errors = PluginSettings.check(plugin, d)
            if (errors.isNotEmpty()) {
                _state.update { it.copy(editor = e.copy(draft = d, errors = errors)) }
                _events.tryEmit(PluginsEvent.Error(R.string.plugins_fix_fields))
                return
            }
        }
        val saved = d
        saveInstances(onDone = { closeEditor(); _events.tryEmit(PluginsEvent.Saved) }) { list ->
            if (list.any { it.instanceId == saved.instanceId }) list.map { if (it.instanceId == saved.instanceId) saved else it } else list + saved
        }
    }

    fun askRemove(ask: Boolean) = _state.update { s -> s.copy(editor = s.editor?.copy(confirmRemove = ask)) }

    fun remove() {
        val id = state.value.editor?.draft?.instanceId ?: return
        _state.update { s -> s.copy(editor = s.editor?.copy(confirmRemove = false)) }
        saveInstances(onDone = { closeEditor(); _events.tryEmit(PluginsEvent.Removed) }) { list -> list.filterNot { it.instanceId == id } }
    }

    // ---------------------------------------------------------------- saving

    private fun saveInstances(onDone: () -> Unit = { _events.tryEmit(PluginsEvent.Saved) }, change: (List<PluginInstanceDraft>) -> List<PluginInstanceDraft>) {
        var sent: List<PluginInstanceDraft> = emptyList()
        save(build = { doc -> change(PluginSettings.instances(doc.raw)).also { sent = it }.let(PluginSettings::instancesPatch) }, sent = { sent }, onDone = onDone)
    }

    private fun save(patch: JsonObject, onDone: () -> Unit = {}) = save(build = { patch }, sent = { emptyList() }, onDone = onDone)

    /** Sends [build]'s patch on the current version; on `412` reloads and builds it again on the newer document once. */
    private fun save(build: (SettingsDocument) -> JsonObject, sent: () -> List<PluginInstanceDraft>, onDone: () -> Unit) {
        val doc = state.value.doc ?: return
        if (state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val next = try {
                    api.patchSettings(frameId, doc.version, build(doc))
                } catch (e: ApiException) {
                    if (e.status != 412 && e.code != ProblemCode.VERSION_CONFLICT) throw e
                    val latest = api.settings(frameId)
                    _state.update { it.copy(doc = latest) }
                    api.patchSettings(frameId, latest.version, build(latest))
                }
                _state.update { it.copy(doc = next, saving = false) }
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                val fields = (e as? ApiException)?.problem?.errors.orEmpty()
                val editor = state.value.editor
                val mine = editor?.let { PluginSettings.fieldErrors(fields, sent())[it.draft.instanceId] }.orEmpty()
                if (editor != null && mine.isNotEmpty()) {
                    _state.update { it.copy(editor = editor.copy(errors = mine)) }
                    _events.tryEmit(PluginsEvent.Error(R.string.plugins_fix_fields))
                } else {
                    _events.tryEmit(PluginsEvent.Error(Problems.message(e)))
                }
            }
        }
    }

    override fun onCleared() {
        poll?.cancel()
    }

    companion object {
        const val POLL_MS = 3_000L
    }
}
