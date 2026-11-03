package app.kinframe.family.ui.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kinframe.core.api.DiscoveredEntity
import app.kinframe.core.api.DiscoveredService
import app.kinframe.core.api.FramePlugins
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.plugin.settings.FormPlugin
import app.kinframe.core.plugin.settings.FormPreset
import app.kinframe.core.plugin.settings.FormSetting
import app.kinframe.core.plugin.settings.InstanceFieldError
import app.kinframe.core.plugin.settings.InstanceStatus
import app.kinframe.core.plugin.settings.PendingUpdate
import app.kinframe.core.plugin.settings.Permission
import app.kinframe.core.plugin.settings.PluginInstanceDraft
import app.kinframe.core.plugin.settings.PluginLayout
import app.kinframe.core.plugin.settings.PluginSettings
import app.kinframe.core.plugin.settings.SettingType
import app.kinframe.core.plugin.settings.TimeZoneOption
import app.kinframe.core.plugin.settings.TimeZones
import app.kinframe.core.plugin.settings.Slots
import app.kinframe.core.plugin.settings.Visibility
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.Skeleton
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.ToggleChip
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The language tag the manifest translations are looked up with ("nl-BE" → nl → en → the manifest). */
@Composable
private fun languageTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** The frame settings' Plugins section, with its "Add plugin" sheet and the per-instance editor. */
@Composable
fun PluginsSection(state: PluginsState, vm: PluginsViewModel) {
    PluginsSectionContent(state, vm::setLayout, vm::setEnabled, vm::edit, vm::openAdd, { vm.load() })
    if (state.adding) AddPluginSheet(state, vm::add, vm::closeAdd)
    state.confirming?.let { p -> ConfirmAddDialog(p, vm::confirmAdd, vm::cancelAdd) }
    val e = state.editor
    if (e != null) {
        Dialog(onDismissRequest = vm::closeEditor, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
                PluginEditorContent(
                    state.plugin(e.draft.pluginId), e, state.report, state.saving,
                    PluginEditorActions(vm::update, vm::saveEditor, vm::closeEditor, vm::askRemove, vm::remove,
                        onApproveUpdate = { v -> vm.approveUpdate(e.draft.instanceId, v) }, onDismissUpdate = { v -> vm.dismissUpdate(e.draft.instanceId, v) }),
                    dismissedUpdates = state.dismissedUpdates,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginsSectionContent(
    state: PluginsState,
    onLayout: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = Kin.colors
    val tag = languageTag()
    val instances = state.instances
    val canAdd = state.report?.catalogAt != null && instances.size < PluginSettings.MAX_INSTANCES && !state.saving
    SectionTitle(stringResource(R.string.plugins_title)) {
        if (canAdd) TextButton(onAdd) { Icon(KinIcons.Plus, null, tint = c.accent); Text(stringResource(R.string.plugins_add), color = c.accent) }
    }
    Text(stringResource(R.string.plugins_hint), style = Kin.type.caption, color = c.inkSoft)
    when (val p = state.plugins) {
        Load.Loading -> { Skeleton(Modifier.fillMaxWidth().heightIn(min = 120.dp), corner = 18.dp); return }
        is Load.Error -> { ErrorPanel(p.message, onRetry); return }
        is Load.Ready -> Unit
    }
    if (state.needsFrameUpdate) Note(stringResource(R.string.plugins_update_frame), tone = NoteTone.Warn)

    val layout = state.layout
    Surface(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plugins_layout), style = Kin.type.label, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PluginLayout.MODE_OFF to R.string.plugins_layout_off, PluginLayout.MODE_OVERLAY to R.string.plugins_layout_overlay,
                PluginLayout.MODE_BAND to R.string.plugins_layout_band, PluginLayout.MODE_SPLIT to R.string.plugins_layout_split).forEach { (v, l) ->
                ToggleChip(stringResource(l), layout.mode == v, { if (layout.mode != v) onLayout("mode", v) })
            }
        }
        Text(stringResource(when (layout.mode) {
            PluginLayout.MODE_OVERLAY -> R.string.plugins_layout_overlay_hint
            PluginLayout.MODE_BAND -> R.string.plugins_layout_band_hint
            PluginLayout.MODE_SPLIT -> R.string.plugins_layout_split_hint
            else -> R.string.plugins_layout_off_hint
        }), style = Kin.type.caption, color = c.inkSoft)
        if (layout.mode == PluginLayout.MODE_SPLIT) {
            Text(stringResource(R.string.plugins_split_side), style = Kin.type.label, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PluginLayout.SIDE_LEFT to R.string.plugins_side_left, PluginLayout.SIDE_RIGHT to R.string.plugins_side_right).forEach { (v, l) ->
                    ToggleChip(stringResource(l), layout.side == v, { if (layout.side != v) onLayout("side", v) })
                }
            }
        }
        if (layout.mode == PluginLayout.MODE_BAND) {
            Text(stringResource(R.string.plugins_band_side), style = Kin.type.label, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PluginLayout.SIDE_LEFT to R.string.plugins_side_left, PluginLayout.SIDE_RIGHT to R.string.plugins_side_right).forEach { (v, l) ->
                    ToggleChip(stringResource(l), layout.side == v, { if (layout.side != v) onLayout("side", v) })
                }
            }
            Text(stringResource(R.string.plugins_background), style = Kin.type.label, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PluginLayout.BACKGROUND_PAPER to R.string.plugins_background_paper, PluginLayout.BACKGROUND_PHOTO to R.string.plugins_background_photo).forEach { (v, l) ->
                    ToggleChip(stringResource(l), layout.background == v, { if (layout.background != v) onLayout("background", v) })
                }
            }
        }
    }

    Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
        if (instances.isEmpty()) Text(stringResource(R.string.plugins_empty), style = Kin.type.body, color = c.inkSoft, modifier = Modifier.padding(8.dp))
        instances.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = c.line)
            val plugin = state.plugin(d.pluginId)
            val update = PluginSettings.pendingUpdate(plugin, d, state.report?.instances?.firstOrNull { it.instanceId == d.instanceId })
                ?.takeIf { !state.updateDismissed(d.instanceId, it.version) }
            InstanceRow(d, plugin?.name(tag) ?: d.pluginId, PluginSettings.status(d, state.report, plugin != null), update != null, !state.saving,
                { onToggle(d.instanceId, it) }, { onEdit(d.instanceId) })
        }
    }
    if (instances.size >= PluginSettings.MAX_INSTANCES) {
        Text(stringResource(R.string.plugins_max, PluginSettings.MAX_INSTANCES), style = Kin.type.caption, color = c.inkSoft)
    }
}

@Composable
private fun InstanceRow(d: PluginInstanceDraft, name: String, status: InstanceStatus, update: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onEdit).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(slotLabel(d.slot), style = Kin.type.caption, color = c.inkSoft)
            when (status) {
                is InstanceStatus.Failed -> Text(status.message, style = Kin.type.caption, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InstanceStatus.Waiting -> Text(stringResource(R.string.plugins_status_waiting), style = Kin.type.caption, color = c.inkFaint)
                InstanceStatus.NotOnFrame -> Text(stringResource(R.string.plugins_status_not_on_frame), style = Kin.type.caption, color = c.warn)
                is InstanceStatus.Withdrawn -> Text(stringResource(R.string.plugins_withdrawn, status.reason), style = Kin.type.caption, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InstanceStatus.Off, InstanceStatus.Running -> Unit
            }
            if (update && status !is InstanceStatus.Withdrawn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(KinIcons.Shield, null, Modifier.size(14.dp), tint = c.accent)
                Text(stringResource(R.string.plugins_update_waiting), style = Kin.type.caption, color = c.accent)
            }
        }
        Switch(
            checked = d.enabled, onCheckedChange = onToggle, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedBorderColor = c.line),
        )
    }
}

@Composable
private fun slotLabel(slot: String) = stringResource(if (slot == Slots.TOP_LEFT) R.string.plugins_slot_top_left else R.string.plugins_slot_top_right)

// -------------------------------------------------------------------- catalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPluginSheet(state: PluginsState, onPick: (FormPlugin) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kin.colors.surface) {
        PluginCatalogContent(state.catalog, onPick)
    }
}

@Composable
fun PluginCatalogContent(catalog: List<FormPlugin>, onPick: (FormPlugin) -> Unit) {
    val c = Kin.colors
    val tag = languageTag()
    val sorted = catalog.sortedBy { it.name(tag).lowercase() }
    val (builtin, community) = sorted.partition { !it.community }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.plugins_catalog_title), style = Kin.type.title, color = c.ink)
        if (catalog.isEmpty()) Text(stringResource(R.string.plugins_catalog_empty), style = Kin.type.body, color = c.inkSoft)
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (community.isNotEmpty() && builtin.isNotEmpty()) GroupTitle(stringResource(R.string.plugins_group_builtin))
            builtin.forEach { CatalogEntry(it, tag, onPick) }
            if (community.isNotEmpty()) {
                GroupTitle(stringResource(R.string.plugins_group_community), KinIcons.People)
                Text(stringResource(R.string.plugins_group_community_hint), style = Kin.type.caption, color = c.inkSoft)
                community.forEach { CatalogEntry(it, tag, onPick) }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.padding(top = 8.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = Kin.colors.inkSoft)
        Text(text, style = Kin.type.label, color = Kin.colors.ink)
    }
}

/** "by Jane Doe · MIT · version 1.2.0" for a community plugin. */
@Composable
private fun communityMeta(p: FormPlugin, withVersion: Boolean = true): String = listOfNotNull(
    p.author?.let { stringResource(R.string.plugins_by_author, it) },
    p.license,
    p.version?.takeIf { withVersion && it.isNotBlank() }?.let { stringResource(R.string.plugins_version_number, it) },
).joinToString(" · ")

@Composable
private fun CatalogEntry(p: FormPlugin, tag: String, onPick: (FormPlugin) -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { onPick(p) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(p.name(tag), style = Kin.type.bodyStrong, color = c.ink)
        if (p.community) communityMeta(p).takeIf { it.isNotEmpty() }?.let { Text(it, style = Kin.type.caption, color = c.inkFaint) }
        p.description(tag)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
        val hosts = p.hosts.map { it.removePrefix("https://").removePrefix("http://") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (hosts.isEmpty()) KinIcons.Home else KinIcons.Globe, null, Modifier.size(16.dp), tint = c.inkSoft)
            Text(if (hosts.isEmpty()) stringResource(R.string.plugins_local) else stringResource(R.string.plugins_reaches, hosts.joinToString(", ")),
                style = Kin.type.caption, color = c.inkSoft)
        }
        if (p.location) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(KinIcons.MapPin, null, Modifier.size(16.dp), tint = c.inkSoft)
            Text(stringResource(R.string.plugins_location), style = Kin.type.caption, color = c.inkSoft)
        }
    }
}

/** What a plugin may do, one line each; [plugin] names the address settings by their form labels. */
@Composable
fun PermissionList(items: List<Permission>, plugin: FormPlugin?, showNone: Boolean = true) {
    val c = Kin.colors
    val tag = languageTag()
    @Composable
    fun line(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) = Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = c.inkSoft)
        Text(text, style = Kin.type.caption, color = c.ink)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { p ->
            when (p) {
                is Permission.Internet -> line(KinIcons.Globe, stringResource(R.string.plugins_reaches, p.hosts.joinToString(", ")))
                is Permission.Addresses -> line(KinIcons.Wifi, stringResource(R.string.plugins_perm_addresses,
                    p.settings.joinToString(", ") { k -> plugin?.setting(k)?.let { s -> plugin.label(s, tag) } ?: k }))
                Permission.Location -> line(KinIcons.MapPin, stringResource(R.string.plugins_location))
                Permission.Actions -> line(KinIcons.Power, stringResource(R.string.plugins_perm_actions))
            }
        }
        if (items.isEmpty() && showNone) line(KinIcons.Check, stringResource(R.string.plugins_perm_none))
    }
}

/** Before a community plugin is added: who made it and exactly what it may do (Add / Cancel). */
@Composable
fun ConfirmAddDialog(plugin: FormPlugin, onAdd: () -> Unit, onCancel: () -> Unit) {
    val c = Kin.colors
    val tag = languageTag()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.plugins_confirm_title, plugin.name(tag))) },
        text = { ConfirmAddContent(plugin) },
        confirmButton = { TextButton(onAdd) { Text(stringResource(R.string.plugins_add_confirm), color = c.accent) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun ConfirmAddContent(plugin: FormPlugin) {
    val c = Kin.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        communityMeta(plugin).takeIf { it.isNotEmpty() }?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
        Text(stringResource(R.string.plugins_confirm_body), style = Kin.type.body, color = c.ink)
        PermissionList(plugin.permissions.items, plugin)
    }
}

// -------------------------------------------------------------------- editor

/** What the editor can do; [onChange] edits the instance being edited. */
class PluginEditorActions(
    val onChange: ((PluginInstanceDraft) -> PluginInstanceDraft) -> Unit,
    val onSave: () -> Unit,
    val onClose: () -> Unit,
    val onAskRemove: (Boolean) -> Unit,
    val onRemove: () -> Unit,
    /** Approves the waiting registry update with this version / puts it off for now. */
    val onApproveUpdate: (String) -> Unit = {},
    val onDismissUpdate: (String) -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginEditorContent(
    plugin: FormPlugin?,
    editor: PluginEditor,
    report: FramePlugins?,
    saving: Boolean,
    actions: PluginEditorActions,
    dismissedUpdates: Set<String> = emptySet(),
) {
    val c = Kin.colors
    val tag = languageTag()
    val d = editor.draft
    val name = plugin?.name(tag) ?: d.pluginId
    val instanceStatus = report?.instances?.firstOrNull { it.instanceId == d.instanceId }
    var presetAsk by remember { mutableStateOf<FormPreset?>(null) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        KinTopBar(name, onBack = actions.onClose)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (plugin == null) Note(stringResource(R.string.plugins_status_not_on_frame), tone = NoteTone.Warn)
            val status = if (editor.isNew) null else PluginSettings.status(d, report, plugin != null)
            val update = if (editor.isNew || status is InstanceStatus.Withdrawn) null
                else PluginSettings.pendingUpdate(plugin, d, instanceStatus)?.takeIf { "${d.instanceId}@${it.version}" !in dismissedUpdates }
            if (status is InstanceStatus.Withdrawn) WithdrawnCard(status.reason, enabled = !saving) { actions.onAskRemove(true) }
            if (update != null) UpdateCard(update, plugin, saving, actions)
            when (val st = status) {
                is InstanceStatus.Failed -> Note(st.message, tone = NoteTone.Danger, icon = KinIcons.Warning)
                InstanceStatus.Waiting -> Note(stringResource(R.string.plugins_status_waiting))
                else -> Unit
            }
            editor.errors.filter { it.key == null }.forEach { Note(errorText(it), tone = NoteTone.Danger) }
            plugin?.description(tag)?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }

            if (plugin != null && plugin.presets.isNotEmpty()) PresetMenu(plugin, tag) { p ->
                if (p.inputs.isEmpty()) plugin.applyPreset(p.id, d.settings)?.let { s -> actions.onChange { it.copy(settings = s) } } else presetAsk = p
            }

            if (plugin != null && plugin.settings.isNotEmpty()) {
                Surface(Modifier.fillMaxWidth()) {
                    plugin.settings.forEach { s ->
                        val err = editor.errors.firstOrNull { it.key == s.key }?.let { errorText(it) }
                        SettingField(plugin, s, d, tag, err, instanceStatus?.entities, report?.services?.get(s.discover).orEmpty(), actions.onChange)
                    }
                }
            }

            SectionTitle(stringResource(R.string.plugins_placement))
            Surface(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plugins_corner), style = Kin.type.label, color = c.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (plugin?.slots ?: Slots.all).forEach { slot -> ToggleChip(slotLabel(slot), d.slot == slot, { actions.onChange { it.copy(slot = slot) } }) }
                }
                Text(stringResource(R.string.plugins_visibility), style = Kin.type.label, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Visibility.ALWAYS to R.string.plugins_vis_always, Visibility.ON_CHANGE to R.string.plugins_vis_on_change, Visibility.ON_TAP to R.string.plugins_vis_on_tap)
                        .forEach { (v, l) -> ToggleChip(stringResource(l), d.visibility == v, { actions.onChange { it.copy(visibility = v) } }) }
                }
                if (editor.isNew) Text(stringResource(R.string.plugins_new_hint), style = Kin.type.caption, color = c.inkSoft)
                else SwitchRow(stringResource(R.string.plugins_enabled), d.enabled, { on -> actions.onChange { it.copy(enabled = on) } }, hint = stringResource(R.string.plugins_enabled_hint))
            }
            if (plugin != null && (plugin.community || instanceStatus?.version != null)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (plugin.community) {
                        Pill(stringResource(R.string.plugins_group_community), c.paperDeep, c.inkSoft, icon = KinIcons.People)
                        communityMeta(plugin, withVersion = false).takeIf { it.isNotEmpty() }?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                    }
                    instanceStatus?.version?.let { Text(stringResource(R.string.plugins_runs_version, it), style = Kin.type.caption, color = c.inkSoft) }
                }
            }
            if (!editor.isNew) {
                TextButton({ actions.onAskRemove(true) }, enabled = !saving) {
                    Icon(KinIcons.Trash, null, tint = c.danger); Text(stringResource(R.string.plugins_remove), color = c.danger, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            QuietButton(stringResource(R.string.common_cancel), actions.onClose)
            PrimaryButton(stringResource(R.string.common_save), actions.onSave, busy = saving)
        }
    }

    if (editor.confirmRemove) {
        AlertDialog(
            onDismissRequest = { actions.onAskRemove(false) },
            title = { Text(stringResource(R.string.plugins_remove_title, name)) },
            text = { Text(stringResource(R.string.plugins_remove_body)) },
            confirmButton = { TextButton(actions.onRemove) { Text(stringResource(R.string.common_remove), color = c.danger) } },
            dismissButton = { TextButton({ actions.onAskRemove(false) }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    val ask = presetAsk
    if (ask != null && plugin != null) PresetInputsDialog(plugin, ask, d, tag, { presetAsk = null }) { values ->
        presetAsk = null
        actions.onChange { cur ->
            val filled = values.entries.fold(cur) { acc, (k, v) -> PluginSettings.withValue(acc, k, JsonPrimitive(v.trim())) }
            plugin.applyPreset(ask.id, filled.settings)?.let { filled.copy(settings = it) } ?: filled
        }
    }
}

/** A registry update that asks for other permissions: what it asks for, Approve / Not now (§7). */
@Composable
private fun UpdateCard(u: PendingUpdate, plugin: FormPlugin?, saving: Boolean, actions: PluginEditorActions) {
    val c = Kin.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warnSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KinIcons.Shield, null, Modifier.padding(top = 2.dp).size(18.dp), tint = c.warn)
            Text(stringResource(if (u.fewer) R.string.plugins_update_fewer else R.string.plugins_update_asks, u.version), style = Kin.type.bodyStrong, color = c.ink)
        }
        if (!u.fewer) PermissionList(u.asks, plugin)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(R.string.plugins_update_approve), { actions.onApproveUpdate(u.version) }, busy = saving)
            QuietButton(stringResource(R.string.plugins_update_dismiss), { actions.onDismissUpdate(u.version) })
        }
    }
}

/** The plugin was withdrawn from the registry: why, and the suggestion to remove it. */
@Composable
private fun WithdrawnCard(reason: String, enabled: Boolean, onRemove: () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.dangerSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KinIcons.Warning, null, Modifier.padding(top = 2.dp).size(18.dp), tint = c.danger)
            Text(stringResource(R.string.plugins_withdrawn, reason), style = Kin.type.bodyStrong, color = c.danger)
        }
        Text(stringResource(R.string.plugins_withdrawn_hint), style = Kin.type.caption, color = c.ink)
        TextButton(onRemove, enabled = enabled) {
            Icon(KinIcons.Trash, null, tint = c.danger); Text(stringResource(R.string.plugins_remove), color = c.danger, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun errorText(e: InstanceFieldError): String = when (e.code) {
    InstanceFieldError.REQUIRED -> stringResource(R.string.plugins_err_required)
    InstanceFieldError.TYPE -> stringResource(R.string.plugins_err_type)
    InstanceFieldError.TIMEZONE -> stringResource(R.string.plugins_err_timezone)
    "enum" -> stringResource(if (e.field == "slot") R.string.plugins_err_slot else R.string.plugins_err_enum)
    "unknown" -> stringResource(R.string.plugins_err_unknown)
    "secret" -> stringResource(R.string.plugins_err_secret)
    "not_on_frame" -> stringResource(R.string.plugins_err_not_on_frame)
    "duplicate" -> stringResource(R.string.plugins_err_duplicate)
    else -> stringResource(R.string.plugins_err_other, e.code)
}

@Composable
private fun PresetMenu(plugin: FormPlugin, tag: String, onPick: (FormPreset) -> Unit) {
    val c = Kin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).background(c.surface).clickable(role = Role.Button) { open = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KinIcons.Bolt, null, Modifier.size(18.dp), tint = c.accent)
            Text(stringResource(R.string.plugins_fill_in_for), style = Kin.type.label, color = c.ink)
        }
        DropdownMenu(open, { open = false }) {
            plugin.presets.forEach { p -> DropdownMenuItem(text = { Text(plugin.presetLabel(p, tag)) }, onClick = { open = false; onPick(p) }) }
        }
    }
}

@Composable
private fun PresetInputsDialog(plugin: FormPlugin, preset: FormPreset, d: PluginInstanceDraft, tag: String, onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    val values = remember(preset.id) {
        mutableStateMapOf<String, String>().apply { preset.inputs.forEach { k -> put(k, PluginSettings.text(d.settings[k])) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_preset_title, plugin.presetLabel(preset, tag))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.plugins_preset_needs), style = Kin.type.caption, color = Kin.colors.inkSoft)
                preset.inputs.forEach { k ->
                    val s = plugin.setting(k)
                    KinTextField(values[k].orEmpty(), { values[k] = it }, s?.let { plugin.label(it, tag) } ?: k,
                        placeholder = s?.let { plugin.placeholder(it, tag) }, supporting = s?.let { plugin.help(it, tag) })
                }
            }
        },
        confirmButton = {
            TextButton({ onApply(values.toMap()) }, enabled = preset.inputs.all { values[it].orEmpty().isNotBlank() }) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// -------------------------------------------------------------------- fields

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingField(
    plugin: FormPlugin,
    s: FormSetting,
    d: PluginInstanceDraft,
    tag: String,
    error: String?,
    entities: List<DiscoveredEntity>?,
    services: List<DiscoveredService>,
    onChange: ((PluginInstanceDraft) -> PluginInstanceDraft) -> Unit,
) {
    val c = Kin.colors
    val label = plugin.label(s, tag)
    val help = plugin.help(s, tag)
    val value = PluginSettings.displayValue(s, d)
    fun set(v: kotlinx.serialization.json.JsonElement?) = onChange { PluginSettings.withValue(it, s.key, v) }
    when (s.type) {
        SettingType.BOOLEAN -> SwitchRow(label, (value as? JsonPrimitive)?.booleanOrNull ?: false, { set(JsonPrimitive(it)) }, hint = error ?: help)
        SettingType.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(label, help, error)
            val current = (value as? JsonPrimitive)?.contentOrNull
            if (s.options.size <= 4) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.options.forEach { o -> ToggleChip(plugin.optionLabel(s, o, tag), current == o, { set(JsonPrimitive(o)) }) }
                }
            } else {
                ChoiceMenu(s.options.map { it to plugin.optionLabel(s, it, tag) }, current) { set(JsonPrimitive(it)) }
            }
        }
        SettingType.SECRET -> SecretField(s.key, label, help, error, d, onChange)
        SettingType.ENTITY -> EntityField(s, label, help, error, PluginSettings.entityIds(value), entities) { ids -> set(PluginSettings.entityValue(s, ids)) }
        SettingType.TIMEZONE -> TimeZoneField(label, help, error, (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }) { id -> set(id?.let(::JsonPrimitive)) }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // text, number, url, color, duration: a text field that keeps what's typed while the value it means is unchanged.
            var text by remember(d.instanceId, s.key) { mutableStateOf(PluginSettings.text(value)) }
            LaunchedEffect(value) { if ((PluginSettings.parseText(s, text) ?: s.default) != value) text = PluginSettings.text(value) }
            KinTextField(
                text, { t -> text = t; set(PluginSettings.parseText(s, t)) }, label,
                placeholder = plugin.placeholder(s, tag), supporting = help, error = error,
                keyboardOptions = KeyboardOptions(keyboardType = when (s.type) {
                    SettingType.NUMBER -> KeyboardType.Decimal
                    SettingType.URL -> KeyboardType.Uri
                    else -> KeyboardType.Text
                }),
                trailing = if (s.type == SettingType.COLOR) ({ ColorSwatch(text) }) else null,
            )
            if (s.type == SettingType.URL && services.isNotEmpty()) {
                Text(stringResource(R.string.plugins_found_on_network), style = Kin.type.caption, color = c.inkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.forEach { svc ->
                        val host = svc.url.removePrefix("http://").removePrefix("https://").trimEnd('/')
                        ToggleChip("${svc.name} · $host", text.trim() == svc.url, { text = svc.url; set(JsonPrimitive(svc.url)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String, help: String?, error: String?) {
    val c = Kin.colors
    Text(label, style = Kin.type.label, color = c.ink)
    if (help != null) Text(help, style = Kin.type.caption, color = c.inkSoft)
    if (error != null) Text(error, style = Kin.type.caption, color = c.danger)
}

@Composable
private fun ColorSwatch(text: String) {
    val color = Regex("^#([0-9a-fA-F]{6})$").matchEntire(text.trim())?.groupValues?.get(1)?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    if (color != null) Box(Modifier.size(24.dp).clip(CircleShape).background(color).border(1.dp, Kin.colors.line, CircleShape))
}

@Composable
private fun ChoiceMenu(options: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
    val c = Kin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).background(c.surface)
                .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == current }?.second ?: stringResource(R.string.plugins_choose), style = Kin.type.body,
                color = if (current == null) c.inkFaint else c.ink, modifier = Modifier.weight(1f))
            Icon(KinIcons.ArrowDown, null, Modifier.size(18.dp), tint = c.inkSoft)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

@Composable
private fun SecretField(
    key: String,
    label: String,
    help: String?,
    error: String?,
    d: PluginInstanceDraft,
    onChange: ((PluginInstanceDraft) -> PluginInstanceDraft) -> Unit,
) {
    val c = Kin.colors
    val stored = key in d.secretsSet
    val writing = key in d.secretWrites
    val written = d.secretWrites[key]
    var changing by remember(d.instanceId, key) { mutableStateOf(false) }
    fun write(v: String?) = onChange { it.copy(secretWrites = it.secretWrites + (key to v)) }
    fun untouched() = onChange { it.copy(secretWrites = it.secretWrites - key) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            stored && !writing && !changing -> {
                FieldLabel(label, help, error)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(stringResource(R.string.plugins_secret_set), c.sageSoft, c.onSageSoft, icon = KinIcons.Lock)
                    TextButton({ changing = true }) { Text(stringResource(R.string.plugins_secret_change), color = c.accent) }
                    TextButton({ write(null) }) { Text(stringResource(R.string.common_remove), color = c.danger) }
                }
            }
            stored && writing && written == null -> {
                FieldLabel(label, help, error)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.plugins_secret_removed), style = Kin.type.caption, color = c.danger)
                    TextButton({ untouched() }) { Text(stringResource(R.string.common_undo), color = c.accent) }
                }
            }
            else -> {
                KinTextField(
                    written.orEmpty(), { v -> if (v.isEmpty()) untouched() else write(v) },
                    if (stored) "$label · ${stringResource(R.string.plugins_secret_new)}" else label,
                    supporting = help, error = error, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (stored) TextButton({ changing = false; untouched() }) { Text(stringResource(R.string.common_cancel), color = c.accent) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntityField(
    s: FormSetting,
    label: String,
    help: String?,
    error: String?,
    ids: List<String>,
    entities: List<DiscoveredEntity>?,
    onIds: (List<String>) -> Unit,
) {
    val c = Kin.colors
    var query by remember(s.key) { mutableStateOf("") }
    val all = entities.orEmpty()
    fun pick(id: String) {
        onIds(if (s.multiple) (ids + id).distinct() else listOf(id))
        query = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, help, error)
        ids.forEach { id ->
            val e = all.firstOrNull { it.entityId == id }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(e?.name ?: id, style = Kin.type.bodyStrong, color = c.onAccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e?.name != null) Text(id, style = Kin.type.caption, color = c.onAccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ onIds(ids - id) }) { Icon(KinIcons.Close, stringResource(R.string.plugins_entity_remove, e?.name ?: id), tint = c.onAccentSoft) }
            }
        }
        KinTextField(query, { query = it }, stringResource(R.string.plugins_entity_search), trailing = { Icon(KinIcons.Search, null, tint = c.inkSoft) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        val q = query.trim()
        if (all.isEmpty()) {
            Text(stringResource(R.string.plugins_entities_none), style = Kin.type.caption, color = c.inkSoft)
        } else if (q.isNotEmpty() || (ids.isEmpty() && query.isEmpty())) {
            val matches = FormPlugin.entities(all, s, q).filterNot { it.entityId in ids }.take(if (q.isEmpty()) 5 else 12)
            matches.forEach { e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { pick(e.entityId) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(listOfNotNull(e.name ?: e.entityId, e.unit).joinToString(" · "), style = Kin.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.entityId, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (q.isNotEmpty() && matches.isEmpty() && !q.contains('.')) Text(stringResource(R.string.plugins_entity_no_match), style = Kin.type.caption, color = c.inkSoft)
        }
        // Typing an entity id by hand always works.
        if (q.contains('.') && q !in ids && all.none { it.entityId == q }) {
            TextButton({ pick(q) }) { Text(stringResource(R.string.plugins_entity_use, q), color = c.accent) }
        }
    }
}

/** A `timezone` setting: the chosen zone as its city with region and UTC offset, and a search by city, region or id. */
@Composable
private fun TimeZoneField(label: String, help: String?, error: String?, id: String?, onPick: (String?) -> Unit) {
    val c = Kin.colors
    val options = remember { TimeZones.options() }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, help, error)
        if (id != null) {
            // An id this phone doesn't know (stored elsewhere) still shows, as written.
            val o = options.firstOrNull { it.id == id } ?: TimeZones.option(id)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(o.city, style = Kin.type.bodyStrong, color = c.onAccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (o.offsetSeconds == null) o.id else o.detail, style = Kin.type.caption, color = c.onAccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ onPick(null) }) { Icon(KinIcons.Close, stringResource(R.string.plugins_timezone_clear, o.city), tint = c.onAccentSoft) }
            }
        }
        KinTextField(query, { query = it }, stringResource(R.string.plugins_timezone_search), trailing = { Icon(KinIcons.Search, null, tint = c.inkSoft) })
        val q = query.trim()
        val matches = when {
            q.isNotEmpty() -> TimeZones.search(options, q).take(12)
            // Nothing chosen yet: offer this phone's own zone.
            id == null -> options.filter { it.id == java.time.ZoneId.systemDefault().id }
            else -> emptyList()
        }
        matches.filter { it.id != id }.forEach { o -> TimeZoneRow(o) { onPick(o.id); query = "" } }
        if (q.isNotEmpty() && matches.isEmpty()) Text(stringResource(R.string.plugins_timezone_no_match), style = Kin.type.caption, color = c.inkSoft)
    }
}

@Composable
private fun TimeZoneRow(o: TimeZoneOption, onClick: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(o.city, style = Kin.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(o.detail, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
