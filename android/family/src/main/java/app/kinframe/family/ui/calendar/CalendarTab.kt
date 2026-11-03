package app.kinframe.family.ui.calendar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kinframe.core.api.CalendarColors
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarScheduleDto
import app.kinframe.core.api.CalendarSource
import app.kinframe.core.api.CalendarStatus
import app.kinframe.core.api.CalendarView
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.Frame
import app.kinframe.core.api.KinJson
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.Skeleton
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.ui.frame.FrameSettingsViewModel
import app.kinframe.family.ui.frame.SettingsFormState
import app.kinframe.family.util.Format
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** Everything the Calendar tab does; one class so the screen stays testable with fakes. */
class CalendarActions(
    val vm: CalendarViewModel?,
    val settings: FrameSettingsViewModel?,
    val me: String?,
    val meName: String,
)

/**
 * A frame's Calendar tab (web `FrameCalendar.svelte`): calendars managers add, events people send, the frame's
 * calendar settings and a preview of what the frame shows with countdowns. Shown when the server has calendars on
 * and the person may change settings or send.
 */
@Composable
fun CalendarTab(
    frame: Frame,
    enabled: Boolean,
    state: CalendarState,
    form: SourceForm?,
    settingsState: SettingsFormState?,
    a: CalendarActions,
) {
    val manage = frame.can { it.changeSettings }
    val mayPut = frame.can { it.send }
    var sendOpen by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CalendarSource?>(null) }
    var detail by remember { mutableStateOf<CalendarEvent?>(null) }
    PageColumn {
        when {
            !enabled -> Note(stringResource(R.string.cal_server_off))
            state.error != null -> ErrorPanel(state.error, { a.vm?.load() })
            else -> {
                if (manage) SourcesSection(state, a, onRemove = { removing = it })
                if (mayPut || manage) SentSection(frame, state, a, mayPut, onSend = { sendOpen = true })
                if (manage && settingsState != null && a.settings != null) CalendarSettingsSection(settingsState, a.settings)
                if (manage) {
                    SectionTitle(stringResource(R.string.cal_preview_title))
                    val v = state.view
                    if (v == null) Skeleton(Modifier.fillMaxWidth().height(320.dp))
                    else CalendarPreview(v, settingsState, onOpen = { detail = it })
                }
            }
        }
    }
    if (form != null && a.vm != null) SourceDialog(form, a.vm)
    removing?.let { s ->
        val label = sourceLabel(s)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.cal_remove_title, label)) },
            text = { Text(stringResource(if (s.isLocal) R.string.cal_remove_received_body else R.string.cal_remove_body)) },
            confirmButton = { TextButton({ removing = null; a.vm?.remove(s, label) }) { Text(stringResource(R.string.cal_remove), color = Kin.colors.danger) } },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    detail?.let { ev -> EventDetailDialog(ev, state.view, manage, state.busyCountdown, { detail = null }) { a.vm?.toggleCountdown(ev) { detail = it } } }
    if (sendOpen && a.vm != null) SendEventDialog(frame.name, a.meName, state, a.vm) { sendOpen = false }
}

@Composable
fun sourceLabel(s: CalendarSource): String = if (s.isLocal && s.name.isEmpty()) stringResource(R.string.cal_received) else s.name

/** A palette colour for the current theme. */
@Composable
fun calColor(key: String?): Color {
    val sw = CalendarColors.swatch(key)
    return Color(if (Kin.colors.paper.luminance() < 0.5f) sw.dark else sw.light)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------ sources

@Composable
private fun SourcesSection(state: CalendarState, a: CalendarActions, onRemove: (CalendarSource) -> Unit) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.cal_sources_title)) {
        TextButton({ a.vm?.openAdd() }) { Icon(KinIcons.Plus, null, tint = c.accent); Text(stringResource(R.string.cal_add), color = c.accent) }
    }
    Text(stringResource(R.string.cal_sources_hint), style = Kin.type.caption, color = c.inkSoft)
    val sources = state.sources
    when {
        sources == null -> Skeleton(Modifier.fillMaxWidth().height(120.dp))
        sources.isEmpty() -> Note(stringResource(R.string.cal_none))
        else -> Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
            sources.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = c.line)
                SourceRow(s, a, onRemove)
            }
        }
    }
}

@Composable
private fun SourceRow(s: CalendarSource, a: CalendarActions, onRemove: (CalendarSource) -> Unit) {
    val c = Kin.colors
    val label = sourceLabel(s)
    Column(Modifier.fillMaxWidth().padding(8.dp).alpha(if (s.enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(calColor(s.color)), contentAlignment = Alignment.Center) {
                if (s.isLocal) Icon(KinIcons.Heart, null, Modifier.size(14.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (s.kind) {
                        "local" -> stringResource(R.string.cal_received_hint)
                        "caldav" -> stringResource(R.string.cal_kind_caldav) + " · " + s.username.orEmpty()
                        else -> s.urlHint ?: stringResource(R.string.cal_kind_ics)
                    },
                    style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val showLabel = stringResource(R.string.cal_show) + ": " + label
            Switch(s.enabled, { a.vm?.setEnabled(s, it) }, Modifier.semantics { contentDescription = showLabel },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val failed = s.status == CalendarStatus.FAILED || s.status == CalendarStatus.AUTH_FAILED
            val color = when { failed -> c.danger; s.status == CalendarStatus.OK -> c.sage; else -> c.inkSoft }
            val statusIcon = when { failed -> KinIcons.Warning; s.status == CalendarStatus.OK -> KinIcons.Check; s.status == CalendarStatus.SYNCING -> KinIcons.Refresh; else -> null }
            statusIcon?.let { Icon(it, null, Modifier.size(14.dp), tint = color) }
            var text = statusText(s)
            if (s.status == CalendarStatus.OK && s.eventCount != null) text += " · " + pluralStringResource(R.plurals.cal_events, s.eventCount!!, s.eventCount!!)
            Text(text, style = Kin.type.caption, color = color, modifier = Modifier.padding(start = 4.dp).weight(1f))
            if (!s.isLocal) {
                IconButton({ a.vm?.syncNow(s, label) }, enabled = s.status != CalendarStatus.SYNCING && s.enabled) {
                    Icon(KinIcons.Refresh, stringResource(R.string.cal_sync_now) + ": " + label, tint = c.inkSoft)
                }
            }
            IconButton({ a.vm?.openEdit(s) }) { Icon(KinIcons.Edit, stringResource(R.string.cal_edit, label), tint = c.inkSoft) }
            IconButton({ onRemove(s) }) { Icon(KinIcons.Trash, stringResource(R.string.cal_remove) + ": " + label, tint = c.danger) }
        }
        if ((s.status == CalendarStatus.FAILED || s.status == CalendarStatus.AUTH_FAILED) && (s.lastError != null || s.status == CalendarStatus.AUTH_FAILED)) {
            Text(if (s.status == CalendarStatus.AUTH_FAILED) stringResource(R.string.cal_auth_help) else s.lastError.orEmpty(), style = Kin.type.caption, color = c.danger)
        }
    }
}

@Composable
private fun statusText(s: CalendarSource): String {
    if (s.status == CalendarStatus.OK || (s.status == CalendarStatus.DISABLED && s.lastSyncedAt != null)) {
        return s.lastSyncedAt?.let { stringResource(R.string.cal_last_synced, Format.relative(it)) } ?: stringResource(R.string.cal_never_synced)
    }
    return stringResource(when (s.status) {
        CalendarStatus.SYNCING -> R.string.cal_status_syncing
        CalendarStatus.FAILED -> R.string.cal_status_failed
        CalendarStatus.AUTH_FAILED -> R.string.cal_status_auth_failed
        CalendarStatus.DISABLED -> R.string.cal_status_disabled
        else -> R.string.cal_status_ok
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceDialog(f: SourceForm, vm: CalendarViewModel) {
    val c = Kin.colors
    Dialog(onDismissRequest = vm::closeForm, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (f.editing) R.string.cal_edit_title else R.string.cal_add_title), style = Kin.type.title, color = c.ink)
            if (!f.editing) {
                Text(stringResource(R.string.cal_type), style = Kin.type.label, color = c.ink)
                KindOption(KinIcons.Link, R.string.cal_type_ics, R.string.cal_type_ics_hint, f.kind == "ics") { vm.editForm { it.copy(kind = "ics") } }
                KindOption(KinIcons.Server, R.string.cal_type_caldav, R.string.cal_type_caldav_hint, f.kind == "caldav") { vm.editForm { it.copy(kind = "caldav") } }
            }
            if (!f.isLocal) {
                KinTextField(
                    f.url, { v -> vm.editForm { it.copy(url = v) } },
                    stringResource(if (f.kind == "caldav") R.string.cal_server_url else R.string.cal_url),
                    placeholder = when {
                        f.editing && f.source?.urlHint != null -> stringResource(R.string.cal_url_keep, f.source.urlHint!!)
                        f.kind == "caldav" -> stringResource(R.string.cal_server_url_placeholder)
                        else -> stringResource(R.string.cal_url_placeholder)
                    },
                    error = if (f.urlError) stringResource(R.string.cal_url_invalid) else null,
                    supporting = if (f.kind == "ics" && !f.editing) stringResource(R.string.cal_url_help) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                if (f.kind == "caldav") {
                    KinTextField(f.username, { v -> vm.editForm { it.copy(username = v) } }, stringResource(R.string.cal_username),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    KinTextField(f.password, { v -> vm.editForm { it.copy(password = v) } }, stringResource(R.string.cal_password),
                        placeholder = if (f.editing && f.source?.hasPassword == true) stringResource(R.string.cal_password_keep) else null,
                        supporting = stringResource(R.string.cal_password_help),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                }
                if (!f.editing) {
                    SecondaryButton(stringResource(if (f.testing) R.string.cal_testing else R.string.cal_test), vm::test, enabled = f.canTest, icon = KinIcons.Refresh)
                    f.result?.let { r ->
                        val msg = if (!r.ok) {
                            if (r.status == CalendarStatus.AUTH_FAILED) stringResource(R.string.cal_test_auth) else stringResource(R.string.cal_test_failed, r.error.orEmpty())
                        } else {
                            val events = r.eventCount?.let { pluralStringResource(R.plurals.cal_test_upcoming, it, it) }.orEmpty()
                            r.calendarName?.let { stringResource(R.string.cal_test_ok_named, it, events) } ?: stringResource(R.string.cal_test_ok, events)
                        }
                        Note(msg, tone = if (r.ok) NoteTone.Good else NoteTone.Danger, icon = if (r.ok) KinIcons.Check else KinIcons.Warning)
                    }
                }
            }
            if (f.multi) {
                Text(stringResource(R.string.cal_pick_calendars), style = Kin.type.label, color = c.ink)
                Text(stringResource(R.string.cal_pick_hint), style = Kin.type.caption, color = c.inkSoft)
                f.calendars.forEach { cal ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { vm.togglePick(cal.url) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cal.url in f.picked, null, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                        Box(Modifier.padding(horizontal = 8.dp).size(12.dp).clip(CircleShape).background(parseHex(cal.color) ?: c.inkFaint))
                        Text(cal.name, style = Kin.type.body, color = c.ink)
                    }
                }
                if (f.picked.isEmpty()) Text(stringResource(R.string.cal_pick_none), style = Kin.type.caption, color = c.danger)
            }
            if (!f.multi || f.picked.size == 1) {
                KinTextField(f.name, { v -> vm.editForm { it.copy(name = v.take(60)) } }, stringResource(R.string.cal_name),
                    placeholder = stringResource(if (f.isLocal) R.string.cal_received else R.string.cal_name_placeholder))
                Text(stringResource(R.string.cal_color), style = Kin.type.label, color = c.ink)
                ColorPicker(f.color) { col -> vm.editForm { it.copy(color = col) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(calColor(f.color).copy(alpha = 0.16f)).padding(10.dp)) {
                    Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(calColor(f.color)))
                    Text(f.name.trim().ifEmpty { stringResource(if (f.isLocal) R.string.cal_received else R.string.cal_name_placeholder) }, style = Kin.type.body, color = c.ink)
                }
            }
            if (f.editing) SwitchRow(stringResource(R.string.cal_show), f.enabled, { v -> vm.editForm { it.copy(enabled = v) } })
            f.error?.let { Note(stringResource(it), tone = NoteTone.Danger, icon = KinIcons.Warning) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                QuietButton(stringResource(R.string.common_cancel), vm::closeForm)
                PrimaryButton(stringResource(if (f.editing) R.string.common_save else R.string.cal_save), vm::save, enabled = f.canSave, busy = f.saving)
            }
        }
    }
}

@Composable
private fun KindOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, hint: Int, on: Boolean, onClick: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (on) c.accent else c.inkSoft)
        Column {
            Text(stringResource(title), style = Kin.type.bodyStrong, color = c.ink)
            Text(stringResource(hint), style = Kin.type.caption, color = c.inkSoft)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(value: String, onChange: (String) -> Unit) {
    val c = Kin.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalendarLogic.COLORS.forEach { key ->
            val label = stringResource(R.string.cal_color_name, key)
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(calColor(key))
                    .border(if (key == value) 3.dp else 0.dp, if (key == value) c.ink else Color.Transparent, CircleShape)
                    .clickable(role = Role.RadioButton) { onChange(key) }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { if (key == value) Icon(KinIcons.Check, null, Modifier.size(18.dp), tint = Color.White) }
        }
    }
}

private fun parseHex(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

// ------------------------------------------------------------------ sent events

@Composable
private fun SentSection(frame: Frame, state: CalendarState, a: CalendarActions, mayPut: Boolean, onSend: () -> Unit) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.cal_sent_title)) {
        if (mayPut) TextButton(onSend) { Icon(KinIcons.Send, null, tint = c.accent); Text(stringResource(R.string.cal_send_button), color = c.accent) }
    }
    Text(stringResource(R.string.cal_send_hint), style = Kin.type.caption, color = c.inkSoft)
    val list = state.invitations
    when {
        list == null -> Skeleton(Modifier.fillMaxWidth().height(60.dp))
        list.isEmpty() -> Note(stringResource(R.string.cal_sent_none))
        else -> Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
            list.forEachIndexed { i, inv ->
                if (i > 0) HorizontalDivider(color = c.line)
                val mine = inv.senderId == a.me
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(inv.title, style = Kin.type.bodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(invitationWhen(inv) + if (!mine) " · " + stringResource(R.string.cal_sent_by, inv.senderName) else "", style = Kin.type.caption, color = c.inkSoft)
                    }
                    InvitationBadge(inv.status)
                    if (inv.status == "pending" && (mine || frame.isManager)) QuietButton(stringResource(R.string.cal_withdraw), { a.vm?.withdraw(inv) })
                }
            }
        }
    }
}

@Composable
private fun InvitationBadge(status: String) {
    val c = Kin.colors
    val (label, bg, fg) = when (status) {
        "accepted" -> Triple(R.string.cal_inv_accepted, c.sageSoft, c.onSageSoft)
        "declined" -> Triple(R.string.cal_inv_declined, c.danger.copy(alpha = 0.14f), c.danger)
        "withdrawn" -> Triple(R.string.cal_inv_withdrawn, c.paperDeep, c.inkSoft)
        else -> Triple(R.string.cal_inv_pending, c.accentSoft, c.onAccentSoft)
    }
    Pill(stringResource(label), bg, fg)
}

@Composable
private fun invitationWhen(inv: EventInvitation): String {
    val locale = Locale.getDefault()
    val day = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    if (inv.allDay && inv.startDate != null) {
        val s = CalendarLogic.parseDate(inv.startDate) ?: return ""
        val e = CalendarLogic.parseDate(inv.endDate)
        return if (e != null && e != s) "${s.format(day)} – ${e.format(day)}" else "${s.format(day)} · ${stringResource(R.string.cal_all_day)}"
    }
    val start = CalendarLogic.parseInstant(inv.startAt)?.atZone(ZoneId.systemDefault()) ?: return ""
    return "${start.format(day)} · ${start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendEventDialog(frameName: String, senderName: String, state: CalendarState, vm: CalendarViewModel, onClose: () -> Unit) {
    val c = Kin.colors
    var form by remember { mutableStateOf(CalendarLogic.defaultForm()) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var placement by remember { mutableStateOf("top") }
    var tried by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    val error = CalendarLogic.build(form).first
    val close = { vm.clearSendError(); onClose() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.cal_send_dialog_title, frameName), style = Kin.type.title, color = c.ink)
            KinTextField(form.title, { form = form.copy(title = it.take(200)) }, stringResource(R.string.cal_ev_title),
                placeholder = stringResource(R.string.cal_ev_title_placeholder),
                error = if (tried && error == CalendarLogic.FormError.Title) stringResource(R.string.cal_ev_err_title) else null)
            SwitchRow(stringResource(R.string.cal_ev_all_day), form.allDay, { form = form.copy(allDay = it) })
            if (form.allDay) {
                DateField(stringResource(R.string.cal_ev_start_date), form.startDate) { form = CalendarLogic.moveStartDate(form, it) }
                DateField(stringResource(R.string.cal_ev_end_date), form.endDate, min = form.startDate) { form = form.copy(endDate = it) }
            } else {
                DateTimeField(stringResource(R.string.cal_ev_start), form.start) { form = CalendarLogic.moveStart(form, it) }
                DateTimeField(stringResource(R.string.cal_ev_end), form.end) { form = form.copy(end = it) }
            }
            if (tried && error != null && error != CalendarLogic.FormError.Title) {
                Text(stringResource(when (error) {
                    CalendarLogic.FormError.Start -> R.string.cal_ev_err_start
                    CalendarLogic.FormError.End -> R.string.cal_ev_err_end
                    else -> R.string.cal_ev_err_order
                }), style = Kin.type.caption, color = c.danger)
            }
            if (!form.allDay) Text(stringResource(R.string.cal_ev_times_local), style = Kin.type.caption, color = c.inkSoft)
            val optional = " (" + stringResource(R.string.common_optional) + ")"
            KinTextField(form.location, { form = form.copy(location = it.take(200)) }, stringResource(R.string.cal_ev_location) + optional)
            KinTextField(form.notes, { form = form.copy(notes = it.take(2000)) }, stringResource(R.string.cal_ev_notes) + optional, singleLine = false, minLines = 2)
            Text(stringResource(R.string.cal_ev_photo) + optional, style = Kin.type.label, color = c.ink)
            if (photo != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(stringResource(R.string.cal_ev_place_top), placement == "top", { placement = "top" })
                    ToggleChip(stringResource(R.string.cal_ev_place_background), placement == "background", { placement = "background" })
                    QuietButton(stringResource(R.string.cal_ev_remove_photo), { photo = null })
                }
            } else {
                SecondaryButton(stringResource(R.string.cal_ev_add_photo), { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = KinIcons.Image)
            }
            Text(stringResource(R.string.cal_ev_preview), style = Kin.type.label, color = c.ink)
            EventCardPreview(form, photo, placement, senderName)
            state.sendError?.let { Note(stringResource(it), tone = NoteTone.Danger, icon = KinIcons.Warning) }
            if (state.sending && photo != null && state.sendProgress < 1f) {
                LinearProgressIndicator({ state.sendProgress }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                QuietButton(stringResource(R.string.common_cancel), close)
                PrimaryButton(stringResource(R.string.cal_ev_send), {
                    tried = true
                    if (error == null) vm.send(form, photo, placement, frameName, onClose)
                }, busy = state.sending, icon = KinIcons.Send)
            }
        }
    }
}

/** The invitation card as the frame shows it. */
@Composable
private fun EventCardPreview(form: CalendarLogic.EventForm, photo: Uri?, placement: String, senderName: String) {
    val c = Kin.colors
    val locale = Locale.getDefault()
    val day = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val whenText = if (form.allDay) {
        val s = form.startDate
        val e = form.endDate?.takeIf { it != s }
        when { s == null -> ""; e != null -> "${s.format(day)} – ${e.format(day)}"; else -> "${s.format(day)} · ${stringResource(R.string.cal_all_day)}" }
    } else {
        form.start?.let { s -> "${s.format(day)} · ${s.format(time)}" + (form.end?.let { " – ${it.format(time)}" } ?: "") }.orEmpty()
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paperDeep)) {
        if (photo != null && placement == "background") Thumb(photo, null, Modifier.matchParentSize(), corner = 16.dp)
        Column {
            if (photo != null && placement == "top") Thumb(photo, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 0.dp)
            Column(
                Modifier.fillMaxWidth().then(if (photo != null && placement == "background") Modifier.background(Color.Black.copy(alpha = 0.45f)) else Modifier).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val onPhoto = photo != null && placement == "background"
                val ink = if (onPhoto) Color.White else c.ink
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(KinIcons.Heart, null, Modifier.size(12.dp), tint = if (onPhoto) Color.White else c.accent)
                    Text(stringResource(R.string.cal_sent_by, senderName), style = Kin.type.caption, color = if (onPhoto) Color.White else c.inkSoft)
                }
                Text(form.title.trim().ifEmpty { stringResource(R.string.cal_ev_title_placeholder) }, style = Kin.type.title, color = ink)
                if (whenText.isNotEmpty()) Text(whenText, style = Kin.type.body, color = ink)
                form.location.trim().takeIf { it.isNotEmpty() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(KinIcons.MapPin, null, Modifier.size(13.dp), tint = ink)
                        Text(it, style = Kin.type.body, color = ink)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate?, min: LocalDate? = null, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerChip(label, value?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())).orEmpty(), KinIcons.Calendar) { open = true }
    if (open) {
        val ds = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    min == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(min)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    ds.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(ds) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, value: LocalDateTime?, onChange: (LocalDateTime) -> Unit) {
    var step by remember { mutableStateOf(0) } // 1: date, 2: time
    var date by remember { mutableStateOf(value?.toLocalDate()) }
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
    PickerChip(label, value?.format(fmt).orEmpty(), KinIcons.Clock) { date = value?.toLocalDate(); step = 1 }
    if (step == 1) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = value?.toLocalDate()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton({
                    date = ds.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: date
                    step = 2
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = { TextButton({ step = 0 }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(ds) }
    } else if (step == 2) {
        val t = value?.toLocalTime() ?: LocalTime.of(12, 0)
        val tp = rememberTimePickerState(t.hour, t.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text(label) },
            text = { TimeInput(tp) },
            confirmButton = {
                TextButton({
                    val d = date ?: LocalDate.now()
                    onChange(d.atTime(tp.hour, tp.minute)); step = 0
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = { TextButton({ step = 0 }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PickerChip(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.paperDeep).clickable(role = Role.Button, onClick = onClick).padding(12.dp)) {
        Text(label, style = Kin.type.caption, color = c.inkSoft)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = c.inkSoft)
            Text(value, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ calendar settings

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSettingsSection(state: SettingsFormState, vm: FrameSettingsViewModel) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.cal_settings_title))
    val s = state.settings
    val cal = s.calendar
    Surface(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cal_set_view), style = Kin.type.label, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("four_day" to R.string.cal_set_view_four, "week" to R.string.cal_set_view_week, "month" to R.string.cal_set_view_month).forEach { (v, l) ->
                ToggleChip(stringResource(l), cal.view == v, { vm.set("calendar.view", v) })
            }
        }
        SwitchRow(stringResource(R.string.cal_set_start_today), cal.startOnToday, { vm.set("calendar.start_on_today", it) }, hint = stringResource(R.string.cal_set_start_today_hint))
        SwitchRow(stringResource(R.string.cal_set_fade_past), cal.fadePast, { vm.set("calendar.fade_past", it) })
        SwitchRow(stringResource(R.string.cal_set_week_numbers), cal.weekNumbers, { vm.set("calendar.week_numbers", it) })
        SwitchRow(stringResource(R.string.cal_set_sidebar), s.widgets.calendarSidebar, { vm.set("widgets.calendar_sidebar", it) }, hint = stringResource(R.string.cal_set_sidebar_hint))
        SwitchRow(stringResource(R.string.cal_set_countdowns), s.widgets.countdowns, { vm.set("widgets.countdowns", it) }, hint = stringResource(R.string.cal_set_countdowns_hint))
        Text(stringResource(R.string.cal_set_window), style = Kin.type.label, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(stringResource(R.string.cal_set_past), cal.pastDays, 1..365, Modifier.weight(1f)) { vm.set("calendar.past_days", it) }
            NumberField(stringResource(R.string.cal_set_future), cal.futureDays, 7..365, Modifier.weight(1f)) { vm.set("calendar.future_days", it) }
        }
    }
    SectionTitle(stringResource(R.string.cal_timed_title)) {
        TextButton({ vm.setTimedViews(cal.timedViews + CalendarScheduleDto(id = UUID.randomUUID().toString())) }) {
            Icon(KinIcons.Plus, null, tint = c.accent); Text(stringResource(R.string.cal_timed_add), color = c.accent)
        }
    }
    Text(stringResource(R.string.cal_timed_hint), style = Kin.type.caption, color = c.inkSoft)
    if (cal.timedViews.isEmpty()) Note(stringResource(R.string.cal_timed_none))
    cal.timedViews.forEachIndexed { i, sch ->
        TimedViewCard(i, sch, { n -> vm.setTimedViews(cal.timedViews.mapIndexed { j, o -> if (j == i) n else o }) }) {
            vm.setTimedViews(cal.timedViews.filterIndexed { j, _ -> j != i })
        }
    }
    if (state.dirty) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fs_unsaved), style = Kin.type.label, color = c.inkSoft, modifier = Modifier.weight(1f))
            QuietButton(stringResource(R.string.fs_discard), { vm.discard() })
            PrimaryButton(stringResource(R.string.common_save), { vm.save() }, busy = state.saving)
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    KinTextField(text, { t ->
        text = t.filter { it.isDigit() }.take(3)
        text.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
    }, label, modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimedViewCard(i: Int, sch: CalendarScheduleDto, onChange: (CalendarScheduleDto) -> Unit, onRemove: () -> Unit) {
    val c = Kin.colors
    var picking by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxWidth().alpha(if (sch.enabled) 1f else 0.7f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cal_timed_n, (i + 1).toString()), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            Switch(sch.enabled, { onChange(sch.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            IconButton(onRemove) { Icon(KinIcons.Trash, stringResource(R.string.cal_timed_remove), tint = c.danger) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PickerChip(stringResource(R.string.cal_timed_from), sch.from, KinIcons.Clock) { picking = "from" } }
            Box(Modifier.weight(1f)) { PickerChip(stringResource(R.string.cal_timed_to), sch.to, KinIcons.Clock) { picking = "to" } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEach { (d, l) ->
                ToggleChip(stringResource(l), d in sch.days, {
                    onChange(sch.copy(days = if (d in sch.days) sch.days - d else DAYS.map { it.first }.filter { it == d || it in sch.days }))
                })
            }
        }
    }
    picking?.let { which ->
        val cur = if (which == "from") sch.from else sch.to
        val (h, m) = cur.split(":").map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
        val tp = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(if (which == "from") R.string.cal_timed_from else R.string.cal_timed_to)) },
            text = { TimeInput(tp) },
            confirmButton = {
                TextButton({
                    val v = "%02d:%02d".format(tp.hour, tp.minute)
                    onChange(if (which == "from") sch.copy(from = v) else sch.copy(to = v)); picking = null
                }) { Text(stringResource(R.string.common_done)) }
            },
            dismissButton = { TextButton({ picking = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private val DAYS = listOf(
    "mon" to R.string.day_mon, "tue" to R.string.day_tue, "wed" to R.string.day_wed, "thu" to R.string.day_thu,
    "fri" to R.string.day_fri, "sat" to R.string.day_sat, "sun" to R.string.day_sun,
)

private fun FrameSettingsViewModel.setTimedViews(list: List<CalendarScheduleDto>) =
    set("calendar.timed_views", KinJson.encodeToJsonElement(ListSerializer(CalendarScheduleDto.serializer()), list))

// ------------------------------------------------------------------ preview

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarPreview(view: CalendarView, settings: SettingsFormState?, onOpen: (CalendarEvent) -> Unit) {
    val c = Kin.colors
    val zone = CalendarLogic.zone(view.timezone)
    val now = remember { Instant.now() }
    val today = CalendarLogic.today(zone, now)
    var month by rememberSaveable { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(YearMonth.from(today)) }
    val s = settings?.settings
    val unit = s?.locale?.temperatureUnit ?: "c"
    val fadePast = s?.calendar?.fadePast ?: true
    val colors = view.sources.associate { it.id to it.color }
    val wx = CalendarLogic.weatherByDate(view.weather?.daily)
    val from = CalendarLogic.parseDate(view.window?.from) ?: today.minusDays(30)
    val to = CalendarLogic.parseDate(view.window?.to) ?: today.plusDays(30)
    Surface(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            ToggleChip(stringResource(R.string.cal_view_agenda), !month, { month = false }, leading = { Icon(KinIcons.History, null, Modifier.size(16.dp)) })
            ToggleChip(stringResource(R.string.cal_view_month), month, { month = true }, leading = { Icon(KinIcons.Calendar, null, Modifier.size(16.dp)) })
        }
        if (view.countdowns.isNotEmpty()) {
            Text(stringResource(R.string.cal_countdowns), style = Kin.type.label, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                view.countdowns.forEach { cd ->
                    val n = CalendarLogic.daysUntil(cd, zone, now)
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(calColor(cd.color).copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (n <= 0) stringResource(R.string.cal_is_today) else pluralStringResource(R.plurals.cal_in_days, n.toInt(), n.toInt()), style = Kin.type.bodyStrong, color = c.ink)
                        Text(cd.title, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!month) {
            CalendarLogic.agenda(view.events, zone, today, from, to).forEach { day ->
                AgendaDayRow(day, today, zone, colors, wx[day.day], unit, fadePast, now, onOpen)
            }
        } else {
            val first = CalendarLogic.firstDay(s?.locale?.firstDayOfWeek)
            val canPrev = cursor.atDay(1).isAfter(from)
            val canNext = !cursor.plusMonths(1).atDay(1).isAfter(to)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ cursor = cursor.minusMonths(1) }, enabled = canPrev) { Icon(KinIcons.ChevronLeft, stringResource(R.string.cal_prev), tint = c.ink) }
                Text(cursor.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase() } + " " + cursor.year,
                    style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                IconButton({ cursor = cursor.plusMonths(1) }, enabled = canNext) { Icon(KinIcons.ChevronRight, stringResource(R.string.cal_next), tint = c.ink) }
                TextButton({ cursor = YearMonth.from(today) }) { Text(stringResource(R.string.cal_today), color = c.accent) }
            }
            MonthGrid(cursor, first, view, zone, today, colors, wx, unit, fadePast, s?.calendar?.weekNumbers ?: false, onOpen)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(KinIcons.Clock, null, Modifier.size(13.dp), tint = c.inkFaint)
            Text(stringResource(R.string.cal_times_in, zone.id), style = Kin.type.caption, color = c.inkFaint)
        }
        if (view.weather != null) Text(stringResource(R.string.cal_weather_credit), style = Kin.type.caption, color = c.inkFaint)
    }
}

@Composable
private fun AgendaDayRow(
    day: CalendarLogic.AgendaDay, today: LocalDate, zone: ZoneId, colors: Map<String, String>, w: app.kinframe.core.api.WeatherDay?, unit: String,
    fadePast: Boolean, now: Instant, onOpen: (CalendarEvent) -> Unit,
) {
    val c = Kin.colors
    val locale = Locale.getDefault()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = c.line)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (day.day == today) stringResource(R.string.cal_today) else day.day.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase() },
                    style = Kin.type.bodyStrong, color = if (day.day == today) c.accent else c.ink)
                Text(day.day.format(DateTimeFormatter.ofPattern("d MMMM", locale)), style = Kin.type.caption, color = c.inkSoft)
            }
            w?.let { WeatherBadge(it, unit) }
        }
        if (day.events.isEmpty()) Text(stringResource(R.string.cal_preview_empty), style = Kin.type.caption, color = c.inkSoft)
        day.events.forEach { ev ->
            val past = fadePast && CalendarLogic.isPast(ev, zone, now)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(calColor(colors[ev.sourceId]).copy(alpha = if (ev.allDay) 0.22f else 0.1f))
                    .clickable(role = Role.Button) { onOpen(ev) }.alpha(if (past) 0.55f else 1f).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(4.dp).heightIn(min = 28.dp).clip(RoundedCornerShape(2.dp)).background(calColor(colors[ev.sourceId])))
                Text(timeLabel(ev, day.day, zone, agendaRow = true), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.width(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(ev.title, style = Kin.type.bodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ev.location?.let { Text(it, style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (ev.senderName != null) Icon(KinIcons.Heart, stringResource(R.string.cal_sent_by, ev.senderName!!), Modifier.size(14.dp), tint = c.accent)
                if (ev.countdown) Icon(KinIcons.Star, stringResource(R.string.cal_countdowns), Modifier.size(14.dp), tint = c.warn)
            }
        }
    }
}

@Composable
private fun WeatherBadge(w: app.kinframe.core.api.WeatherDay, unit: String) {
    val c = Kin.colors
    val kind = stringResource(weatherName(CalendarLogic.weatherKind(w.code)))
    val label = stringResource(R.string.cal_weather_day, kind, CalendarLogic.formatTemp(w.minC, unit), CalendarLogic.formatTemp(w.maxC, unit))
    Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(weatherIcon(CalendarLogic.weatherKind(w.code)), null, Modifier.size(22.dp), tint = c.inkSoft)
        Text(CalendarLogic.formatTemp(w.maxC, unit), style = Kin.type.bodyStrong, color = c.ink)
        Text(CalendarLogic.formatTemp(w.minC, unit), style = Kin.type.caption, color = c.inkSoft)
        w.precipProb?.takeIf { it >= 10 }?.let { Text(stringResource(R.string.cal_rain, it.toString()), style = Kin.type.caption, color = c.accent) }
    }
}

private fun weatherName(k: CalendarLogic.WeatherKind): Int = when (k) {
    CalendarLogic.WeatherKind.Clear -> R.string.cal_wx_clear
    CalendarLogic.WeatherKind.Partly -> R.string.cal_wx_partly
    CalendarLogic.WeatherKind.Cloudy -> R.string.cal_wx_cloudy
    CalendarLogic.WeatherKind.Fog -> R.string.cal_wx_fog
    CalendarLogic.WeatherKind.Drizzle -> R.string.cal_wx_drizzle
    CalendarLogic.WeatherKind.Rain -> R.string.cal_wx_rain
    CalendarLogic.WeatherKind.Showers -> R.string.cal_wx_showers
    CalendarLogic.WeatherKind.Snow -> R.string.cal_wx_snow
    CalendarLogic.WeatherKind.Storm -> R.string.cal_wx_storm
}

private fun weatherIcon(k: CalendarLogic.WeatherKind) = when (k) {
    CalendarLogic.WeatherKind.Clear -> KinIcons.Sun
    CalendarLogic.WeatherKind.Drizzle, CalendarLogic.WeatherKind.Rain, CalendarLogic.WeatherKind.Showers, CalendarLogic.WeatherKind.Storm -> KinIcons.Rain
    else -> KinIcons.Cloud
}

@Composable
private fun timeLabel(ev: CalendarEvent, day: LocalDate, zone: ZoneId, agendaRow: Boolean = false): String {
    val (s, e) = CalendarLogic.span(ev, zone)
    val locale = Locale.getDefault()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    fun t(iso: String?) = CalendarLogic.parseInstant(iso)?.atZone(zone)?.format(time).orEmpty()
    if (agendaRow && s != e) {
        val f = DateTimeFormatter.ofPattern("d MMM", locale)
        return "${s.format(f)} – ${e.format(f)}"
    }
    if (ev.allDay) return stringResource(R.string.cal_all_day)
    if (s != e && day != s) return if (day == e && ev.endAt != null) "→ ${t(ev.endAt)}" else stringResource(R.string.cal_all_day)
    return if (ev.endAt != null && s == e) "${t(ev.startAt)} – ${t(ev.endAt)}" else t(ev.startAt)
}

@Composable
private fun MonthGrid(
    month: YearMonth, first: java.time.DayOfWeek, view: CalendarView, zone: ZoneId, today: LocalDate, colors: Map<String, String>,
    wx: Map<LocalDate, app.kinframe.core.api.WeatherDay>, unit: String, fadePast: Boolean, weekNumbers: Boolean, onOpen: (CalendarEvent) -> Unit,
) {
    val c = Kin.colors
    val locale = Locale.getDefault()
    val weeks = CalendarLogic.monthGrid(month, first)
    val byDay = CalendarLogic.eventsByDay(view.events, zone, weeks.first().first(), weeks.last().last())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            CalendarLogic.weekdayOrder(first).forEach { d ->
                Text(d.getDisplayName(TextStyle.SHORT, locale), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
            }
        }
        weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEachIndexed { i, d ->
                    val events = byDay[d].orEmpty()
                    val outside = d.month != month.month
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (d == today) c.accentSoft else c.paperDeep.copy(alpha = if (outside) 0.4f else 1f))
                            .alpha(if (fadePast && d.isBefore(today)) 0.6f else 1f).padding(3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.dayOfMonth.toString(), style = Kin.type.caption, color = if (outside) c.inkFaint else c.ink, modifier = Modifier.weight(1f))
                            if (weekNumbers && i == 0) Text(stringResource(R.string.cal_week_short, CalendarLogic.isoWeek(d).toString()), style = Kin.type.caption, color = c.inkFaint)
                        }
                        wx[d]?.let { Text(CalendarLogic.formatTemp(it.maxC, unit), style = Kin.type.caption, color = c.inkSoft) }
                        events.take(3).forEach { ev ->
                            Text(
                                ev.title, style = Kin.type.caption, color = c.ink, maxLines = 1, overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(calColor(colors[ev.sourceId]).copy(alpha = 0.3f))
                                    .clickable(role = Role.Button) { onOpen(ev) }.padding(horizontal = 2.dp),
                            )
                        }
                        if (events.size > 3) Text(stringResource(R.string.cal_more, events.size - 3), style = Kin.type.caption, color = c.inkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDetailDialog(ev: CalendarEvent, view: CalendarView?, manage: Boolean, busy: Boolean, onClose: () -> Unit, onToggle: () -> Unit) {
    val c = Kin.colors
    val zone = CalendarLogic.zone(view?.timezone)
    val source = view?.sources?.firstOrNull { it.id == ev.sourceId }
    val (s, e) = CalendarLogic.span(ev, zone)
    val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ev.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(calColor(source?.color)))
                    Text(if (source?.isLocal == true && source.name.isEmpty()) stringResource(R.string.cal_received) else source?.name.orEmpty(), style = Kin.type.body, color = c.ink)
                }
                DetailRow(KinIcons.Clock, if (ev.allDay) s.format(dayFmt) + (if (s != e) " – " + e.format(dayFmt) else "") else s.format(dayFmt) + ", " + timeLabel(ev, s, zone))
                if (ev.recurring) DetailRow(KinIcons.Refresh, stringResource(R.string.cal_recurring))
                ev.location?.let { DetailRow(KinIcons.MapPin, it) }
                ev.senderName?.let { DetailRow(KinIcons.Heart, stringResource(R.string.cal_sent_by, it)) }
                ev.notes?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
                if (manage) SwitchRow(stringResource(R.string.cal_countdown_toggle), ev.countdown, { onToggle() }, enabled = !busy)
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.common_done)) } },
    )
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = Kin.colors.inkSoft)
        Text(text, style = Kin.type.body, color = Kin.colors.ink)
    }
}
