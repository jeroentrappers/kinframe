package app.kinframe.family.ui.engagement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.EmailDigest
import app.kinframe.core.social.FeedEngagementUpdate
import app.kinframe.core.social.FeedPrompt
import app.kinframe.core.social.FeedPromptSet
import app.kinframe.core.social.Milestone
import app.kinframe.core.social.MilestoneKey
import app.kinframe.core.social.MonthlyRecap
import app.kinframe.core.social.OnThisDayGroup
import app.kinframe.core.social.PhotoRequestState
import app.kinframe.core.social.PromptStatus
import app.kinframe.core.social.ReminderLevel
import app.kinframe.core.social.ReminderPreferencesUpdate
import app.kinframe.core.social.WeeklySummary
import app.kinframe.family.R
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
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.chats.monthName
import app.kinframe.family.ui.social.ChoiceRow
import app.kinframe.family.ui.social.FreshnessRing
import app.kinframe.family.ui.social.PersonAvatar
import app.kinframe.family.ui.social.freshnessLabel
import app.kinframe.family.ui.social.personName
import app.kinframe.family.util.Format

@Composable
fun milestoneTitle(key: String): String = stringResource(when (key) {
    MilestoneKey.FIRST_PHOTO -> R.string.engagement_milestone_first_photo
    MilestoneKey.PHOTOS_10 -> R.string.engagement_milestone_photos_10
    MilestoneKey.PHOTOS_100 -> R.string.engagement_milestone_photos_100
    MilestoneKey.PHOTOS_1000 -> R.string.engagement_milestone_photos_1000
    MilestoneKey.FIRST_VIDEO -> R.string.engagement_milestone_first_video
    MilestoneKey.YEAR_OF_SHARING -> R.string.engagement_milestone_year
    MilestoneKey.LOVES_100 -> R.string.engagement_milestone_loves_100
    else -> R.string.engagement_milestone_other
})

/**
 * Settings → Reminders and notifications: level, frame requests, weekly summary, birthday reminders, snooze, quiet
 * hours, email digest (README §5.20), read receipts (§5.19) and blocked people.
 */
@Composable
fun RemindersScreen(
    s: RemindersState,
    vm: RemindersViewModel,
    chats: Boolean,
    engagement: Boolean,
    onBack: () -> Unit,
    onBlocked: (() -> Unit)?,
    onMilestones: (() -> Unit)?,
    onReports: (() -> Unit)?,
) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.engagement_settings_title), onBack = onBack)
        PageColumn {
            if (engagement) when (val l = s.prefs) {
                Load.Loading -> SkeletonList(2, 80.dp)
                is Load.Error -> ErrorPanel(l.message, { vm.load() })
                is Load.Ready -> {
                    val p = l.value
                    SectionTitle(stringResource(R.string.engagement_reminders))
                    Text(stringResource(R.string.engagement_reminders_lede), style = Kin.type.body, color = c.inkSoft)
                    if (p.autoReduced) Note(stringResource(R.string.engagement_auto_reduced), tone = NoteTone.Info)
                    ChoiceRow(ReminderLevel.VALUES.map { it to stringResource(levelLabel(it)) }, p.level) { vm.update(ReminderPreferencesUpdate(level = it)) }
                    Text(stringResource(levelHint(p.level)), style = Kin.type.caption, color = c.inkSoft)
                    val snoozed = p.snoozedUntil?.let { Format.instant(it) }?.takeIf { it.toEpochMilli() > System.currentTimeMillis() }
                    if (snoozed != null) {
                        Note(stringResource(R.string.engagement_snoozed_until, Format.date(p.snoozedUntil)))
                        QuietButton(stringResource(R.string.engagement_snooze_end), { vm.snooze(0) })
                    } else SecondaryButton(stringResource(R.string.engagement_snooze_month), { vm.snooze(30) }, icon = KinIcons.Moon)
                    SwitchRow(stringResource(R.string.engagement_frame_requests), p.frameRequests, { vm.update(ReminderPreferencesUpdate(frameRequests = it)) },
                        hint = stringResource(R.string.engagement_frame_requests_hint))
                    SwitchRow(stringResource(R.string.engagement_weekly_summary), p.weeklySummary, { vm.update(ReminderPreferencesUpdate(weeklySummary = it)) },
                        hint = stringResource(R.string.engagement_weekly_summary_hint))
                    SwitchRow(stringResource(R.string.engagement_birthday_reminders), p.birthdayReminders, { vm.update(ReminderPreferencesUpdate(birthdayReminders = it)) },
                        hint = stringResource(R.string.engagement_birthday_reminders_hint))
                    SectionTitle(stringResource(R.string.engagement_quiet_hours))
                    Text(stringResource(R.string.engagement_quiet_hours_hint), style = Kin.type.caption, color = c.inkSoft)
                    QuietHoursPicker(p.quietHours.start, p.quietHours.end) { a, b -> vm.setQuietHours(a, b) }
                    if (p.emailDigestAvailable) {
                        SectionTitle(stringResource(R.string.engagement_email_digest))
                        Text(stringResource(R.string.engagement_email_digest_hint), style = Kin.type.caption, color = c.inkSoft)
                        ChoiceRow(EmailDigest.VALUES.map { it to stringResource(digestLabel(it)) }, p.emailDigest) { vm.update(ReminderPreferencesUpdate(emailDigest = it)) }
                    }
                    onMilestones?.let { SecondaryButton(stringResource(R.string.engagement_milestones), it, icon = KinIcons.Star) }
                }
            }
            if (chats) {
                SectionTitle(stringResource(R.string.chat_settings_title))
                SwitchRow(stringResource(R.string.chat_read_receipts), s.showReadReceipts, vm::setReadReceipts, hint = stringResource(R.string.chat_read_receipts_hint))
                onBlocked?.let { SecondaryButton(stringResource(R.string.chat_blocked_title), it, icon = KinIcons.Shield) }
                onReports?.let { SecondaryButton(stringResource(R.string.chat_reports_operator_title), it, icon = KinIcons.Warning) }
                Text(stringResource(R.string.chat_operator_note), style = Kin.type.caption, color = c.inkFaint)
            }
        }
    }
}

private fun levelLabel(l: String) = when (l) {
    ReminderLevel.FEWER -> R.string.engagement_level_fewer
    ReminderLevel.OFF -> R.string.engagement_level_off
    else -> R.string.engagement_level_normal
}

private fun levelHint(l: String) = when (l) {
    ReminderLevel.FEWER -> R.string.engagement_level_fewer_hint
    ReminderLevel.OFF -> R.string.engagement_level_off_hint
    else -> R.string.engagement_level_normal_hint
}

private fun digestLabel(d: String) = when (d) {
    EmailDigest.DAILY -> R.string.engagement_digest_daily
    EmailDigest.WEEKLY -> R.string.engagement_digest_weekly
    else -> R.string.engagement_digest_off
}

/** Two hour pickers ("from 21:00 to 09:00"), whole hours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietHoursPicker(start: String, end: String, onChange: (String, String) -> Unit) {
    var a by remember(start) { mutableFloatStateOf(start.substringBefore(':').toFloatOrNull() ?: 21f) }
    var b by remember(end) { mutableFloatStateOf(end.substringBefore(':').toFloatOrNull() ?: 9f) }
    val c = Kin.colors
    fun hh(v: Float) = "%02d:00".format(v.toInt())
    Column {
        Text(stringResource(R.string.engagement_quiet_from, hh(a)), style = Kin.type.label, color = c.ink)
        Slider(a, { a = it }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { onChange(hh(a), hh(b)) },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
        Text(stringResource(R.string.engagement_quiet_to, hh(b)), style = Kin.type.label, color = c.ink)
        Slider(b, { b = it }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { onChange(hh(a), hh(b)) },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
    }
}

/** A family's streak and weekly prompts; admins change the goal, prompts, recap and the quiet threshold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedEngagementScreen(s: FeedEngagementState, vm: FeedEngagementViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    val admin = s.feed?.isAdmin == true
    var choosing by remember { mutableStateOf<FeedPrompt?>(null) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.engagement_family_title, s.feed?.name.orEmpty()), onBack = onBack)
        PageColumn {
            val settings = s.settings
            when (val st = s.streak) {
                Load.Loading -> SkeletonList(1, 120.dp)
                is Load.Error -> ErrorPanel(st.message, { vm.load() })
                is Load.Ready -> {
                    val k = st.value
                    if (k.enabled) Surface(Modifier.fillMaxWidth()) {
                        Text(if (k.currentWeeks > 0) pluralStringResource(R.plurals.engagement_streak_weeks, k.currentWeeks, k.currentWeeks) else stringResource(R.string.engagement_streak_new),
                            style = Kin.type.headline, color = c.ink)
                        Text(pluralStringResource(R.plurals.engagement_streak_week_progress, k.goal, k.week.photos, k.goal), style = Kin.type.body, color = c.inkSoft)
                        if (k.week.met) Pill(stringResource(R.string.engagement_streak_met), c.sageSoft, c.onSageSoft)
                        if (k.week.helpers.isNotEmpty()) {
                            Text(stringResource(R.string.engagement_streak_helpers), style = Kin.type.label, color = c.inkSoft)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { k.week.helpers.forEach { PersonAvatar(it, 30.dp) } }
                        }
                        Text(stringResource(R.string.engagement_streak_best, k.bestWeeks) + " · " + pluralStringResource(R.plurals.engagement_freezes, k.freezesLeft, k.freezesLeft),
                            style = Kin.type.caption, color = c.inkFaint)
                    } else Note(stringResource(R.string.engagement_streak_off))
                }
            }
            if (settings?.promptsEnabled == true || s.prompts.isNotEmpty()) {
                SectionTitle(stringResource(R.string.engagement_prompts))
                s.current?.let { p ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engagement_prompt_current), style = Kin.type.label, color = c.inkSoft)
                        Text(p.title.ifBlank { stringResource(R.string.engagement_prompt_skipped_week) }, style = Kin.type.headline, color = c.ink)
                        if (p.answerCount > 0) Text(pluralStringResource(R.plurals.engagement_prompt_answers, p.answerCount, p.answerCount), style = Kin.type.caption, color = c.inkSoft)
                        if (admin) SecondaryButton(stringResource(R.string.engagement_prompt_change), { choosing = p; vm.loadLibrary() })
                    }
                }
                if (admin) s.planned.forEach { p ->
                    Surface(Modifier.fillMaxWidth(), onClick = { choosing = p; vm.loadLibrary() }) {
                        Text(stringResource(R.string.engagement_prompt_week_of, Format.date(p.weekOf + "T12:00:00Z")), style = Kin.type.caption, color = c.inkSoft)
                        Text(if (p.status == PromptStatus.SKIPPED) stringResource(R.string.engagement_prompt_skipped_week) else p.title, style = Kin.type.bodyStrong, color = c.ink)
                    }
                }
            }
            if (admin && settings != null) {
                SectionTitle(stringResource(R.string.engagement_family_settings))
                SwitchRow(stringResource(R.string.engagement_setting_streak), settings.streakEnabled, { vm.update(FeedEngagementUpdate(streakEnabled = it)) },
                    hint = stringResource(R.string.engagement_setting_streak_hint), enabled = !s.busy)
                if (settings.streakEnabled) {
                    var goal by remember(settings.streakGoal) { mutableFloatStateOf(settings.streakGoal.toFloat()) }
                    Text(pluralStringResource(R.plurals.engagement_setting_goal, goal.toInt(), goal.toInt()), style = Kin.type.label, color = c.ink)
                    Slider(goal, { goal = it }, valueRange = 1f..20f, steps = 18, onValueChangeFinished = { vm.update(FeedEngagementUpdate(streakGoal = goal.toInt())) },
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                }
                SwitchRow(stringResource(R.string.engagement_setting_prompts), settings.promptsEnabled, { vm.update(FeedEngagementUpdate(promptsEnabled = it)) },
                    hint = stringResource(R.string.engagement_setting_prompts_hint), enabled = !s.busy)
                SwitchRow(stringResource(R.string.engagement_setting_recap), settings.monthlyRecap, { vm.update(FeedEngagementUpdate(monthlyRecap = it)) },
                    hint = stringResource(R.string.engagement_setting_recap_hint), enabled = !s.busy)
                var quiet by remember(settings.quietAfterDays) { mutableFloatStateOf(settings.quietAfterDays.toFloat()) }
                Text(pluralStringResource(R.plurals.engagement_setting_quiet, quiet.toInt(), quiet.toInt()), style = Kin.type.label, color = c.ink)
                Slider(quiet, { quiet = it }, valueRange = 3f..30f, steps = 26, onValueChangeFinished = { vm.update(FeedEngagementUpdate(quietAfterDays = quiet.toInt())) },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                Text(stringResource(R.string.engagement_setting_timezone, settings.timezone), style = Kin.type.caption, color = c.inkSoft)
                val here = java.util.TimeZone.getDefault().id
                if (here != settings.timezone) QuietButton(stringResource(R.string.engagement_setting_timezone_use, here), { vm.update(FeedEngagementUpdate(timezone = here)) })
            }
        }
    }
    choosing?.let { p -> PromptChooser(p, s, { choosing = null }, { set -> choosing = null; vm.setPrompt(p.weekOf, set) }, { choosing = null; vm.resetPrompt(p.weekOf) }) }
}

@Composable
private fun PromptChooser(p: FeedPrompt, s: FeedEngagementState, onDismiss: () -> Unit, onSet: (FeedPromptSet) -> Unit, onReset: () -> Unit) {
    var own by remember { mutableStateOf("") }
    val c = Kin.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engagement_prompt_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KinTextField(own, { own = it.take(80) }, stringResource(R.string.engagement_prompt_own), placeholder = stringResource(R.string.engagement_prompt_own_placeholder))
                TextButton({ onSet(FeedPromptSet(title = own.trim())) }, enabled = own.isNotBlank()) { Text(stringResource(R.string.engagement_prompt_use_own)) }
                HorizontalDivider(color = c.line)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(s.library, key = { it.key }) { e ->
                        Text(e.title, style = Kin.type.body, color = if (e.key == p.libraryKey) c.accent else c.ink,
                            modifier = Modifier.fillMaxWidth().clickable { onSet(FeedPromptSet(libraryKey = e.key)) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSet(FeedPromptSet(skip = true)) }) { Text(stringResource(R.string.engagement_prompt_skip)) } },
        dismissButton = {
            Row {
                if (p.source == "custom" || p.status == PromptStatus.SKIPPED) TextButton(onReset) { Text(stringResource(R.string.engagement_prompt_reset_button)) }
                TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
fun WeeklySummaryScreen(s: Load<WeeklySummary>, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.engagement_summary_title), onBack = onBack)
        when (s) {
            Load.Loading -> SkeletonList(2, 100.dp)
            is Load.Error -> ErrorPanel(s.message, onRetry)
            is Load.Ready -> PageColumn {
                val w = s.value
                Text(stringResource(R.string.engagement_summary_week, Format.date(w.weekOf + "T12:00:00Z")), style = Kin.type.caption, color = c.inkSoft)
                Text(pluralStringResource(R.plurals.engagement_summary_seen, w.seenCount, w.seenCount, w.frameCount), style = Kin.type.headline, color = c.ink)
                if (w.loveCount > 0) Text(pluralStringResource(R.plurals.engagement_summary_loved, w.loveCount, w.loveCount), style = Kin.type.title, color = c.ink)
                if (w.reactionCount > 0 || w.commentCount > 0) Text(
                    pluralStringResource(R.plurals.engagement_summary_reactions, w.reactionCount, w.reactionCount) + " · " +
                        pluralStringResource(R.plurals.engagement_summary_comments, w.commentCount, w.commentCount),
                    style = Kin.type.body, color = c.inkSoft,
                )
                w.topItem?.let { Thumb(it.thumbUrl, it.caption, Modifier.fillMaxWidth().heightIn(min = 200.dp)) }
                w.frames.forEach { f ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(f.frameName, style = Kin.type.body, color = c.ink, modifier = Modifier.weight(1f))
                        Text("👁 ${f.seenCount}   ❤️ ${f.loveCount}", style = Kin.type.body, color = c.inkSoft)
                    }
                }
            }
        }
    }
}

@Composable
fun RecapScreen(s: Load<MonthlyRecap>, onRetry: () -> Unit, onBack: () -> Unit, onOpenPost: (String) -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar((s as? Load.Ready)?.value?.let { stringResource(R.string.engagement_recap_title, monthName(it.month)) } ?: stringResource(R.string.engagement_recap), onBack = onBack)
        when (s) {
            Load.Loading -> SkeletonList(2, 100.dp)
            is Load.Error -> ErrorPanel(s.message, onRetry)
            is Load.Ready -> PageColumn {
                val r = s.value
                Text(pluralStringResource(R.plurals.engagement_recap_photos, r.photosShared, r.photosShared) + " · " +
                    pluralStringResource(R.plurals.engagement_recap_videos, r.videosShared, r.videosShared), style = Kin.type.headline, color = c.ink)
                Text(pluralStringResource(R.plurals.engagement_recap_sharers, r.sharerCount, r.sharerCount), style = Kin.type.body, color = c.inkSoft)
                r.mostLoved?.let { ml ->
                    SectionTitle(stringResource(R.string.engagement_recap_most_loved))
                    Surface(Modifier.fillMaxWidth(), onClick = { onOpenPost(ml.postId) }) {
                        Thumb(ml.item.thumbUrl, ml.item.caption, Modifier.fillMaxWidth().heightIn(min = 200.dp))
                        Text(pluralStringResource(R.plurals.social_reactions_count, ml.reactionCount, ml.reactionCount), style = Kin.type.caption, color = c.inkSoft)
                    }
                }
                if (r.freshestFrames.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.engagement_recap_freshest))
                    r.freshestFrames.forEach { f -> Text(pluralStringResource(R.plurals.engagement_recap_frame_days, f.daysWithNewPhotos, f.frameName, f.daysWithNewPhotos), style = Kin.type.body, color = c.ink) }
                }
                if (r.newMembers.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.engagement_recap_new_members))
                    Text(r.newMembers.map { personName(it) }.joinToString(", "), style = Kin.type.body, color = c.ink)
                }
                r.streakWeeks?.takeIf { it > 0 }?.let { Text("🔥 " + pluralStringResource(R.plurals.engagement_streak_weeks, it, it), style = Kin.type.title, color = c.ink) }
            }
        }
    }
}

/** Personal milestones: private, optionally shown on your profile to your contacts. */
@Composable
fun MilestonesScreen(s: Load<List<Milestone>>, vm: MilestonesViewModel, onBack: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.engagement_milestones), onBack = onBack)
        when (s) {
            Load.Loading -> SkeletonList(3, 64.dp)
            is Load.Error -> ErrorPanel(s.message, { vm.load() })
            is Load.Ready -> PageColumn(spacing = 10.dp) {
                Text(stringResource(R.string.engagement_milestones_lede), style = Kin.type.body, color = c.inkSoft)
                if (s.value.isEmpty()) Note(stringResource(R.string.engagement_milestones_none))
                s.value.forEach { m ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text("✨ " + milestoneTitle(m.key), style = Kin.type.bodyStrong, color = c.ink)
                        Text(Format.date(m.achievedAt), style = Kin.type.caption, color = c.inkSoft)
                        SwitchRow(stringResource(R.string.engagement_milestone_show), m.shown, { vm.setShown(m, it) }, hint = stringResource(R.string.engagement_milestone_show_hint))
                    }
                }
            }
        }
    }
}

/** "Send something to Oma's frame": its freshness, who asked, and suggestions (On this day, the prompt). */
@Composable
fun FreshFrameScreen(s: FreshFrameState, onBack: () -> Unit, onSend: () -> Unit, onShareAgain: (OnThisDayGroup) -> Unit, onOpenFrame: () -> Unit) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        val f = (s.frame as? Load.Ready)?.value
        KinTopBar(f?.let { stringResource(R.string.engagement_send_to, it.name) }.orEmpty(), onBack = onBack)
        when (val l = s.frame) {
            Load.Loading -> SkeletonList(2, 100.dp)
            is Load.Error -> ErrorPanel(l.message, onBack)
            is Load.Ready -> PageColumn {
                val frame = l.value
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FreshnessRing(frame.freshness?.level, size = 76.dp) { androidx.compose.material3.Icon(KinIcons.Frame, null, Modifier.size(30.dp), tint = c.inkSoft) }
                    Column(Modifier.weight(1f)) {
                        Text(frame.name, style = Kin.type.headline, color = c.ink)
                        freshnessLabel(frame.freshness)?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
                        frame.freshness?.lastSharedBy?.let { p -> Text(stringResource(R.string.engagement_last_shared_by, personName(p), Format.relative(frame.freshness?.lastSharedAt)), style = Kin.type.caption, color = c.inkFaint) }
                    }
                }
                s.requests.firstOrNull()?.let { r ->
                    Note(when (r.state) {
                        PhotoRequestState.OPEN -> stringResource(R.string.engagement_request_open, frame.name, Format.relative(r.createdAt))
                        PhotoRequestState.ANSWERED -> stringResource(R.string.engagement_request_answered, personName(r.answeredBy))
                        else -> stringResource(R.string.engagement_request_expired)
                    }, tone = if (r.state == PhotoRequestState.OPEN) NoteTone.Warn else NoteTone.Good)
                }
                PrimaryButton(stringResource(R.string.engagement_pick_photos), onSend, Modifier.fillMaxWidth(), icon = KinIcons.Image)
                Text(stringResource(R.string.engagement_pick_photos_hint), style = Kin.type.caption, color = c.inkSoft)
                s.prompts.forEach { p ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.engagement_prompt_current), style = Kin.type.label, color = c.inkSoft)
                        Text(p.title, style = Kin.type.title, color = c.ink)
                    }
                }
                s.onThisDay?.groups?.forEach { g ->
                    Surface(Modifier.fillMaxWidth()) {
                        Text(pluralStringResource(R.plurals.engagement_years_ago_in, g.yearsAgo, g.yearsAgo, g.feedName), style = Kin.type.label, color = c.inkSoft)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(g.items, key = { it.id }) { Thumb(it.thumbUrl, null, Modifier.size(84.dp)) } }
                        SecondaryButton(stringResource(R.string.engagement_share_again), { onShareAgain(g) })
                    }
                }
                QuietButton(stringResource(R.string.engagement_open_frame), onOpenFrame)
            }
        }
    }
}

/** Frame settings → Family social (README §5.20): comments, "Ask for new photos", prompt cards, "New photos from". */
@Composable
fun FrameSocialSettings(get: (String, Boolean) -> Boolean, set: (String, Boolean) -> Unit, social: Boolean, engagement: Boolean) {
    val c = Kin.colors
    SectionTitle(stringResource(R.string.engagement_frame_settings))
    if (social) SwitchRow(stringResource(R.string.social_frame_show_comments), get("social.show_comments", false), { set("social.show_comments", it) },
        hint = stringResource(R.string.social_frame_show_comments_hint))
    if (engagement) {
        SwitchRow(stringResource(R.string.engagement_frame_ask), get("social.ask_for_photos", true), { set("social.ask_for_photos", it) },
            hint = stringResource(R.string.engagement_frame_ask_hint))
        SwitchRow(stringResource(R.string.engagement_frame_prompt_cards), get("social.prompt_cards", true), { set("social.prompt_cards", it) },
            hint = stringResource(R.string.engagement_frame_prompt_cards_hint))
        SwitchRow(stringResource(R.string.engagement_frame_new_from), get("social.new_photos_from", true), { set("social.new_photos_from", it) },
            hint = stringResource(R.string.engagement_frame_new_from_hint))
    }
    Text(stringResource(R.string.engagement_frame_settings_note), style = Kin.type.caption, color = c.inkFaint, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
