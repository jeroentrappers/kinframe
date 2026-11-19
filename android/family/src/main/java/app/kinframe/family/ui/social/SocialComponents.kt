package app.kinframe.family.ui.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.Freshness
import app.kinframe.core.social.FreshnessState
import app.kinframe.core.social.Mention
import app.kinframe.core.social.MentionText
import app.kinframe.core.social.PersonReaction
import app.kinframe.core.social.PersonRef
import app.kinframe.core.social.ReactionList
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.ReportReason
import app.kinframe.family.R
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.ToggleChip

/** A person's name, or "Former member" for a deleted account. */
@Composable
fun personName(p: PersonRef?): String = when {
    p == null -> ""
    p.formerMember -> stringResource(R.string.social_former_member)
    else -> p.displayName
}

@Composable
fun PersonAvatar(p: PersonRef?, size: Dp = 36.dp) {
    Avatar(personName(p).ifEmpty { "?" }, if (p?.formerMember == true) null else p?.avatarUrl, size)
}

/** A body with its @mentions emphasised and its links tappable (no previews in comments; README §5.18). */
@Composable
fun MentionBody(text: String, mentions: List<Mention>, modifier: Modifier = Modifier, style: TextStyle = Kin.type.body, color: Color = Kin.colors.ink, maxLines: Int = Int.MAX_VALUE) {
    val accent = Kin.colors.accent
    val annotated = remember(text, mentions, accent) { annotate(text, mentions, accent) }
    Text(annotated, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private val LINK = Regex("""https?://[^\s<>"]+""")

fun annotate(text: String, mentions: List<Mention>, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    MentionText.spans(text, mentions).forEach { m -> addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), m.start, m.start + m.length) }
    LINK.findAll(text).forEach { r ->
        val url = r.value.trimEnd('.', ',', ')', '!', '?')
        addLink(LinkAnnotation.Url(url), r.range.first, r.range.first + url.length)
        addStyle(SpanStyle(color = accent), r.range.first, r.range.first + url.length)
    }
}

/** The emoji picker: the six people's reactions (README §5.18). */
@Composable
fun ReactionPicker(expanded: Boolean, mine: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PersonReaction.ALL.forEach { r ->
                val selected = r == mine
                val name = reactionName(r)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (selected) Kin.colors.accentSoft else Color.Transparent)
                        .clickable(role = Role.Button) { onDismiss(); onPick(if (selected) null else r) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) { Text(PersonReaction.emoji(r), style = Kin.type.title) }
            }
        }
    }
}

@Composable
fun reactionName(r: String): String = stringResource(when (r) {
    PersonReaction.LOVE -> R.string.social_reaction_love
    PersonReaction.LAUGH -> R.string.social_reaction_laugh
    PersonReaction.WOW -> R.string.social_reaction_wow
    PersonReaction.SAD -> R.string.social_reaction_sad
    PersonReaction.THUMBS_UP -> R.string.social_reaction_thumbs_up
    else -> R.string.social_reaction_pray
})

/**
 * The reaction row: people and frames together ("❤ Oma's frame, Anna and 3 others"). Tap the smile to react, tap
 * your own reaction to take it back, tap the words for who reacted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(s: ReactionSummary, me: String?, onReact: ((String?) -> Unit)?, onWho: (() -> Unit)?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Kin.colors
    var picking by remember { mutableStateOf(false) }
    val you = stringResource(R.string.social_you)
    val frameLabel = stringResource(R.string.social_frame_label)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onReact != null) Box {
            val mine = s.mine
            Box(
                Modifier.heightIn(min = 36.dp).clip(CircleShape).background(if (mine != null) c.accentSoft else c.paperDeep)
                    .clickable(role = Role.Button) { if (mine != null && compact) onReact(null) else picking = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (mine != null) Text(PersonReaction.emoji(mine), style = Kin.type.body)
                else Icon(KinIcons.Smile, stringResource(R.string.social_react), Modifier.size(18.dp), tint = c.inkSoft)
            }
            ReactionPicker(picking, mine, { picking = false }, onReact)
        }
        if (s.total > 0) {
            val row = ReactionText.row(s, { frameLabel.format(it) }, me, you)
            val emojis = s.counts.take(3).joinToString("") { PersonReaction.emoji(it.emoji) }
            val words = when {
                row.names.isEmpty() -> pluralStringResource(R.plurals.social_reactions_count, s.total, s.total)
                row.others == 0 -> row.names.joinToString(", ")
                else -> pluralStringResource(R.plurals.social_reactions_others, row.others, row.names.joinToString(", "), row.others)
            }
            Text(
                "$emojis $words", style = Kin.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).let { m -> if (onWho != null) m.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onWho).padding(4.dp) else m },
            )
        }
    }
}

/** "Who reacted": people and frames, from `listReactions`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoReactedSheet(load: suspend () -> ReactionList, onDismiss: () -> Unit) {
    val c = Kin.colors
    val list by produceState<Load<ReactionList>>(Load.Loading) { value = try { Load.Ready(load()) } catch (e: Exception) { Load.Error(SocialProblems.message(e)) } }
    ModalBottomSheet(onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.social_who_reacted), style = Kin.type.title, color = c.ink)
            when (val l = list) {
                Load.Loading -> Text(stringResource(R.string.social_loading), style = Kin.type.caption, color = c.inkSoft)
                is Load.Error -> Text(stringResource(l.message), style = Kin.type.caption, color = c.danger)
                is Load.Ready -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(l.value.frames, key = { "f" + it.frameId + it.itemId }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(c.paperDeep), contentAlignment = Alignment.Center) { Icon(KinIcons.Frame, null, Modifier.size(18.dp), tint = c.inkSoft) }
                            Text(stringResource(R.string.social_frame_label, f.frameName), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                            Text(PersonReaction.emoji(f.reaction), style = Kin.type.title)
                        }
                    }
                    items(l.value.people, key = { "p" + it.person.userId }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PersonAvatar(p.person)
                            Text(personName(p.person), style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                            Text(PersonReaction.emoji(p.emoji), style = Kin.type.title)
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The text field for comments and messages with @mention autocomplete: typing "@An" offers the people who may see
 * it; picking one inserts "@Anna" with its span (UTF-16 offsets, README §5.18).
 */
@Composable
fun MentionField(
    draft: MentionText,
    onChange: (MentionText) -> Unit,
    people: List<PersonRef>,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxMentions: Int = 20,
) {
    val c = Kin.colors
    var value by remember { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    // The draft changed from outside (sent, edit started): follow it.
    LaunchedEffect(draft.text) { if (draft.text != value.text) value = TextFieldValue(draft.text, TextRange(draft.text.length)) }
    val query = remember(value, draft) { if (value.selection.collapsed) draft.copy(text = value.text).query(value.selection.start) else null }
    val matches = remember(query, people) {
        if (query == null || draft.mentions.size >= maxMentions) emptyList()
        else people.filter { !it.formerMember && it.displayName.contains(query.text, ignoreCase = true) }.take(5)
    }
    Column(modifier) {
        if (matches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
                matches.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = c.line)
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            val (next, cursor) = draft.copy(text = value.text).insert(query!!, p.userId, p.displayName)
                            value = TextFieldValue(next.text, TextRange(cursor))
                            onChange(next)
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PersonAvatar(p, 28.dp)
                        Text(p.displayName, style = Kin.type.body, color = c.ink)
                    }
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { v -> value = v; if (v.text != draft.text) onChange(draft.edited(v.text)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = c.inkFaint) },
            enabled = enabled,
            maxLines = 6,
            textStyle = Kin.type.body,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.line, unfocusedContainerColor = c.surface, focusedContainerColor = c.surface, cursorColor = c.accent,
            ),
        )
    }
}

/** Report a comment, message or post: why, and an optional note for the family's admins or the operator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDialog(onDismiss: () -> Unit, onReport: (reason: String, note: String?) -> Unit) {
    var reason by rememberSaveable { mutableStateOf(ReportReason.UNKIND) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.chat_report_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportReason.ALL.forEach { r -> ToggleChip(stringResource(reportReasonLabel(r)), r == reason, { reason = r }) }
                }
                app.kinframe.family.ui.KinTextField(note, { note = it.take(500) }, stringResource(R.string.chat_report_note), singleLine = false, minLines = 2)
            }
        },
        confirmButton = { TextButton({ onDismiss(); onReport(reason, note.trim().ifEmpty { null }) }) { Text(stringResource(R.string.chat_report_send), color = Kin.colors.danger) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

fun reportReasonLabel(r: String): Int = when (r) {
    ReportReason.UNKIND -> R.string.chat_report_unkind
    ReportReason.SPAM -> R.string.chat_report_spam
    ReportReason.INAPPROPRIATE -> R.string.chat_report_inappropriate
    else -> R.string.chat_report_other
}

/** "Fresh", "4 days", "2 weeks quiet": words, not numbers, under the ring (README §5.20). */
@Composable
fun freshnessLabel(f: Freshness?): String? {
    f ?: return null
    val days = f.daysQuiet
    return when {
        f.state == FreshnessState.NEVER -> stringResource(R.string.engagement_fresh_never)
        f.state == FreshnessState.FRESH || days == 0 -> stringResource(R.string.engagement_fresh_today)
        days == null -> null
        f.state == FreshnessState.QUIET && days >= 14 -> pluralStringResource(R.plurals.engagement_fresh_weeks_quiet, days / 7, days / 7)
        f.state == FreshnessState.QUIET -> pluralStringResource(R.plurals.engagement_fresh_days_quiet, days, days)
        else -> pluralStringResource(R.plurals.engagement_fresh_days, days, days)
    }
}

/** The freshness ring: full and warm when shared today, thinner and paler over 14 days. */
@Composable
fun FreshnessRing(level: Double?, modifier: Modifier = Modifier, size: Dp = 64.dp, content: @Composable () -> Unit) {
    val c = Kin.colors
    val l = (level ?: 0.0).toFloat().coerceIn(0f, 1f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 2.dp.toPx() + 3.dp.toPx() * l
            val inset = stroke / 2
            drawArc(c.line, 0f, 360f, false, topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(2.dp.toPx()))
            if (l > 0f) drawArc(
                c.accent.copy(alpha = 0.35f + 0.65f * l), -90f, 360f * l, false,
                topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Box(Modifier.size(size - 12.dp).clip(CircleShape), contentAlignment = Alignment.Center) { content() }
    }
}

/** A long-press menu entry list for comments and messages. */
data class MenuAction(val label: Int, val danger: Boolean = false, val run: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.longPressMenu(onLongPress: () -> Unit, onClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongPress)

@Composable
fun ActionMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        actions.forEach { a ->
            DropdownMenuItem(
                text = { Text(stringResource(a.label), color = if (a.danger) Kin.colors.danger else Kin.colors.ink) },
                onClick = { onDismiss(); a.run() },
            )
        }
    }
}

/** A row of choices, as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> ToggleChip(label, v == selected, { onSelect(v) }) }
    }
}

/** The small "unread" dot with a count. */
@Composable
fun UnreadPill(count: Int, mentions: Boolean = false) {
    if (count <= 0) return
    val c = Kin.colors
    Box(Modifier.heightIn(min = 20.dp).clip(CircleShape).background(if (mentions) c.accent else c.sage).padding(horizontal = 7.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else count.toString(), style = Kin.type.caption, color = Color.White)
    }
}

@Composable
fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = Kin.colors.inkSoft) {
    IconButton(onClick) { Icon(icon, label, tint = tint) }
}

@Composable
fun Hairline() = HorizontalDivider(color = Kin.colors.line)

@Composable
fun SpacerW(w: Dp) = Spacer(Modifier.width(w))
