package app.kinframe.family.ui.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Frame
import app.kinframe.core.api.ImmichAlbum
import app.kinframe.core.api.ItemVia
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceDeletePolicy
import app.kinframe.core.api.PhotoSourceHistory
import app.kinframe.core.api.PhotoSourceLinks
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.PhotoSourceUpdate
import app.kinframe.core.api.SourceLink
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.util.Format

// ---------------------------------------------------------------------------------------------------------- credit

/** "Anna via Immich" where a sender is shown; just the name for photos sent directly. */
@Composable
fun creditName(name: String, via: ItemVia?): String {
    val fmt = stringResource(R.string.source_credit_via)
    return SourceCredit.text(name, via) { n, v -> fmt.format(n, v) }
}

// ---------------------------------------------------------------------------------------------------------- status

@Composable
fun SourceStatusRow(s: PhotoSource) {
    val c = Kin.colors
    val ui = SourceStatus.of(s)
    val (bg, fg) = when (ui.tone) {
        SourceTone.Good -> c.sageSoft to c.onSageSoft
        SourceTone.Danger -> c.dangerSoft to c.danger
        SourceTone.Warn -> c.warnSoft to c.warn
        SourceTone.Paused -> c.paperDeep to c.inkSoft
    }
    val label = stringResource(when (ui.tone) {
        SourceTone.Good -> R.string.source_status_active
        SourceTone.Danger -> R.string.source_status_error
        SourceTone.Warn -> R.string.source_status_attention
        SourceTone.Paused -> R.string.source_status_paused
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(label, bg, fg)
        Text(headline(ui.headline), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
        if (s.running) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun headline(h: SourceHeadline): String = when (h) {
    SourceHeadline.Paused -> stringResource(R.string.source_line_paused)
    SourceHeadline.Running -> stringResource(R.string.source_line_running)
    is SourceHeadline.Importing -> stringResource(R.string.source_line_importing, h.done, h.total)
    is SourceHeadline.Synced -> if (h.next != null) stringResource(R.string.source_line_synced_next, Format.relative(h.at), Format.relative(h.next))
        else stringResource(R.string.source_line_synced, Format.relative(h.at))
    SourceHeadline.Waiting -> stringResource(R.string.source_line_waiting)
    is SourceHeadline.Failing -> if (h.since != null) stringResource(R.string.source_line_failing_since, Format.dateTime(h.since)) else stringResource(R.string.source_line_failing)
    SourceHeadline.NeedsYou -> stringResource(R.string.source_line_needs_you)
}

@Composable
private fun problemText(ui: SourceStatusUi): String? {
    val p = ui.problem ?: return null
    return stringResource(p, *ui.problemArgs.toTypedArray())
}

// ---------------------------------------------------------------------------------------------------------- list

/** One source with its status, counts and what you can do with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceCard(s: PhotoSource, busy: Boolean, showTarget: Boolean, vm: PhotoSourcesViewModel, open: (SourceDialog) -> Unit) {
    val c = Kin.colors
    val ui = SourceStatus.of(s)
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (s.provider == PhotoSourceProvider.IMMICH_API) KinIcons.Server else KinIcons.Link, null, tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(s.name, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(SourceStatus.providerName(s.provider)) + (if (s.canManage) " · " + SourceStatus.host(s) else ""),
                    style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.canManage) Column {
                IconButton({ menu = true }, enabled = !busy) { Icon(KinIcons.More, stringResource(R.string.source_more), tint = c.inkSoft) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.source_edit)) }, { menu = false; open(SourceDialog.Edit(s, SourceFix.None)) })
                    if (!s.isOwner) DropdownMenuItem({ Text(stringResource(R.string.source_take_over)) }, { menu = false; open(SourceDialog.TakeOver(s)) })
                    DropdownMenuItem({ Text(stringResource(R.string.source_delete), color = c.danger) }, { menu = false; open(SourceDialog.Delete(s)) })
                }
            }
        }
        if (showTarget) {
            Text(if (s.target.isFeed) stringResource(R.string.source_into_feed, s.target.feedName.orEmpty())
                else stringResource(R.string.source_into_album, s.target.albumName.orEmpty(), s.target.frameName.orEmpty()),
                style = Kin.type.caption, color = c.inkSoft)
        }
        SourceStatusRow(s)
        problemText(ui)?.let { text ->
            if (ui.retrying) Text(stringResource(R.string.source_retrying, text), style = Kin.type.caption, color = c.inkFaint)
            else Note(text, tone = if (ui.tone == SourceTone.Danger) NoteTone.Danger else NoteTone.Warn)
        }
        if (ui.fix != SourceFix.None) {
            val label = stringResource(when (ui.fix) {
                SourceFix.NewLink -> R.string.source_fix_link
                SourceFix.NewPassword -> R.string.source_fix_password
                SourceFix.NewKey -> R.string.source_fix_key
                SourceFix.TakeOver -> R.string.source_take_over
                else -> R.string.source_fix_removals
            })
            PrimaryButton(label, {
                when (ui.fix) {
                    SourceFix.TakeOver -> open(SourceDialog.TakeOver(s))
                    SourceFix.ConfirmRemovals -> open(SourceDialog.Removals(s))
                    else -> open(SourceDialog.Edit(s, ui.fix))
                }
            }, enabled = !busy)
        }
        s.warnings.mapNotNull { SourceStatus.warning(it) }.forEach { Text(stringResource(it), style = Kin.type.caption, color = c.warn) }
        if (s.bestEffort) Text(stringResource(R.string.source_best_effort_short), style = Kin.type.caption, color = c.inkFaint)
        val counts = buildList {
            add(pluralStringResource(R.plurals.source_count_imported, s.counts.imported, s.counts.imported))
            if (s.counts.skipped > 0) add(pluralStringResource(R.plurals.source_count_skipped, s.counts.skipped, s.counts.skipped))
            if (s.counts.failed > 0) add(pluralStringResource(R.plurals.source_count_failed, s.counts.failed, s.counts.failed))
        }.joinToString(" · ")
        Text(counts, style = Kin.type.caption, color = c.inkSoft)
        Text(when {
            s.owner == null -> stringResource(R.string.source_owner_gone)
            s.isOwner -> stringResource(R.string.source_owner_you)
            else -> stringResource(R.string.source_owner, s.owner?.displayName.orEmpty())
        }, style = Kin.type.caption, color = c.inkFaint)
        if (s.canManage) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.isPaused) SecondaryButton(stringResource(R.string.source_sync_now), { vm.syncNow(s) }, enabled = !busy && !s.running, icon = KinIcons.Refresh)
            QuietButton(stringResource(if (s.isPaused) R.string.source_resume else R.string.source_pause), { vm.setPaused(s, !s.isPaused) }, enabled = !busy)
        }
    }
}

/** The dialogs a source list opens. */
sealed interface SourceDialog {
    data class Edit(val source: PhotoSource, val focus: SourceFix) : SourceDialog
    data class TakeOver(val source: PhotoSource) : SourceDialog
    data class Delete(val source: PhotoSource) : SourceDialog
    data class Removals(val source: PhotoSource) : SourceDialog
}

/** The cards and their dialogs; used by an album, a feed and "My photo sources". */
@Composable
fun SourceList(state: SourcesState, vm: PhotoSourcesViewModel, showTarget: Boolean, empty: (@Composable () -> Unit)? = null) {
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    when (val l = state.sources) {
        Load.Loading -> SkeletonList(1, 120.dp)
        is Load.Error -> ErrorPanel(l.message, { vm.load() })
        is Load.Ready -> {
            if (l.value.isEmpty()) empty?.invoke()
            l.value.forEach { s -> SourceCard(s, s.id in state.busy, showTarget, vm) { dialog = it } }
        }
    }
    when (val d = dialog) {
        is SourceDialog.Edit -> EditSourceDialog(d.source, d.focus, { dialog = null }) { u -> dialog = null; vm.update(d.source, u) }
        is SourceDialog.TakeOver -> TakeOverDialog(d.source, { dialog = null }) { key -> dialog = null; vm.takeOver(d.source, key) }
        is SourceDialog.Delete -> DeleteSourceDialog(d.source, { dialog = null }) { remove -> dialog = null; vm.delete(d.source, remove) }
        is SourceDialog.Removals -> RemovalsDialog(d.source, { dialog = null },
            onRemove = { dialog = null; vm.confirmRemovals(d.source) },
            onKeep = { dialog = null; vm.update(d.source, PhotoSourceUpdate(onUpstreamDelete = PhotoSourceDeletePolicy.KEEP)) })
        null -> Unit
    }
}

/**
 * "Synced from…" under an album (album managers) or a feed's Sources tab (owner/admins): the sources, "Sync from…" and
 * "Add from Google Photos". [onAdd] / [onGoogle] are null when the server doesn't offer them.
 */
@Composable
fun PhotoSourcesSection(state: SourcesState, vm: PhotoSourcesViewModel, feed: Boolean, onAdd: (() -> Unit)?, onGoogle: (() -> Unit)?) {
    val c = Kin.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onAdd != null) {
            Text(stringResource(R.string.source_section_title), style = Kin.type.bodyStrong, color = c.ink)
            SourceList(state, vm, showTarget = false) {
                Text(stringResource(if (feed) R.string.source_section_empty_feed else R.string.source_section_empty_album), style = Kin.type.caption, color = c.inkSoft)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onAdd?.let { SecondaryButton(stringResource(if (feed) R.string.source_add_feed else R.string.source_add_album), it, icon = KinIcons.Link) }
            onGoogle?.let { SecondaryButton(stringResource(R.string.google_add), it, icon = KinIcons.Image) }
        }
    }
}

/** Settings → My photo sources: everything you set up, across albums and feeds. */
@Composable
fun MyPhotoSourcesScreen(state: SourcesState, vm: PhotoSourcesViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        KinTopBar(stringResource(R.string.source_mine_title), onBack = onBack)
        PageColumn(spacing = 12.dp) {
            Text(stringResource(R.string.source_mine_intro), style = Kin.type.body, color = Kin.colors.inkSoft)
            SourceList(state, vm, showTarget = true) {
                EmptyState(stringResource(R.string.source_mine_empty_title), stringResource(R.string.source_mine_empty_body), art = {})
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- dialogs

@Composable
private fun RadioRow(text: String, selected: Boolean, hint: String? = null, onClick: () -> Unit) {
    val c = Kin.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
        Column(Modifier.padding(start = 8.dp)) {
            Text(text, style = Kin.type.body, color = c.ink)
            hint?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
        }
    }
}

@Composable
private fun DeletePolicyPicker(value: String, feed: Boolean, service: String, onChange: (String) -> Unit) {
    Text(stringResource(R.string.source_delete_policy_title, service), style = Kin.type.label, color = Kin.colors.ink)
    RadioRow(stringResource(R.string.source_policy_mirror), value == PhotoSourceDeletePolicy.MIRROR,
        if (!feed) stringResource(R.string.source_policy_default) else null) { onChange(PhotoSourceDeletePolicy.MIRROR) }
    RadioRow(stringResource(R.string.source_policy_keep), value == PhotoSourceDeletePolicy.KEEP,
        if (feed) stringResource(R.string.source_policy_default) else null) { onChange(PhotoSourceDeletePolicy.KEEP) }
}

/** Change a source: name, deletion behaviour, videos, reactions (the owner only) and the connection. */
@Composable
fun EditSourceDialog(s: PhotoSource, focus: SourceFix, onDismiss: () -> Unit, onSave: (PhotoSourceUpdate) -> Unit) {
    val c = Kin.colors
    var name by remember { mutableStateOf(s.name) }
    var policy by remember { mutableStateOf(s.onUpstreamDelete) }
    var videos by remember { mutableStateOf(s.includeVideos) }
    var notify by remember { mutableStateOf(s.notifyReactions) }
    var link by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var removePassword by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(s.serverUrl.orEmpty()) }
    var key by remember { mutableStateOf("") }
    val api = s.provider == PhotoSourceProvider.IMMICH_API
    val linkIssue = link.isNotBlank() && PhotoSourceLinks.parse(link)?.provider != s.provider
    val keyIssue = key.isNotBlank() && key.trim().length < 8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_edit_title, s.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (focus == SourceFix.None) {
                    KinTextField(name, { name = it.take(60) }, stringResource(R.string.source_name))
                    DeletePolicyPicker(policy, s.target.isFeed, s.serviceLabel) { policy = it }
                    SwitchRow(stringResource(R.string.source_include_videos), videos, { videos = it })
                    if (s.isOwner) SwitchRow(stringResource(R.string.source_notify_reactions), notify, { notify = it }, hint = stringResource(R.string.source_notify_reactions_hint))
                    Text(stringResource(R.string.source_connection), style = Kin.type.label, color = c.ink)
                }
                if (s.isLink && (focus == SourceFix.None || focus == SourceFix.NewLink)) {
                    s.urlHint?.let { Text(stringResource(R.string.source_current_link, it), style = Kin.type.caption, color = c.inkSoft) }
                    KinTextField(link, { link = it.take(2048) }, stringResource(R.string.source_new_link), error = if (linkIssue) stringResource(R.string.source_link_invalid) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                if (s.provider == PhotoSourceProvider.IMMICH_LINK && focus != SourceFix.NewLink && focus != SourceFix.NewKey) {
                    KinTextField(password, { password = it.take(200); if (it.isNotEmpty()) removePassword = false }, stringResource(R.string.source_password),
                        supporting = stringResource(if (s.hasPassword) R.string.source_password_has else R.string.source_password_hint),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (s.hasPassword && focus == SourceFix.None) Row(Modifier.clickable(role = Role.Checkbox) { removePassword = !removePassword; if (removePassword) password = "" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(removePassword, null, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                        Text(stringResource(R.string.source_password_remove), style = Kin.type.body, color = c.ink, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (api && (focus == SourceFix.None || focus == SourceFix.NewKey)) {
                    KinTextField(server, { server = it.take(2048) }, stringResource(R.string.source_server), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    KinTextField(key, { key = it.take(200) }, stringResource(R.string.source_new_key), error = if (keyIssue) stringResource(R.string.source_need_key) else null,
                        supporting = stringResource(R.string.source_key_permissions), visualTransformation = PasswordVisualTransformation())
                }
            }
        },
        confirmButton = {
            TextButton({
                val serverChanged = api && server.isNotBlank() && server.trim() != s.serverUrl
                onSave(PhotoSourceUpdate(
                    name = name.trim().takeIf { it.isNotEmpty() && it != s.name },
                    url = link.trim().takeIf { it.isNotEmpty() }?.let { PhotoSourceLinks.firstUrl(it) },
                    password = password.takeIf { it.isNotEmpty() }, removePassword = removePassword,
                    serverUrl = server.trim().takeIf { serverChanged }?.let { (PhotoSourceLinks.serverAddress(it) as? app.kinframe.core.api.ServerAddress.Ok)?.url ?: it },
                    apiKey = key.trim().takeIf { it.isNotEmpty() },
                    onUpstreamDelete = policy.takeIf { it != s.onUpstreamDelete },
                    includeVideos = videos.takeIf { it != s.includeVideos },
                    notifyReactions = notify.takeIf { it != s.notifyReactions && s.isOwner },
                ))
            }, enabled = !linkIssue && !keyIssue) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Become the owner: future photos are credited to you. An Immich server source needs your own API key. */
@Composable
fun TakeOverDialog(s: PhotoSource, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var key by remember { mutableStateOf("") }
    val needsKey = s.provider == PhotoSourceProvider.IMMICH_API
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_take_over_title, s.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.source_take_over_body), style = Kin.type.body, color = Kin.colors.ink)
                if (needsKey) KinTextField(key, { key = it.take(200) }, stringResource(R.string.source_api_key),
                    supporting = stringResource(R.string.source_take_over_key), visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton({ onConfirm(key.trim().takeIf { it.isNotEmpty() }) }, enabled = !needsKey || key.trim().length >= 8) { Text(stringResource(R.string.source_take_over)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Stop syncing: keep the imported photos (as your ordinary photos, still "via …") or remove them. */
@Composable
fun DeleteSourceDialog(s: PhotoSource, onDismiss: () -> Unit, onConfirm: (removeItems: Boolean) -> Unit) {
    var remove by remember { mutableStateOf(false) }
    val n = s.counts.imported
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_delete_title, s.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (s.target.isFeed) R.string.source_delete_body_feed else R.string.source_delete_body_album), style = Kin.type.body, color = Kin.colors.ink)
                RadioRow(pluralStringResource(R.plurals.source_delete_keep, n, n), !remove) { remove = false }
                RadioRow(pluralStringResource(R.plurals.source_delete_remove, n, n), remove) { remove = true }
            }
        },
        confirmButton = { TextButton({ onConfirm(remove) }) { Text(stringResource(R.string.source_delete), color = Kin.colors.danger) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The mass-removal guard held back removals: apply them, or keep the photos (switches the source to "keep"). */
@Composable
fun RemovalsDialog(s: PhotoSource, onDismiss: () -> Unit, onRemove: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.source_removals_title, s.pendingRemovals, s.pendingRemovals, s.serviceLabel)) },
        text = { Text(stringResource(R.string.source_removals_body), style = Kin.type.body) },
        confirmButton = { TextButton(onRemove) { Text(stringResource(R.string.source_removals_remove), color = Kin.colors.danger) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.source_removals_keep)) } },
    )
}

// ---------------------------------------------------------------------------------------------------------- wizard

/** "Sync from…": the add-source flow, one step at a time. */
@Composable
fun AddSourceScreen(state: AddSourceState, vm: AddSourceViewModel, onBack: () -> Unit, onDone: (PhotoSource) -> Unit) {
    val c = Kin.colors
    BackHandler { if (!vm.back()) onBack() }
    LaunchedEffect(state.created) { state.created?.let(onDone) }
    val title = if (state.target.isFeed) stringResource(R.string.source_add_title_feed, state.target.name)
    else stringResource(R.string.source_add_title_album, state.target.name)
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(title, onBack = { if (!vm.back()) onBack() })
        PageColumn(spacing = 12.dp) {
            when (state.step) {
                SourceStep.Provider -> ProviderStep(state, vm)
                SourceStep.Connection -> ConnectionStep(state, vm)
                SourceStep.Album -> AlbumStep(state, vm)
                SourceStep.Options -> OptionsStep(state, vm)
            }
        }
    }
}

@Composable
private fun ProviderStep(state: AddSourceState, vm: AddSourceViewModel) {
    val c = Kin.colors
    Text(stringResource(R.string.source_provider_intro), style = Kin.type.body, color = c.inkSoft)
    if (state.providers.isEmpty()) Note(stringResource(R.string.source_problem_disabled), tone = NoteTone.Warn)
    state.providers.forEach { p ->
        Surface(Modifier.fillMaxWidth(), onClick = { vm.choose(p) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(SourceStatus.providerName(p)), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                when (p) {
                    PhotoSourceProvider.IMMICH_LINK -> Pill(stringResource(R.string.source_recommended), c.sageSoft, c.onSageSoft)
                    PhotoSourceProvider.ICLOUD_LINK -> Pill(stringResource(R.string.source_best_effort), c.warnSoft, c.warn)
                }
                Icon(KinIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.inkFaint)
            }
            Text(stringResource(when (p) {
                PhotoSourceProvider.IMMICH_API -> R.string.source_provider_immich_api_hint
                PhotoSourceProvider.ICLOUD_LINK -> R.string.source_provider_icloud_hint
                else -> R.string.source_provider_immich_link_hint
            }), style = Kin.type.caption, color = c.inkSoft)
        }
    }
}

@Composable
private fun ConnectionStep(state: AddSourceState, vm: AddSourceViewModel) {
    val c = Kin.colors
    val provider = state.provider ?: return
    SectionTitle(stringResource(SourceStatus.providerName(provider)))
    val issue = state.connectionIssue
    val typed = when (provider) {
        PhotoSourceProvider.IMMICH_API -> state.serverUrl.isNotBlank() || state.apiKey.isNotBlank()
        else -> state.link.isNotBlank()
    }
    when (provider) {
        PhotoSourceProvider.IMMICH_LINK -> {
            Note(stringResource(R.string.source_immich_link_how))
            KinTextField(state.link, vm::onLink, stringResource(R.string.source_link), placeholder = "https://photos.example.org/share/…",
                error = issue?.takeIf { typed && it != R.string.source_need_link }?.let { stringResource(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            KinTextField(state.password, vm::onPassword, stringResource(R.string.source_password), supporting = stringResource(R.string.source_password_hint),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        PhotoSourceProvider.IMMICH_API -> {
            Note(stringResource(R.string.source_immich_api_how))
            KinTextField(state.serverUrl, vm::onServer, stringResource(R.string.source_server), placeholder = "https://photos.example.org",
                error = issue?.takeIf { typed && state.serverUrl.isNotBlank() && it != R.string.source_need_key }?.let { stringResource(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            KinTextField(state.apiKey, vm::onKey, stringResource(R.string.source_api_key), supporting = stringResource(R.string.source_key_permissions),
                visualTransformation = PasswordVisualTransformation())
        }
        PhotoSourceProvider.ICLOUD_LINK -> {
            Note(stringResource(R.string.source_best_effort_note), tone = NoteTone.Warn)
            Note(stringResource(R.string.source_icloud_how))
            KinTextField(state.link, vm::onLink, stringResource(R.string.source_link), placeholder = "https://www.icloud.com/sharedalbum/#…",
                error = issue?.takeIf { typed && it != R.string.source_need_link }?.let { stringResource(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
    }
    if (state.privateHint) Note(stringResource(R.string.source_private_hint), tone = NoteTone.Warn)
    state.testError?.let { Note(stringResource(it, *state.testErrorArgs.toTypedArray()), tone = NoteTone.Danger) }
    PrimaryButton(stringResource(R.string.source_test), vm::test, Modifier.fillMaxWidth(), enabled = issue == null, busy = state.testing)
}

@Composable
private fun AlbumStep(state: AddSourceState, vm: AddSourceViewModel) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.source_pick_album))
    state.test?.serverVersion?.let { Text(stringResource(R.string.source_server_version, it), style = Kin.type.caption, color = c.inkSoft) }
    if (state.albums.isEmpty()) Note(stringResource(R.string.source_no_albums), tone = NoteTone.Warn)
    state.albums.forEach { a: ImmichAlbum ->
        RadioRow(a.name, state.albumId == a.id, listOfNotNull(
            pluralStringResource(R.plurals.source_album_items, a.assetCount, a.assetCount),
            a.ownerName?.takeIf { a.shared }?.let { stringResource(R.string.source_album_shared_by, it) },
        ).joinToString(" · ")) { vm.pickAlbum(a.id) }
    }
    PrimaryButton(stringResource(R.string.common_continue), vm::continueFromAlbum, Modifier.fillMaxWidth(), enabled = state.albumId != null)
}

@Composable
private fun OptionsStep(state: AddSourceState, vm: AddSourceViewModel) {
    val c = Kin.colors
    val context = LocalContext.current
    val service = if (state.provider == PhotoSourceProvider.ICLOUD_LINK) "iCloud" else "Immich"
    state.test?.let { t ->
        Surface(Modifier.fillMaxWidth()) {
            Text(state.album?.name ?: t.albumName.orEmpty(), style = Kin.type.title, color = c.ink)
            val parts = listOfNotNull(
                t.photos?.let { pluralStringResource(R.plurals.history_photos, it, it) },
                t.videos?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.history_videos, it, it) },
                t.estimateBytes?.let { stringResource(R.string.source_about_size, Format.bytes(context, it)) },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = Kin.type.caption, color = c.inkSoft)
            t.warnings.mapNotNull { SourceStatus.warning(it) }.forEach { Text(stringResource(it), style = Kin.type.caption, color = c.warn) }
        }
    }
    if (state.bestEffort) Note(stringResource(R.string.source_best_effort_note), tone = NoteTone.Warn)
    KinTextField(state.name, vm::setName, stringResource(R.string.source_name))
    Text(stringResource(R.string.source_history_title), style = Kin.type.label, color = c.ink)
    RadioRow(stringResource(R.string.source_history_all), state.history == PhotoSourceHistory.ALL) { vm.setHistory(PhotoSourceHistory.ALL) }
    RadioRow(stringResource(R.string.source_history_last_n, state.historyCount), state.history == PhotoSourceHistory.LAST_N) { vm.setHistory(PhotoSourceHistory.LAST_N) }
    if (state.history == PhotoSourceHistory.LAST_N) {
        var count by remember { mutableStateOf(state.historyCount.toString()) }
        KinTextField(count, { v -> count = v.filter { it.isDigit() }.take(5); count.toIntOrNull()?.let { vm.setHistory(PhotoSourceHistory.LAST_N, it) } },
            stringResource(R.string.source_history_count), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    RadioRow(stringResource(R.string.source_history_none), state.history == PhotoSourceHistory.NONE) { vm.setHistory(PhotoSourceHistory.NONE) }
    Text(stringResource(R.string.source_history_quiet), style = Kin.type.caption, color = c.inkSoft)
    DeletePolicyPicker(state.onUpstreamDelete, state.target.isFeed, service, vm::setDeletePolicy)
    SwitchRow(stringResource(R.string.source_include_videos), state.includeVideos, vm::setIncludeVideos)
    SwitchRow(stringResource(R.string.source_notify_reactions), state.notifyReactions, vm::setNotifyReactions, hint = stringResource(R.string.source_notify_reactions_hint))
    if (state.suggestAlbumOnly) {
        Note(stringResource(R.string.source_album_only_suggest, state.firstImportCount ?: 0), tone = NoteTone.Info)
        SwitchRow(stringResource(R.string.albums_only_mode), state.albumOnly, vm::setAlbumOnly, hint = stringResource(R.string.albums_only_mode_hint))
    }
    Note(if (state.target.isFeed) stringResource(R.string.source_who_sees_feed, state.target.name) else stringResource(R.string.source_who_sees_album, state.target.frameName ?: state.target.name))
    Text(stringResource(if (state.target.isFeed) R.string.source_cadence_feed else R.string.source_cadence_album), style = Kin.type.caption, color = c.inkSoft)
    state.createError?.let { Note(stringResource(it), tone = NoteTone.Danger) }
    PrimaryButton(stringResource(R.string.source_connect), vm::create, Modifier.fillMaxWidth(), enabled = state.canCreate, busy = state.creating)
}

// ---------------------------------------------------------------------------------------------------------- Google

/** "Add from Google Photos": consent, picking, copying, the result. Shown over the app while an import is active. */
@Composable
fun GoogleImportDialog(state: GoogleImportState, flow: GoogleImportFlow) {
    val c = Kin.colors
    if (!state.active) return
    val g = state.import
    val p = g?.progress
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.google_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.target?.name?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.google_into, it), style = Kin.type.caption, color = c.inkSoft) }
                when (state.phase) {
                    GooglePhase.Starting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.google_starting), style = Kin.type.body, color = c.ink)
                    }
                    GooglePhase.Consent -> Text(stringResource(R.string.google_consent), style = Kin.type.body, color = c.ink)
                    GooglePhase.Picking -> {
                        Text(stringResource(R.string.google_picking), style = Kin.type.body, color = c.ink)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                    }
                    GooglePhase.Importing -> {
                        val total = p?.picked ?: 0
                        Text(if (total > 0) pluralStringResource(R.plurals.google_importing, total, total, p?.handled ?: 0) else stringResource(R.string.google_importing_start),
                            style = Kin.type.body, color = c.ink)
                        if (total > 0) LinearProgressIndicator({ ((p?.handled ?: 0).toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = c.accent)
                        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                        Text(stringResource(R.string.google_importing_hint), style = Kin.type.caption, color = c.inkSoft)
                    }
                    GooglePhase.Done, GooglePhase.Cancelled -> {
                        val n = p?.imported ?: 0
                        Text(if (state.phase == GooglePhase.Done) pluralStringResource(R.plurals.google_done, n, n) else pluralStringResource(R.plurals.google_cancelled, n, n),
                            style = Kin.type.bodyStrong, color = c.ink)
                        p?.duplicates?.takeIf { it > 0 }?.let { Text(pluralStringResource(R.plurals.google_duplicates, it, it), style = Kin.type.caption, color = c.inkSoft) }
                        val bad = (p?.failed ?: 0) + (p?.skipped ?: 0)
                        if (bad > 0) Text(pluralStringResource(R.plurals.google_failed, bad, bad), style = Kin.type.caption, color = c.warn)
                        if (state.target?.isFeed == true && n > 0) Text(stringResource(R.string.google_done_feed), style = Kin.type.caption, color = c.inkSoft)
                        if (state.undone) Note(stringResource(R.string.google_undone), tone = NoteTone.Good, icon = KinIcons.Check)
                    }
                    GooglePhase.Failed -> Note(stringResource(GoogleImportFlow.errorText(g?.errorCode)), tone = NoteTone.Danger)
                    GooglePhase.Expired -> Note(stringResource(R.string.google_error_expired), tone = NoteTone.Warn)
                    GooglePhase.Error -> Note(stringResource(state.error ?: R.string.google_error_generic), tone = NoteTone.Danger)
                    GooglePhase.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                GooglePhase.Consent -> TextButton(flow::reopen) { Text(stringResource(R.string.google_open_signin)) }
                GooglePhase.Picking -> TextButton(flow::reopen) { Text(stringResource(R.string.google_open_picker)) }
                GooglePhase.Importing -> TextButton(flow::dismiss) { Text(stringResource(R.string.google_hide)) }
                GooglePhase.Failed, GooglePhase.Expired -> TextButton({ state.target?.let(flow::start) }) { Text(stringResource(R.string.google_try_again)) }
                GooglePhase.Starting -> Unit
                else -> TextButton(flow::dismiss) { Text(stringResource(R.string.common_close)) }
            }
        },
        dismissButton = {
            when (state.phase) {
                GooglePhase.Consent, GooglePhase.Picking, GooglePhase.Importing, GooglePhase.Starting ->
                    TextButton(flow::cancel, enabled = !state.cancelling) { Text(stringResource(R.string.common_cancel)) }
                GooglePhase.Done, GooglePhase.Cancelled -> if (!g?.sendIds.isNullOrEmpty() && !state.undone) TextButton(flow::undo) { Text(stringResource(R.string.common_undo)) }
                GooglePhase.Failed, GooglePhase.Expired -> TextButton(flow::dismiss) { Text(stringResource(R.string.common_close)) }
                else -> Unit
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------- shared links

/** An album or feed a shared link can sync into. */
data class SyncChoice(val target: SourceTarget, val label: String)

/** Albums of frames you manage the library of, and feeds you own or administer. */
suspend fun syncChoices(api: KinApi, feeds: Boolean, feedLabel: (String) -> String, albumLabel: (String, String) -> String): List<SyncChoice> {
    val frames: List<Frame> = runCatching { api.frames() }.getOrDefault(emptyList()).filter { it.can { p -> p.manageLibrary } }
    val albums = frames.flatMap { f ->
        runCatching { api.albums(f.id) }.getOrDefault(emptyList()).map { a -> SyncChoice(SourceTarget.album(f.id, f.name, a.id, a.name), albumLabel(a.name, f.name)) }
    }
    val fs = if (!feeds) emptyList() else runCatching { api.feeds() }.getOrDefault(emptyList()).filter { it.isAdmin }
        .map { SyncChoice(SourceTarget.feed(it.id, it.name), feedLabel(it.name)) }
    return albums + fs
}

/** "Sync this album to…" for an Immich or iCloud link shared into the app from another app. */
@Composable
fun SyncLinkDialog(link: SourceLink, choices: List<SyncChoice>?, onDismiss: () -> Unit, onPick: (SyncChoice) -> Unit) {
    val c = Kin.colors
    var picked by remember { mutableStateOf<SyncChoice?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_shared_title, if (link is SourceLink.ICloudSharedAlbum) "iCloud" else "Immich")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    choices == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                    choices.isEmpty() -> Text(stringResource(R.string.source_shared_none), style = Kin.type.body, color = c.ink)
                    else -> {
                        Text(stringResource(R.string.source_shared_body), style = Kin.type.caption, color = c.inkSoft)
                        choices.forEach { ch -> RadioRow(ch.label, picked == ch) { picked = ch } }
                    }
                }
            }
        },
        confirmButton = { TextButton({ picked?.let(onPick) }, enabled = picked != null) { Text(stringResource(R.string.common_continue)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
