package app.kinframe.family.ui.feeds

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.BirthdayDates
import app.kinframe.core.api.BirthdayPerson
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedBirthdayGreeting
import app.kinframe.core.api.FeedMember
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.currentLocale
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What the Birthdays tab needs besides its state. */
class BirthdayActions(
    val vm: FeedBirthdaysViewModel,
    val me: String?,
    val myName: String?,
    val members: List<FeedMember>,
    /** The caller's own `birthday_notifications` for this feed. */
    val setNotifications: (Boolean) -> Unit,
    val today: LocalDate = LocalDate.now(),
)

private fun dayMonth(day: Int, month: Int, locale: Locale): String {
    val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM") }.getOrNull() ?: "d MMMM"
    // 2000 is a leap year, so 29 February formats as itself.
    return DateTimeFormatter.ofPattern(pattern.replace("LLLL", "MMMM"), locale).format(LocalDate.of(2000, month, day))
}

private fun time(at: String, locale: Locale): String = runCatching {
    java.time.Instant.parse(at).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
}.getOrDefault("")

@Composable
private fun whenLabel(p: BirthdayPerson, today: LocalDate): String {
    val days = BirthdayRules.daysUntil(p, today)
    return when (days) {
        0 -> stringResource(R.string.birthday_today)
        1 -> stringResource(R.string.birthday_tomorrow)
        else -> pluralStringResource(R.plurals.birthday_in_days, days, days)
    }
}

/** "🎉 Opa's frame 10:14 · Kitchen 11:02" */
@Composable
private fun greetingsLine(list: List<FeedBirthdayGreeting>): String {
    val locale = currentLocale()
    return "🎉 " + list.sortedBy { it.createdAt }.joinToString(" · ") { "${it.frameName} ${time(it.createdAt, locale)}".trim() }
}

/**
 * A feed's Birthdays (docs/spec/birthdays.md §3): the list by next birthday, suggestions to approve (owner/admins) or
 * waiting (posters), declined suggestions for 30 days, today's greetings, and the caller's own notification switch.
 */
@Composable
fun BirthdaysTab(feed: Feed, state: BirthdaysState, a: BirthdayActions) {
    val c = Kin.colors
    val locale = currentLocale()
    var editing by remember { mutableStateOf<BirthdayDraft?>(null) }
    var editingOriginal by remember { mutableStateOf<BirthdayPerson?>(null) }
    val all = (state.people as? Load.Ready)?.value.orEmpty()
    val mine = all.firstOrNull { a.me != null && it.userId == a.me && !it.isRejected }
    fun open(p: BirthdayPerson?) {
        editingOriginal = p
        editing = p?.let { BirthdayDraft.of(it) } ?: BirthdayDraft()
    }
    when (val people = state.people) {
        Load.Loading -> SkeletonList(3, 72.dp)
        is Load.Error -> ErrorPanel(people.message, { a.vm.load() })
        is Load.Ready -> PageColumn {
            val direct = BirthdayRules.addsDirectly(feed)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(stringResource(if (direct) R.string.birthday_add else R.string.birthday_suggest), { open(null) }, icon = KinIcons.Plus)
                if (mine == null && a.me != null) {
                    SecondaryButton(stringResource(R.string.birthday_add_mine), {
                        editingOriginal = null
                        editing = BirthdayDraft(name = a.myName.orEmpty().take(BirthdayDates.MAX_NAME), userId = a.me)
                    })
                }
            }
            if (!direct) Note(stringResource(R.string.birthday_suggest_hint, feed.ownerName ?: ""))

            if (state.pending.isNotEmpty()) {
                SectionTitle(stringResource(if (feed.isAdmin) R.string.birthday_suggestions else R.string.birthday_your_suggestions))
                state.pending.forEach { p -> PendingCard(feed, p, state.busy, a, onEdit = { open(p) }) }
            }

            SectionTitle(stringResource(R.string.birthday_list_title))
            if (state.approved.isEmpty()) {
                Text(stringResource(R.string.birthday_empty), style = Kin.type.body, color = c.inkSoft)
            } else {
                Surface(Modifier.fillMaxWidth(), padding = 8.dp) {
                    state.approved.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        PersonRow(p, a.today, locale, state.greetingsFor(p), onClick = if (p.canEdit) ({ open(p) }) else null)
                    }
                }
            }

            if (state.rejected.isNotEmpty()) {
                SectionTitle(stringResource(R.string.birthday_declined))
                state.rejected.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(p.displayName, p.photoUrl, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = Kin.type.bodyStrong, color = c.inkSoft)
                            Text(stringResource(R.string.birthday_declined_by, p.decidedByName ?: ""), style = Kin.type.caption, color = c.inkFaint)
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.birthday_notifications_title))
            SwitchRow(
                stringResource(R.string.birthday_notifications), feed.birthdayNotifications, a.setNotifications,
                hint = stringResource(R.string.birthday_notifications_hint),
            )
        }
    }
    editing?.let { d ->
        BirthdayEditor(
            feed, d, editingOriginal, state, a,
            onDismiss = { editing = null; editingOriginal = null; a.vm.clearDuplicate() },
        )
    }
}

@Composable
private fun PersonRow(p: BirthdayPerson, today: LocalDate, locale: Locale, greetings: List<FeedBirthdayGreeting>, onClick: (() -> Unit)?) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(p.displayName, p.photoUrl, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val turns = BirthdayRules.turns(p, today)
            val line = listOfNotNull(
                dayMonth(p.day, p.month, locale),
                whenLabel(p, today),
                turns?.let { pluralStringResource(R.plurals.birthday_turns, it, it) },
            ).joinToString(" · ")
            Text(line, style = Kin.type.caption, color = if (BirthdayRules.daysUntil(p, today) == 0) c.accent else c.inkSoft)
            // The year is shown only where the viewer may see it (§9); say when the frames don't show the age.
            if (BirthdayRules.yearIsPrivate(p)) Text(stringResource(R.string.birthday_year_private, p.birthYear!!), style = Kin.type.caption, color = c.inkFaint)
            if (greetings.isNotEmpty()) Text(greetingsLine(greetings), style = Kin.type.caption, color = c.onSageSoft)
        }
        if (onClick != null) Icon(KinIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.inkFaint)
    }
}

@Composable
private fun PendingCard(feed: Feed, p: BirthdayPerson, busy: Boolean, a: BirthdayActions, onEdit: () -> Unit) {
    val c = Kin.colors
    val locale = currentLocale()
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(p.displayName, p.photoUrl, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = Kin.type.bodyStrong, color = c.ink)
                Text(
                    listOfNotNull(dayMonth(p.day, p.month, locale), p.birthYear?.toString()).joinToString(" "),
                    style = Kin.type.caption, color = c.inkSoft,
                )
                Text(
                    if (p.canDecide) stringResource(R.string.birthday_suggested_by, p.createdByName ?: stringResource(R.string.birthday_former_member))
                    else stringResource(R.string.birthday_waiting, feed.ownerName ?: ""),
                    style = Kin.type.caption, color = c.inkFaint,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.canDecide) {
                PrimaryButton(stringResource(R.string.birthday_approve), { a.vm.approve(p) }, enabled = !busy)
                SecondaryButton(stringResource(R.string.birthday_reject), { a.vm.reject(p) }, enabled = !busy)
            }
            if (p.canEdit) QuietButton(stringResource(R.string.birthday_edit), onEdit, enabled = !busy)
        }
    }
}

/** A day or month dropdown. */
@Composable
private fun <T> Picker(label: String, value: T?, options: List<T>, text: @Composable (T) -> String, enabled: Boolean, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        KinTextField(
            value?.let { text(it) } ?: "", {}, label, enabled = enabled,
            trailing = { Icon(KinIcons.ChevronRight, null, Modifier.size(20.dp).rotate(90f)) },
        )
        // The field itself only shows the value; a transparent layer opens the menu.
        Box(Modifier.matchParentSize().clickable(enabled = enabled, role = Role.DropdownList) { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/**
 * Add or edit a person: name, day and month, optional year, "Show age" (needs the year), a link to a feed member, a
 * square photo (picked and cropped on the phone, like a profile photo) and Remove. The linked member editing their
 * own entry may change only the year, "Show age", the photo and the link (§4.1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BirthdayEditor(feed: Feed, initial: BirthdayDraft, original: BirthdayPerson?, state: BirthdaysState, a: BirthdayActions, onDismiss: () -> Unit) {
    val c = Kin.colors
    val locale = currentLocale()
    var d by remember(initial) { mutableStateOf(initial) }
    var yearText by remember(initial) { mutableStateOf(initial.year?.toString().orEmpty()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val limited = original != null && BirthdayRules.ownEntryOnly(original, feed, a.me)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> if (uri != null) d = d.copy(photo = uri, removePhoto = false) }
    val problem = d.problem(a.today)
    val taken = (state.people as? Load.Ready)?.value.orEmpty().filter { !it.isRejected && it.id != d.id }.mapNotNull { it.userId }.toSet()
    val linkable = a.members.filter { it.userId !in taken }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(when {
                d.id != null -> R.string.birthday_edit_title
                BirthdayRules.addsDirectly(feed) -> R.string.birthday_add_title
                else -> R.string.birthday_suggest_title
            }))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KinTextField(
                    d.name, { d = d.copy(name = it.take(BirthdayDates.MAX_NAME)) }, stringResource(R.string.birthday_name), enabled = !limited,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker(stringResource(R.string.birthday_day), d.day, (1..31).toList(), { it.toString() }, enabled = !limited, modifier = Modifier.weight(1f)) { d = d.copy(day = it) }
                    Picker(
                        stringResource(R.string.birthday_month), d.month, (1..12).toList(),
                        { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { ch -> ch.titlecase(locale) } },
                        enabled = !limited, modifier = Modifier.weight(1.6f),
                    ) { d = d.copy(month = it) }
                }
                KinTextField(
                    yearText,
                    { t ->
                        yearText = t.filter { it.isDigit() }.take(4)
                        d = d.copy(year = yearText.toIntOrNull()?.takeIf { yearText.length == 4 }, showAge = d.showAge && yearText.length == 4)
                    },
                    stringResource(R.string.birthday_year), supporting = stringResource(R.string.birthday_year_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                SwitchRow(
                    stringResource(R.string.birthday_show_age), d.showAge && d.year != null, { d = d.copy(showAge = it) },
                    hint = stringResource(if (d.year == null) R.string.birthday_show_age_needs_year else R.string.birthday_show_age_hint), enabled = d.year != null,
                )
                // Link to a feed member (the member's avatar is the fallback photo).
                if (!limited || d.userId != null) {
                    val linked = a.members.firstOrNull { it.userId == d.userId }
                    Text(stringResource(R.string.birthday_link), style = Kin.type.label, color = c.inkSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        app.kinframe.family.ui.ToggleChip(stringResource(R.string.birthday_link_none), d.userId == null, { d = d.copy(userId = null) })
                        (if (limited) listOfNotNull(linked) else (listOfNotNull(linked) + linkable).distinctBy { it.userId }).forEach { m ->
                            app.kinframe.family.ui.ToggleChip(m.displayName, d.userId == m.userId, {
                                if (limited) return@ToggleChip
                                d = d.copy(userId = m.userId, name = d.name.ifBlank { m.displayName.take(BirthdayDates.MAX_NAME) })
                            })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape)) {
                        when {
                            d.photo != null -> app.kinframe.family.ui.Thumb(d.photo, null, Modifier.size(56.dp), corner = 28.dp)
                            d.removePhoto -> Avatar(d.name, null, 56.dp)
                            else -> Avatar(d.name.ifBlank { "?" }, original?.photoUrl, 56.dp)
                        }
                    }
                    Column {
                        SecondaryButton(stringResource(R.string.birthday_photo_choose), { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = KinIcons.Image)
                        if (d.photo != null || (original?.hasOwnPhoto == true && !d.removePhoto)) {
                            QuietButton(stringResource(R.string.birthday_photo_remove), { d = d.copy(photo = null, removePhoto = true) })
                        }
                    }
                }
                state.duplicateOf?.let { Note(stringResource(R.string.birthday_duplicate_note, it.displayName), tone = app.kinframe.family.ui.NoteTone.Warn) }
                problem?.takeIf { d.name.isNotBlank() && d.day != null && d.month != null }?.let { Text(stringResource(it), style = Kin.type.caption, color = c.danger) }
                if (original != null && BirthdayRules.canRemove(original, feed, a.me)) {
                    QuietButton(stringResource(if (original.isPending && !feed.isAdmin) R.string.birthday_withdraw else R.string.birthday_remove), { confirmRemove = true }, danger = true)
                }
            }
        },
        confirmButton = {
            TextButton({ a.vm.save(d, original, onDismiss) }, enabled = problem == null && !state.saving) {
                Text(stringResource(if (d.id == null && !BirthdayRules.addsDirectly(feed)) R.string.birthday_suggest_send else R.string.common_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (confirmRemove && original != null) {
        // Removing is immediate; the snackbar offers Undo instead of a long confirmation.
        confirmRemove = false
        onDismiss()
        a.vm.remove(original)
    }
}
