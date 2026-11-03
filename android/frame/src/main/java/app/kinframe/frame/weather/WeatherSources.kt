package app.kinframe.frame.weather

import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.MergePatch
import app.kinframe.frame.plugins.FramePlugins
import app.kinframe.frame.plugins.InstanceConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The frame's two weather sources and how they share the screen (docs/plugins.md §9.4): the forecast (Open-Meteo,
 * for the frame's location, next to the clock) and the owner's own weather station (a widget). Each has its own
 * switch; with both on the temperature is shown once, the station's, in the clock's weather block (with the
 * forecast's sky and low/high), and the station widget leaves its own temperature out.
 */
object WeatherSources {
    /** The setting the host passes to the station widget while the forecast shows the temperature. */
    const val HIDE_TEMP = "hide_temp"

    /** The merge patch the frame sends (`PATCH /v1/device/settings`) when its *Weather forecast* switch changes. */
    fun forecastPatch(on: Boolean): JsonObject = MergePatch.field("widgets", "weather", on)

    /** The forecast weather is on and has a place to be about. */
    fun forecastOn(s: FrameSettings): Boolean = s.widgets.weather && s.location != null

    /** The instances as the plugin host should run them: station widgets without their temperature while the forecast is on. */
    fun forHost(list: List<InstanceConfig>, forecastOn: Boolean): List<InstanceConfig> = list.map { cfg ->
        if (cfg.pluginId != FramePlugins.WEATHER_STATION) cfg
        else if (forecastOn) cfg.copy(settings = JsonObject(cfg.settings + (HIDE_TEMP to JsonPrimitive(true))))
        else cfg.copy(settings = JsonObject(cfg.settings - HIDE_TEMP))
    }
}
