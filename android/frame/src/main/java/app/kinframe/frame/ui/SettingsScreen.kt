package app.kinframe.frame.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import app.kinframe.frame.Locales
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.LivePhotos
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.SleepScheduleDto
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import app.kinframe.frame.kiosk.OwnerGate
import app.kinframe.frame.kiosk.SetPinFlow
import app.kinframe.frame.R
import app.kinframe.frame.schedule.SleepPlanner
import app.kinframe.frame.setup.signalWord
import app.kinframe.frame.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The frame's basic settings (v0.1): slideshow, display, sleep. Edits apply immediately and sync to the server with
 * `PATCH /device/settings` (If-Match) through the outbox. Closes itself after 5 minutes without touch.
 */
@Composable
fun SettingsScreen(c: FrameController, onClose: () -> Unit) {
    val s by c.g.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var choosingPin by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch) { delay(5 * 60_000); onClose() }

    fun patch(p: JsonObject) {
        scope.launch { c.session.value?.outbox?.settingsPatch(p) }
    }
    fun section(name: String, vararg fields: Pair<String, Any?>) = patch(buildJsonObject {
        put(name, buildJsonObject { fields.forEach { (k, v) -> put(k, with(app.kinframe.core.api.MergePatch) { v.toJson() }) } })
    })

    Box(
        Modifier.fillMaxSize().background(Kin.colors.paper)
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch = System.currentTimeMillis(); c.gate.touch() } }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    KinIcon(KinIcons.ChevronLeft, stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.settings_title), style = Kin.type.headline, modifier = Modifier.padding(start = 8.dp))
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 88.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SlideshowSection(c, s, ::section)
                VideoSection(s, ::section)
                DisplaySection(c, s, ::section)
                SleepSection(s) { patch(it) }
                LanguageSection(c)
                OwnerPinSection(c, s) { choosingPin = true }
                UpdatesSection(c, s, ::section)
                RemovableSection(c)
                AboutSection(c)
                Spacer(Modifier.height(40.dp))
            }
        }
        if (choosingPin) {
            SetPinFlow(onDone = { pin -> choosingPin = false; scope.launch { c.setOwnerPin(pin) } }, onCancel = { choosingPin = false })
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    KinCard(Modifier.fillMaxWidth().widthIn(max = 1000.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(title, style = Kin.type.title)
            content()
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = Kin.type.label, color = Kin.colors.inkSoft)
        content()
    }
}

@Composable
private fun SlideshowSection(c: FrameController, s: FrameSettings, section: (String, Array<out Pair<String, Any?>>) -> Unit) {
    val context = LocalContext.current
    val ss = s.slideshow
    SectionCard(stringResource(R.string.settings_slideshow)) {
        Labeled(stringResource(R.string.settings_interval)) {
            ChoiceChips(
                Format.intervalPresets.map { it to Format.interval(context, it) }, ss.intervalS,
                { section("slideshow", arrayOf("interval_s" to it)) },
                Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        Labeled(stringResource(R.string.settings_transition)) {
            ChoiceChips(
                listOf(
                    "crossfade" to stringResource(R.string.transition_crossfade),
                    "slide" to stringResource(R.string.transition_slide),
                    "kenburns" to stringResource(R.string.transition_kenburns),
                ),
                ss.transition.takeIf { it == "slide" || it == "kenburns" } ?: "crossfade", { section("slideshow", arrayOf("transition" to it)) },
            )
        }
        Labeled(stringResource(R.string.settings_order)) {
            ChoiceChips(
                listOf(
                    "received" to stringResource(R.string.order_received),
                    "taken" to stringResource(R.string.order_taken),
                    "shuffle" to stringResource(R.string.order_shuffle),
                ),
                ss.order, { section("slideshow", arrayOf("order" to it)) },
            )
        }
        SwitchRow(stringResource(R.string.settings_reverse), ss.reverse) { section("slideshow", arrayOf("reverse" to it)) }
        SwitchRow(stringResource(R.string.settings_collage), ss.collage) { section("slideshow", arrayOf("collage" to it)) }
        if (ss.collage) {
            Labeled(stringResource(R.string.settings_collage_max)) {
                ChoiceChips((2..6).map { it to "$it" }, ss.collageMax.coerceIn(2, 6), { section("slideshow", arrayOf("collage_max" to it)) })
            }
        } else {
            SwitchRow(stringResource(R.string.settings_pair_portraits), ss.pairPortraits) { section("slideshow", arrayOf("pair_portraits" to it)) }
        }
        SwitchRow(stringResource(R.string.settings_on_this_day), ss.onThisDay) { section("slideshow", arrayOf("on_this_day" to it)) }
        Labeled(stringResource(R.string.settings_live_photos)) {
            ChoiceChips(
                listOf(
                    LivePhotos.ON to stringResource(R.string.live_photos_on),
                    LivePhotos.TAP to stringResource(R.string.live_photos_tap),
                    LivePhotos.OFF to stringResource(R.string.live_photos_off),
                ),
                ss.livePhotos.takeIf { it in LivePhotos.all } ?: LivePhotos.ON, { section("slideshow", arrayOf("live_photos" to it)) },
            )
        }
        Labeled(stringResource(R.string.settings_fill)) {
            ChoiceChips(
                listOf(true to stringResource(R.string.fill_fill), false to stringResource(R.string.fill_fit)),
                ss.fill, { section("slideshow", arrayOf("fill" to it)) },
            )
        }
    }
}

/** Video options (product spec 4.4 and §6: autoplay on, play once and hold, auto-mute off). */
@Composable
private fun VideoSection(s: FrameSettings, section: (String, Array<out Pair<String, Any?>>) -> Unit) {
    val v = s.video
    var volume by remember(v.volume) { mutableFloatStateOf(v.volume.toFloat()) }
    SectionCard(stringResource(R.string.settings_video)) {
        SwitchRow(stringResource(R.string.settings_video_autoplay), v.autoplay) { section("video", arrayOf("autoplay" to it)) }
        Labeled(stringResource(R.string.settings_video_playback)) {
            ChoiceChips(
                listOf(
                    "once_hold" to stringResource(R.string.video_once_hold),
                    "loop" to stringResource(R.string.video_loop),
                    "once_advance" to stringResource(R.string.video_once_advance),
                ),
                v.playback, { section("video", arrayOf("playback" to it)) },
            )
        }
        Labeled(stringResource(R.string.settings_video_volume)) {
            Slider(
                value = volume, onValueChange = { volume = it },
                onValueChangeFinished = { section("video", arrayOf("volume" to volume.toInt())) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Kin.colors.accent, activeTrackColor = Kin.colors.accent, inactiveTrackColor = Kin.colors.line),
            )
        }
        Labeled(stringResource(R.string.settings_video_auto_mute)) {
            ChoiceChips(
                listOf(0 to stringResource(R.string.video_auto_mute_off)) + listOf(10, 30, 60).map { it to stringResource(R.string.video_auto_mute_min, it) },
                v.autoMuteMin ?: 0, { section("video", arrayOf("auto_mute_min" to it.takeIf { m -> m > 0 })) },
            )
        }
    }
}

@Composable
private fun DisplaySection(c: FrameController, s: FrameSettings, section: (String, Array<out Pair<String, Any?>>) -> Unit) {
    var value by remember(s.display.brightness) { mutableFloatStateOf(s.display.brightness.toFloat()) }
    SectionCard(stringResource(R.string.settings_display)) {
        Labeled(stringResource(R.string.settings_brightness)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KinIcon(KinIcons.Moon, null, tint = Kin.colors.inkSoft, size = 24.dp)
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        c.display.previewBrightness = it.toInt()
                        c.display.applyBrightness()
                    },
                    onValueChangeFinished = {
                        c.display.previewBrightness = null
                        section("display", arrayOf("brightness" to value.toInt()))
                    },
                    valueRange = 15f..255f,
                    colors = SliderDefaults.colors(thumbColor = Kin.colors.accent, activeTrackColor = Kin.colors.accent, inactiveTrackColor = Kin.colors.line),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                KinIcon(KinIcons.Sun, null, tint = Kin.colors.inkSoft, size = 28.dp)
            }
        }
        if (s.location?.lat != null) {
            Labeled(stringResource(R.string.settings_brightness_mode)) {
                ChoiceChips(
                    listOf("manual" to stringResource(R.string.brightness_manual), "sun" to stringResource(R.string.brightness_sun)),
                    if (s.display.brightnessMode == "sun") "sun" else "manual",
                    { section("display", arrayOf("brightness_mode" to it)) },
                )
            }
        }
    }
}

@Composable
private fun SleepSection(s: FrameSettings, patch: (JsonObject) -> Unit) {
    val current = s.display.sleepSchedules.firstOrNull() ?: SleepScheduleDto(id = "default", enabled = false)
    fun write(updated: SleepScheduleDto) {
        val list = listOf(updated) + s.display.sleepSchedules.drop(1)
        patch(buildJsonObject {
            put("display", buildJsonObject {
                put("sleep_schedules", KinJson.encodeToJsonElement(ListSerializer(SleepScheduleDto.serializer()), list))
            })
        })
    }
    fun shift(t: String, minutes: Int): String {
        val lt = SleepPlanner.parseTime(t) ?: return t
        return lt.plusMinutes(minutes.toLong()).toString().take(5)
    }
    SectionCard(stringResource(R.string.settings_sleep)) {
        SwitchRow(stringResource(R.string.settings_sleep_enabled), current.enabled) { write(current.copy(enabled = it)) }
        if (current.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                TimeStepper(stringResource(R.string.settings_sleep_at), current.sleepAt, { write(current.copy(sleepAt = shift(current.sleepAt, -15))) }, { write(current.copy(sleepAt = shift(current.sleepAt, 15))) })
                TimeStepper(stringResource(R.string.settings_wake_at), current.wakeAt, { write(current.copy(wakeAt = shift(current.wakeAt, -15))) }, { write(current.copy(wakeAt = shift(current.wakeAt, 15))) })
            }
            if (s.display.sleepSchedules.size > 1) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.settings_sleep_more, s.display.sleepSchedules.size - 1, s.display.sleepSchedules.size - 1), style = Kin.type.caption, color = Kin.colors.inkSoft)
            }
        }
    }
}

@Composable
private fun TimeStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Labeled(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(KinIcons.Minus, onMinus)
            Text(value, style = Kin.type.headline, modifier = Modifier.padding(horizontal = 20.dp))
            StepButton(KinIcons.Plus, onPlus)
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(60.dp).clip(CircleShape).background(Kin.colors.paperDeep).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KinIcon(icon, null, tint = Kin.colors.ink)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Kin.type.body, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Kin.colors.accent, checkedThumbColor = Kin.colors.onAccent, uncheckedTrackColor = Kin.colors.paperDeep, uncheckedBorderColor = Kin.colors.line, uncheckedThumbColor = Kin.colors.inkFaint),
        )
    }
}

/** SD card and USB stick (product spec 4.10): opens the storage panel when one is in the frame. */
@Composable
private fun RemovableSection(c: FrameController) {
    val volumes by c.removable.volumes.collectAsState()
    val v = volumes.firstOrNull()
    SectionCard(stringResource(R.string.settings_removable)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KinIcon(KinIcons.Storage, null, tint = if (v != null) Kin.colors.sage else Kin.colors.inkFaint)
            Text(
                if (v == null) stringResource(R.string.settings_removable_none)
                else v.label ?: stringResource(if (v.kind == app.kinframe.frame.removable.Volume.Kind.Usb) R.string.removable_card_usb_title else R.string.removable_card_sd_title),
                style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            if (v != null) KinButton(stringResource(R.string.removable_card_open), { c.removable.open() }, style = KinButtonStyle.Secondary)
        }
    }
}

/** Owner PIN (product spec §5): set, change or remove it here; the owner can also remove it remotely. */
@Composable
private fun OwnerPinSection(c: FrameController, s: FrameSettings, onChoose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val set = s.kiosk.ownerPinHash != null
    SectionCard(stringResource(R.string.settings_pin)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KinIcon(KinIcons.Lock, null, tint = if (set) Kin.colors.sage else Kin.colors.inkFaint)
            Text(
                stringResource(if (set) R.string.settings_pin_on else R.string.settings_pin_off),
                style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(start = 14.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KinButton(stringResource(if (set) R.string.settings_pin_change else R.string.settings_pin_set), onChoose, style = KinButtonStyle.Secondary)
            if (set) KinButton(stringResource(R.string.settings_pin_remove), { scope.launch { c.setOwnerPin(null) } }, style = KinButtonStyle.Quiet)
        }
    }
}

@Composable
private fun UpdatesSection(c: FrameController, s: FrameSettings, section: (String, Array<out Pair<String, Any?>>) -> Unit) {
    val status by c.update.status.collectAsState()
    SectionCard(stringResource(R.string.settings_updates)) {
        Labeled(stringResource(R.string.updates_channel)) {
            ChoiceChips(
                listOf("stable" to stringResource(R.string.updates_channel_stable), "beta" to stringResource(R.string.updates_channel_beta)),
                if (s.updates.channel == "beta") "beta" else "stable",
                { section("updates", arrayOf("channel" to it)) },
            )
        }
        val name = status.versionName.orEmpty()
        val line = when (status.phase) {
            UpdateManager.Phase.Idle -> null
            UpdateManager.Phase.Checking -> stringResource(R.string.updates_checking)
            UpdateManager.Phase.UpToDate -> stringResource(R.string.updates_up_to_date)
            UpdateManager.Phase.Downloading -> stringResource(R.string.updates_downloading, name, ((status.progress ?: 0f) * 100).toInt())
            UpdateManager.Phase.Waiting -> stringResource(R.string.updates_waiting, name)
            UpdateManager.Phase.Installing -> stringResource(R.string.updates_installing, name)
            UpdateManager.Phase.NeedsUser -> stringResource(R.string.update_notice_needs_user)
            UpdateManager.Phase.Failed -> stringResource(R.string.updates_failed)
        }
        if (line != null) Text(line, style = Kin.type.body, color = Kin.colors.inkSoft)
        val busy = status.phase in setOf(UpdateManager.Phase.Checking, UpdateManager.Phase.Downloading, UpdateManager.Phase.Installing)
        KinButton(
            stringResource(if (status.phase == UpdateManager.Phase.Waiting) R.string.updates_install_now else R.string.updates_check),
            { c.update.requestCheck(UpdateManager.Trigger.User) },
            style = KinButtonStyle.Secondary, enabled = !busy,
        )
    }
}

/** The frame's language: the current one, and all 36 to pick from. A choice applies at once and syncs. */
@Composable
private fun LanguageSection(c: FrameController) {
    val context = LocalContext.current
    val current = remember { Locales.effective(context) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(stringResource(R.string.settings_language)) {
        if (expanded) {
            LanguageChoices(current, { code -> expanded = false; c.setLanguage(code) })
        } else {
            KinCard(Modifier.fillMaxWidth(), onClick = { expanded = true }, padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KinIcon(KinIcons.Globe, null, tint = Kin.colors.accent)
                    Text(
                        Locales.language(current)?.nativeName ?: current, style = Kin.type.bodyStrong,
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                    )
                    KinIcon(KinIcons.ChevronRight, null, tint = Kin.colors.inkSoft, size = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun AboutSection(c: FrameController) {
    val context = LocalContext.current
    val net by c.g.network.status.collectAsState()
    val sync by c.syncState.collectAsState()
    SectionCard(stringResource(R.string.settings_about)) {
        InfoRow(stringResource(R.string.about_frame), sync?.frameName ?: stringResource(R.string.frame_default_name))
        InfoRow(stringResource(R.string.about_server), c.g.serverDisplayName())
        InfoRow(
            stringResource(R.string.about_wifi),
            if (net.connected) "${net.ssid ?: stringResource(R.string.wifi_network)} · ${signalWord(net.level)}" else stringResource(R.string.wifi_not_connected),
        )
        InfoRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KinButton(stringResource(R.string.wifi_open_settings), {
                (context as? Activity)?.let { a ->
                    c.gate.authorize(OwnerGate.Reason.Wifi) {
                        runCatching { a.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }, style = KinButtonStyle.Secondary)
            KinButton(stringResource(R.string.about_android_settings), { (context as? Activity)?.let { c.openAndroidSettings(it) } }, style = KinButtonStyle.Quiet)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.widthIn(min = 200.dp))
        Text(value, style = Kin.type.body)
    }
}

@Suppress("unused")
private fun JsonPrimitive.unused() = this
