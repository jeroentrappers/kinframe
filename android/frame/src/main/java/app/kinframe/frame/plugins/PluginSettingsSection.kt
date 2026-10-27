package app.kinframe.frame.plugins

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.plugins.homewizard.FoundMeter
import app.kinframe.frame.plugins.homewizard.HomeWizardDiscovery
import app.kinframe.frame.plugins.station.StationCode
import app.kinframe.frame.plugins.station.WeatherStation
import app.kinframe.frame.plugins.station.WeathercloudSource
import app.kinframe.frame.plugins.station.WindUnit
import app.kinframe.frame.ui.Labeled
import app.kinframe.frame.ui.SectionCard
import app.kinframe.frame.ui.SwitchRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Frame-local plugin settings (step 1 of docs/plugins.md §10, before the apps get a Plugins section): the layout and
 * the built-in HomeWizard P1 widget. Part of the frame's Settings screen, so behind the owner PIN.
 */
@Composable
fun PluginSettingsSection(c: FrameController) {
    val prefs = c.plugins.prefs
    val layout by prefs.layout.collectAsState()
    val instances by prefs.instances.collectAsState()
    SectionCard(stringResource(R.string.plugins_title)) {
        Text(stringResource(R.string.plugins_note), style = Kin.type.caption, color = Kin.colors.inkSoft)
        Labeled(stringResource(R.string.plugins_layout)) {
            ChoiceChips(
                listOf(
                    PluginLayoutMode.Off to stringResource(R.string.plugins_layout_off),
                    PluginLayoutMode.Overlay to stringResource(R.string.plugins_layout_overlay),
                    PluginLayoutMode.Band to stringResource(R.string.plugins_layout_band),
                    PluginLayoutMode.Split to stringResource(R.string.plugins_layout_split),
                ),
                layout.mode, { prefs.setLayout(layout.copy(mode = it)) },
            )
        }
        if (layout.mode == PluginLayoutMode.Split) {
            Labeled(stringResource(R.string.plugins_split_side)) {
                ChoiceChips(
                    listOf(BandSide.Left to stringResource(R.string.plugins_side_left), BandSide.Right to stringResource(R.string.plugins_side_right)),
                    layout.side, { prefs.setLayout(layout.copy(side = it)) },
                )
            }
        }
        if (layout.mode == PluginLayoutMode.Band) {
            Labeled(stringResource(R.string.plugins_band_side)) {
                ChoiceChips(
                    listOf(BandSide.Left to stringResource(R.string.plugins_side_left), BandSide.Right to stringResource(R.string.plugins_side_right)),
                    layout.side, { prefs.setLayout(layout.copy(side = it)) },
                )
            }
            Labeled(stringResource(R.string.plugins_band_background)) {
                ChoiceChips(
                    listOf(
                        BandBackground.Paper to stringResource(R.string.plugins_background_paper),
                        BandBackground.Photo to stringResource(R.string.plugins_background_photo),
                    ),
                    layout.background, { prefs.setLayout(layout.copy(background = it)) },
                )
            }
        }
        if (layout.mode != PluginLayoutMode.Off) {
            val p1 = instances.firstOrNull { it.instanceId == FramePlugins.P1_INSTANCE }
                ?: newP1(c)
            SwitchRow(stringResource(R.string.p1_name), p1.enabled) { prefs.upsert(p1.copy(enabled = it)) }
            if (p1.enabled) HomeWizardSettings(c, p1) { prefs.upsert(it) }
            // The weather station is switched on in the Weather section (next to the forecast).
            if (BuildConfig.DEBUG) {
                val demo = instances.firstOrNull { it.instanceId == FramePlugins.DEMO_INSTANCE }
                    ?: InstanceConfig(FramePlugins.DEMO_INSTANCE, FramePlugins.DEMO, slot = Slot.TopLeft, enabled = false)
                SwitchRow("Demo widgets (debug build, example views)", demo.enabled) { prefs.upsert(demo.copy(enabled = it)) }
                if (demo.enabled) InstancePlacement(demo) { prefs.upsert(it) }
            }
        }
    }
}

@Composable
private fun InstancePlacement(cfg: InstanceConfig, update: (InstanceConfig) -> Unit) {
    Labeled(stringResource(R.string.plugins_slot)) {
        ChoiceChips(
            listOf(Slot.TopLeft to stringResource(R.string.plugins_slot_top_left), Slot.TopRight to stringResource(R.string.plugins_slot_top_right)),
            cfg.slot, { update(cfg.copy(slot = it)) },
        )
    }
    Labeled(stringResource(R.string.plugins_visibility)) {
        ChoiceChips(
            listOf(
                Visibility.Always to stringResource(R.string.plugins_visibility_always),
                Visibility.OnChange to stringResource(R.string.plugins_visibility_change),
                Visibility.OnTap to stringResource(R.string.plugins_visibility_tap),
            ),
            cfg.visibility, { update(cfg.copy(visibility = it)) },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun HomeWizardSettings(c: FrameController, cfg: InstanceConfig, update: (InstanceConfig) -> Unit) {
    val context = LocalContext.current
    fun setting(key: String) = (cfg.settings[key] as? JsonPrimitive)?.contentOrNull
    fun withSetting(key: String, value: JsonPrimitive?) =
        cfg.copy(settings = JsonObject(if (value == null) cfg.settings - key else cfg.settings + (key to value)))
    val host = setting("host")

    Labeled(stringResource(R.string.p1_address)) {
        // mDNS: the frame browses for meters while this section is open.
        var found by remember { mutableStateOf<List<FoundMeter>?>(null) }
        LaunchedEffect(Unit) {
            HomeWizardDiscovery(context.applicationContext).browse().collect { list ->
                found = list
                // One meter and nothing chosen yet: take it.
                if (host == null && list.size == 1 && list[0].apiEnabled != false) update(withSetting("host", JsonPrimitive(list[0].address)))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val list = found.orEmpty()
            list.forEach { m ->
                val selected = m.address == host
                KinCard(Modifier.fillMaxWidth(), selected = selected, onClick = { update(withSetting("host", JsonPrimitive(m.address))) }, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KinIcon(KinIcons.Meter, null, tint = if (selected) Kin.colors.accent else Kin.colors.inkSoft)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(m.product?.let { "$it · ${m.name}" } ?: m.name, style = Kin.type.bodyStrong)
                            Text(
                                if (m.apiEnabled == false) "${m.address} · ${stringResource(R.string.p1_api_off)}" else m.address,
                                style = Kin.type.caption, color = if (m.apiEnabled == false) Kin.colors.warn else Kin.colors.inkSoft,
                            )
                        }
                        if (selected) KinIcon(KinIcons.Check, null, tint = Kin.colors.accent)
                    }
                }
            }
            if (list.isEmpty()) {
                Text(
                    stringResource(if (found == null) R.string.p1_searching else R.string.p1_none_found),
                    style = Kin.type.caption, color = Kin.colors.inkSoft,
                )
            }
            ManualAddress(host) { typed -> update(withSetting("host", JsonPrimitive(typed))) }
        }
    }
    InstancePlacement(cfg, update)
    Labeled(stringResource(R.string.p1_style)) {
        ChoiceChips(
            listOf(
                "value" to stringResource(R.string.p1_style_value),
                "gauge" to stringResource(R.string.p1_style_gauge),
                "flow" to stringResource(R.string.p1_style_flow),
            ),
            setting("style") ?: "value", { update(withSetting("style", JsonPrimitive(it))) },
        )
    }
    if (setting("style") == "gauge") Labeled(stringResource(R.string.p1_max)) {
        val f = remember { c.plugins.format() }
        val current = (cfg.settings["max_w"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: FramePlugins.P1_DEFAULT_MAX_W
        ChoiceChips(
            listOf(2000, 3000, 4000, 5000, 6000, 8000, 10000).map { it to f.number(it.toDouble(), unit = "W") },
            current, { update(withSetting("max_w", JsonPrimitive(it))) },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    } else Labeled(stringResource(R.string.p1_warn)) {
        val f = remember { c.plugins.format() }
        val current = (cfg.settings["warn_w"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        ChoiceChips(
            listOf(0 to stringResource(R.string.p1_warn_off)) + listOf(2000, 3000, 5000, 8000).map { it to f.power(it.toDouble()) },
            current, { update(withSetting("warn_w", if (it == 0) null else JsonPrimitive(it))) },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    val states by c.plugins.states.collectAsState()
    val st = states.firstOrNull { it.config.instanceId == cfg.instanceId }
    if (host != null && st != null) {
        val line = when {
            st.status == InstanceStatus.Ok -> stringResource(R.string.p1_status_ok)
            st.error != null -> stringResource(R.string.p1_status_error, st.error)
            else -> stringResource(R.string.p1_status_waiting)
        }
        Text(line, style = Kin.type.body, color = if (st.status == InstanceStatus.Ok) Kin.colors.sage else Kin.colors.inkSoft)
    }
}

@Composable
private fun ManualAddress(current: String?, onUse: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    val dismiss = rememberKeyboardDismiss()
    fun submit() {
        val url = LocalAddress.baseUrl(text)
        // An IP address must be private here; a name (p1meter.local) is checked when it resolves.
        error = url == null || LocalAddress.literalIsPrivate(url.host) == false
        if (!error) { onUse(text.trim()); dismiss() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = false },
            label = { Text(stringResource(R.string.p1_manual)) },
            placeholder = { Text("192.168.1.50") },
            singleLine = true, isError = error,
            supportingText = if (error) ({ Text(stringResource(R.string.p1_not_private)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Kin.colors.accent, focusedLabelColor = Kin.colors.accent, cursorColor = Kin.colors.accent),
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
        )
        KinButton(stringResource(R.string.p1_use), { submit() }, style = KinButtonStyle.Secondary, enabled = text.isNotBlank() && text.trim() != current)
    }
}

/** A new, switched-off P1 instance with the manifest's defaults. */
private fun newP1(c: FrameController): InstanceConfig {
    val settings = JsonObject(mapOf("style" to JsonPrimitive("value")))
    return c.plugins.bundle(FramePlugins.HOMEWIZARD_P1)?.let { newInstanceConfig(FramePlugins.P1_INSTANCE, it.manifest, enabled = false, settings = settings) }
        ?: InstanceConfig(FramePlugins.P1_INSTANCE, FramePlugins.HOMEWIZARD_P1, enabled = false, settings = settings)
}

/** The built-in weather station's instance, or a new disabled one with the defaults. */
fun stationConfig(instances: List<InstanceConfig>): InstanceConfig =
    instances.firstOrNull { it.instanceId == FramePlugins.STATION_INSTANCE }
        ?: InstanceConfig(
            FramePlugins.STATION_INSTANCE, FramePlugins.WEATHER_STATION, slot = Slot.TopLeft, enabled = false,
            settings = JsonObject(mapOf("source" to JsonPrimitive(WeathercloudSource.ID), "style" to JsonPrimitive("compact"))),
        )

/** The built-in weather station (docs/plugins.md §9.4): the station, units, look and how often to ask. */
@Composable
internal fun WeatherStationSettings(c: FrameController, cfg: InstanceConfig, update: (InstanceConfig) -> Unit) {
    fun setting(key: String) = (cfg.settings[key] as? JsonPrimitive)?.contentOrNull
    fun withSetting(key: String, value: JsonPrimitive?) =
        cfg.copy(settings = JsonObject(if (value == null) cfg.settings - key else cfg.settings + (key to value)))
    val code = StationCode.parse(setting("device"))

    Labeled(stringResource(R.string.ws_station)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StationCodeField(code) { update(withSetting("device", JsonPrimitive(it))) }
            Text(stringResource(R.string.ws_unofficial), style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
    }
    InstancePlacement(cfg, update)
    Labeled(stringResource(R.string.p1_style)) {
        ChoiceChips(
            listOf("compact" to stringResource(R.string.ws_style_compact), "rows" to stringResource(R.string.ws_style_rows)),
            setting("style") ?: "compact", { update(withSetting("style", JsonPrimitive(it))) },
        )
    }
    Labeled(stringResource(R.string.ws_wind_unit)) {
        ChoiceChips(
            listOf(WindUnit.Auto to stringResource(R.string.ws_wind_auto), WindUnit.Ms to "m/s", WindUnit.Kmh to "km/h", WindUnit.Bft to "Beaufort"),
            WindUnit.of(setting("wind_unit")), { update(withSetting("wind_unit", if (it == WindUnit.Auto) null else JsonPrimitive(it.json))) },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    Labeled(stringResource(R.string.ws_interval)) {
        val f = remember { c.plugins.format() }
        val current = (cfg.settings["interval_min"] as? JsonPrimitive)?.intOrNull?.coerceIn(WeatherStation.MIN_INTERVAL_MIN, WeatherStation.MAX_INTERVAL_MIN)
            ?: WeatherStation.MIN_INTERVAL_MIN
        ChoiceChips(
            listOf(5, 10, 15, 30).map { it to f.duration(it * 60L) },
            current, { update(withSetting("interval_min", if (it == WeatherStation.MIN_INTERVAL_MIN) null else JsonPrimitive(it))) },
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    val states by c.plugins.states.collectAsState()
    val st = states.firstOrNull { it.config.instanceId == cfg.instanceId }
    if (code != null && st != null) {
        val line = when {
            st.status == InstanceStatus.Ok -> stringResource(R.string.p1_status_ok)
            st.error != null -> stringResource(R.string.ws_status_error, st.error)
            st.status == InstanceStatus.Stale -> stringResource(R.string.ws_status_quiet)
            else -> stringResource(R.string.p1_status_waiting)
        }
        Text(line, style = Kin.type.body, color = if (st.status == InstanceStatus.Ok) Kin.colors.sage else Kin.colors.inkSoft)
    }
}

@Composable
private fun StationCodeField(current: String?, onUse: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    val dismiss = rememberKeyboardDismiss()
    fun submit() {
        val code = StationCode.parse(text)
        error = code == null
        if (code != null) { text = code; onUse(code); dismiss() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = false },
            label = { Text(stringResource(R.string.ws_code)) },
            placeholder = { Text("https://app.weathercloud.net/d1234567890") },
            singleLine = true, isError = error,
            supportingText = { Text(stringResource(if (error) R.string.ws_code_invalid else R.string.ws_code_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Kin.colors.accent, focusedLabelColor = Kin.colors.accent, cursorColor = Kin.colors.accent),
            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp),
        )
        KinButton(stringResource(R.string.p1_use), { submit() }, style = KinButtonStyle.Secondary, enabled = text.isNotBlank() && text.trim() != current)
    }
}

/**
 * Hides the soft keyboard and drops the text field's focus: the frame has no navigation bar, so on the Denver there
 * is no other way to put the keyboard away once a value is entered.
 */
@Composable
internal fun rememberKeyboardDismiss(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return remember(keyboard, focus) { { keyboard?.hide(); focus.clearFocus() } }
}
