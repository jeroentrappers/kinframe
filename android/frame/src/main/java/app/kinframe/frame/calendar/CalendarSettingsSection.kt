package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.CalendarScheduleDto
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.schedule.SleepPlanner
import app.kinframe.frame.ui.Labeled
import app.kinframe.frame.ui.SectionCard
import app.kinframe.frame.ui.SwitchRow
import app.kinframe.frame.ui.TimeStepper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

private val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

/**
 * Frame settings → Calendar (behaviour spec B.7 §6): view options, the hybrid sidebar and countdown widgets, timed
 * view schedules, and the frame's calendars with their sync status (they are added from the app or web).
 */
@Composable
fun CalendarSettingsSection(c: FrameController, s: FrameSettings, onOpen: () -> Unit, patch: (JsonObject) -> Unit) {
    val cal = s.calendar
    val data by c.calendar.data.collectAsState()
    fun calendar(vararg fields: Pair<String, Any?>) = patch(buildJsonObject {
        put("calendar", buildJsonObject { fields.forEach { (k, v) -> put(k, with(app.kinframe.core.api.MergePatch) { v.toJson() }) } })
    })
    fun widget(key: String, v: Boolean) = patch(buildJsonObject { put("widgets", buildJsonObject { put(key, JsonPrimitive(v)) }) })
    fun schedules(list: List<CalendarScheduleDto>) = patch(buildJsonObject {
        put("calendar", buildJsonObject { put("timed_views", KinJson.encodeToJsonElement(ListSerializer(CalendarScheduleDto.serializer()), list)) })
    })

    SectionCard(stringResource(R.string.settings_calendar)) {
        KinButton(stringResource(R.string.settings_calendar_open), onOpen, style = KinButtonStyle.Secondary, leading = { KinIcon(CalendarIcons.Calendar, null, size = 22.dp) })
        Labeled(stringResource(R.string.settings_calendar_view)) {
            ChoiceChips(
                listOf(
                    "month" to stringResource(R.string.calendar_view_month),
                    "week" to stringResource(R.string.calendar_view_week),
                    "four_day" to stringResource(R.string.calendar_view_four_day),
                ),
                CalendarView.of(cal.view).key, { calendar("view" to it) },
            )
        }
        SwitchRow(stringResource(R.string.settings_calendar_start_today), cal.startOnToday) { calendar("start_on_today" to it) }
        SwitchRow(stringResource(R.string.settings_calendar_fade_past), cal.fadePast) { calendar("fade_past" to it) }
        SwitchRow(stringResource(R.string.settings_calendar_week_numbers), cal.weekNumbers) { calendar("week_numbers" to it) }
        SwitchRow(stringResource(R.string.settings_calendar_sidebar), s.widgets.calendarSidebar) { widget("calendar_sidebar", it) }
        SwitchRow(stringResource(R.string.settings_calendar_countdowns), s.widgets.countdowns) { widget("countdowns", it) }

        // Timed view: new schedules default to 07:00–09:00 every day (behaviour spec B.8).
        val anyOn = cal.timedViews.any { it.enabled }
        SwitchRow(stringResource(R.string.settings_calendar_timed), anyOn) { on ->
            schedules(
                when {
                    on && cal.timedViews.isEmpty() -> listOf(CalendarScheduleDto(id = UUID.randomUUID().toString()))
                    else -> cal.timedViews.map { it.copy(enabled = on) }
                },
            )
        }
        if (anyOn) {
            val first = CalendarLayout.firstDayOfWeek(s.locale.firstDayOfWeek)
            cal.timedViews.forEachIndexed { i, sch ->
                fun write(updated: CalendarScheduleDto) = schedules(cal.timedViews.mapIndexed { j, x -> if (j == i) updated else x })
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kin.colors.paper).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
                        TimeStepper(stringResource(R.string.settings_calendar_timed_from), sch.from, { write(sch.copy(from = shift(sch.from, -15))) }, { write(sch.copy(from = shift(sch.from, 15))) })
                        TimeStepper(stringResource(R.string.settings_calendar_timed_to), sch.to, { write(sch.copy(to = shift(sch.to, -15))) }, { write(sch.copy(to = shift(sch.to, 15))) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DayToggles(sch.days, first) { write(sch.copy(days = it)) }
                        Box(Modifier.weight(1f))
                        KinButton(stringResource(R.string.settings_calendar_timed_remove), { schedules(cal.timedViews.filterIndexed { j, _ -> j != i }) }, style = KinButtonStyle.Quiet)
                    }
                }
            }
            KinButton(
                stringResource(R.string.settings_calendar_timed_add), { schedules(cal.timedViews + CalendarScheduleDto(id = UUID.randomUUID().toString())) },
                style = KinButtonStyle.Quiet, leading = { KinIcon(KinIcons.Plus, null, tint = Kin.colors.accent, size = 22.dp) },
            )
        }

        // The frame's calendars (read-only here).
        val sources = data?.sources.orEmpty()
        Labeled(stringResource(R.string.settings_calendar_sources)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sources.forEach { src ->
                    val sw = CalendarPalette.of(src.color)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (src.isLocal) KinIcon(KinIcons.HeartFilled, null, tint = sw.strong, size = 18.dp)
                        else Box(Modifier.size(16.dp).clip(CircleShape).background(sw.strong))
                        Text(sourceLabel(src.name, src.isLocal), style = Kin.type.body, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Text(
                            stringResource(statusLabel(src.status)), style = Kin.type.caption.copy(fontWeight = FontWeight.Medium),
                            color = if (src.status == "failed" || src.status == "auth_failed") Kin.colors.danger else Kin.colors.inkSoft,
                        )
                    }
                }
                Text(stringResource(R.string.settings_calendar_sources_hint), style = Kin.type.caption, color = Kin.colors.inkSoft)
            }
        }
    }
}

private fun statusLabel(status: String): Int = when (status) {
    "syncing" -> R.string.calendar_status_syncing
    "failed" -> R.string.calendar_status_failed
    "auth_failed" -> R.string.calendar_status_auth_failed
    "disabled" -> R.string.calendar_status_disabled
    else -> R.string.calendar_status_ok
}

private fun shift(t: String, minutes: Int): String {
    val lt = SleepPlanner.parseTime(t) ?: return t
    return lt.plusMinutes(minutes.toLong()).toString().take(5)
}

/** Seven round weekday toggles, starting on the locale's first day of the week. */
@Composable
private fun DayToggles(days: List<String>, first: DayOfWeek, onChange: (List<String>) -> Unit) {
    val locale = currentLocale()
    val monday = LocalDate.of(2026, 10, 19)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 7) {
            val dow = first.plus(i.toLong())
            val key = DAY_KEYS[dow.value - 1]
            val on = key in days
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (on) Kin.colors.accent else Kin.colors.surface)
                    .border(1.dp, if (on) Kin.colors.accent else Kin.colors.line, CircleShape)
                    .clickable { onChange(if (on) days - key else DAY_KEYS.filter { it in days || it == key }) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    CalendarFormat.weekdayShort(monday.plusDays((dow.value - 1).toLong()), locale).take(2),
                    style = Kin.type.label, color = if (on) Kin.colors.onAccent else Kin.colors.inkSoft,
                )
            }
        }
    }
}
