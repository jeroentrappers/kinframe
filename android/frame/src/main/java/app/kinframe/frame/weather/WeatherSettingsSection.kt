package app.kinframe.frame.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.design.Kin
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.plugins.PluginLayoutMode
import app.kinframe.frame.plugins.WeatherStationSettings
import app.kinframe.frame.plugins.stationConfig
import app.kinframe.frame.ui.SectionCard
import app.kinframe.frame.ui.SwitchRow
import kotlinx.serialization.json.JsonObject

/**
 * The frame's two weather sources side by side, each with its own switch (docs/plugins.md §9.4): the forecast from
 * the internet for the frame's location (`widgets.weather`, synced like every setting) and the owner's own weather
 * station (the built-in widget, a frame-local plugin instance). With both on the temperature appears once
 * ([WeatherSources]).
 */
@Composable
fun WeatherSettingsSection(c: FrameController, s: FrameSettings, patch: (JsonObject) -> Unit) {
    val prefs = c.plugins.prefs
    val instances by prefs.instances.collectAsState()
    val layout by prefs.layout.collectAsState()
    val station = stationConfig(instances)
    val hasPlace = s.location != null
    SectionCard(stringResource(R.string.wx_section)) {
        WeatherSwitches(
            forecast = s.widgets.weather, hasPlace = hasPlace, station = station.enabled,
            onForecast = { patch(WeatherSources.forecastPatch(it)) },
            onStation = { on ->
                prefs.upsert(station.copy(enabled = on))
                // A widget needs somewhere to show: the band, unless the owner picked the corners before.
                if (on && layout.mode == PluginLayoutMode.Off) prefs.setLayout(layout.copy(mode = PluginLayoutMode.Band))
            },
        )
        if (station.enabled) WeatherStationSettings(c, station) { prefs.upsert(it) }
    }
}

/** The two switches, and the note on how they share the screen when both are on. */
@Composable
internal fun WeatherSwitches(forecast: Boolean, hasPlace: Boolean, station: Boolean, onForecast: (Boolean) -> Unit, onStation: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SwitchRow(
            stringResource(R.string.wx_forecast), forecast && hasPlace,
            subtitle = stringResource(if (hasPlace) R.string.wx_forecast_sub else R.string.wx_forecast_no_location),
            enabled = hasPlace, onChange = onForecast,
        )
        SwitchRow(stringResource(R.string.wx_station), station, subtitle = stringResource(R.string.wx_station_sub), onChange = onStation)
        if (station && forecast && hasPlace) {
            Text(stringResource(R.string.wx_both_note), style = Kin.type.caption, color = Kin.colors.inkSoft)
        }
    }
}
