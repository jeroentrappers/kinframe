package app.kinframe.frame.plugins

import android.util.Log
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import app.kinframe.frame.Locales
import app.kinframe.frame.R
import app.kinframe.frame.plugins.js.PluginBundle
import app.kinframe.frame.plugins.js.PluginLibraries
import app.kinframe.frame.plugins.js.PluginNet
import app.kinframe.frame.plugins.js.PluginPermissions
import app.kinframe.frame.plugins.js.QuickJsPluginInstance
import app.kinframe.frame.plugins.station.WeatherStation
import app.kinframe.frame.schedule.ScreenMode
import app.kinframe.frame.weather.WeatherSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The frame's plugin feature (docs/plugins.md §10): JavaScript plugins in the QuickJS host (step 2; for now only the
 * bundles built into the APK) and the remaining Kotlin built-ins, configured on the frame. Owns the [PluginHost] and
 * starts instances when the slideshow is up and the screen is awake.
 */
class FramePlugins(private val c: FrameController) {
    private val g = c.g
    val prefs = PluginPrefs(g.app)

    /** Kotlin widgets that aren't JavaScript plugins yet. Everything else comes from a bundle ([bundle]). */
    private val builtIns: Map<String, (InstanceConfig) -> PluginInstance> = buildMap {
        put(WEATHER_STATION) { cfg ->
            WeatherStation(cfg.settings, onReading = { r ->
                val t = r.tempC
                if (t != null) _stationNow.value = StationNow(t, r.atMs ?: g.clock.nowMs())
            })
        }
        if (BuildConfig.DEBUG) put(DEMO) { _ -> DemoFixtures(g.app) }
    }

    val host = PluginHost(
        scope = c.scope,
        now = { g.clock.nowMs() },
        contextFor = { cfg, invalidate -> contextFor(cfg, invalidate) },
        factory = { cfg -> (builtIns[cfg.pluginId]?.invoke(cfg) ?: script(cfg))?.let { it to nameOf(cfg.pluginId) } },
    )

    private val bundles = HashMap<String, PluginBundle?>()
    private val prelude: String by lazy { g.app.assets.open("plugin-host/prelude.js").bufferedReader().use { it.readText() } }
    private val libraries: PluginLibraries by lazy { PluginLibraries.fromAssets(g.app.assets) }

    /** A plugin bundle shipped in the APK (assets `plugins/<id>/`), or null. A broken bundle is logged and skipped. */
    fun bundle(pluginId: String): PluginBundle? = synchronized(bundles) {
        bundles.getOrPut(pluginId) {
            runCatching { PluginBundle.fromAssets(g.app.assets, pluginId) }
                .onFailure { Log.w("KinFramePlugins", "bundle $pluginId: ${it.message}") }.getOrNull()
        }
    }

    private fun script(cfg: InstanceConfig): PluginInstance? {
        val b = bundle(cfg.pluginId) ?: return null
        val net = PluginNet(g.http, PluginPermissions(b.manifest, b.manifest.withDefaults(cfg.settings)), userAgent = USER_AGENT)
        return QuickJsPluginInstance(b, prelude, net, libraries = libraries)
    }

    val states: StateFlow<List<InstanceState>> get() = host.states
    val layout: StateFlow<PluginLayoutConfig> get() = prefs.layout

    /** Set by the slideshow while it is on screen. */
    val slideshowUp = MutableStateFlow(false)

    private val _stationNow = MutableStateFlow<StationNow?>(null)

    /**
     * The weather station's latest temperature while its widget runs; null otherwise. With the forecast on too, the
     * clock's weather block shows it instead of the forecast service's, so the temperature appears once (§9.4).
     */
    val stationNow: StateFlow<StationNow?> = _stationNow

    private var started = false

    fun start() {
        if (started) return
        started = true
        c.scope.launch {
            val forecastOn = g.settings.settings.map { WeatherSources.forecastOn(it) }.distinctUntilChanged()
            combine(prefs.instances, prefs.layout, forecastOn) { list, l, fc -> if (l.mode == PluginLayoutMode.Off) emptyList() else WeatherSources.forHost(list, fc) }
                .distinctUntilChanged().collect { host.apply(it) }
        }
        c.scope.launch {
            combine(prefs.instances, prefs.layout) { list, l -> l.mode != PluginLayoutMode.Off && list.any { it.enabled && it.pluginId == WEATHER_STATION } }
                .distinctUntilChanged().collect { on -> if (!on) _stationNow.value = null }
        }
        c.scope.launch {
            combine(slideshowUp, c.display.mode.map { it != ScreenMode.Asleep && it != ScreenMode.FadingOut }, c.settingsOpen) { up, awake, _ -> up to awake }
                .distinctUntilChanged().collect { (up, awake) -> host.setActive(up, awake) }
        }
    }

    fun nameOf(pluginId: String): String = when (pluginId) {
        HOMEWIZARD_P1 -> Locales.wrap(g.app).getString(R.string.p1_name)
        WEATHER_STATION -> Locales.wrap(g.app).getString(R.string.ws_name)
        DEMO -> "Demo"
        else -> bundle(pluginId)?.name(Locales.effective(g.app)) ?: pluginId
    }

    fun format(): PluginFormat {
        val s = g.settings.settings.value
        return PluginFormat(Locale.forLanguageTag(Locales.effective(g.app)), g.clock.zone, s.widgets.clock24h, s.locale.temperatureUnit)
    }

    private fun contextFor(cfg: InstanceConfig, invalidate: () -> Unit): PluginContext {
        // The manifest's `http` permission: the HomeWizard widget reaches the home network only (`local_only`), the
        // weather station one fixed internet host.
        val http = when (cfg.pluginId) {
            WEATHER_STATION -> PluginHttp(g.http, allowed = WeatherStation::allows, localOnly = false, userAgent = USER_AGENT)
            else -> PluginHttp(g.http, allowed = { url -> url.scheme == "http" || url.scheme == "https" }, localOnly = true, userAgent = USER_AGENT)
        }
        return PluginContext(
            instanceId = cfg.instanceId,
            settings = cfg.settings,
            state = HashMap(),
            store = prefs.store(cfg.instanceId),
            http = http,
            format = ::format,
            t = { key -> translate(key) },
            log = { msg -> Log.i("KinFramePlugins", "[${cfg.instanceId}] $msg") },
            now = { g.clock.nowMs() },
            zone = { g.clock.zone },
            invalidate = invalidate,
        )
    }

    private fun translate(key: String): String {
        val res = Locales.wrap(g.app)
        if (key.startsWith("compass_")) {
            val i = key.removePrefix("compass_").toIntOrNull() ?: return key
            return res.resources.getStringArray(R.array.weather_compass).getOrNull(i) ?: key
        }
        val id = when (key) {
            "using" -> R.string.p1_using
            "returning" -> R.string.p1_returning
            "last_hour" -> R.string.p1_last_hour
            "today_import" -> R.string.p1_today_import
            "today_export" -> R.string.p1_today_export
            "today_gas" -> R.string.p1_today_gas
            "grid" -> R.string.p1_grid
            "home" -> R.string.p1_home
            "temperature" -> R.string.ws_temperature
            "humidity" -> R.string.weather_humidity
            "dew_point" -> R.string.ws_dew_point
            "wind" -> R.string.weather_wind
            "gusts" -> R.string.ws_gusts
            "rain_today" -> R.string.ws_rain_today
            "pressure" -> R.string.ws_pressure
            "sunlight" -> R.string.ws_sunlight
            "uv_index" -> R.string.ws_uv_index
            "updated_now" -> R.string.ws_updated_now
            "updated_ago" -> R.string.ws_updated_ago
            "no_reading_since" -> R.string.ws_no_reading_since
            "feels_like" -> R.string.weather_feels_like
            else -> return key
        }
        return res.getString(id)
    }

    companion object {
        const val HOMEWIZARD_P1 = "kinframe.homewizard-p1"
        /** The P1 dial's full scale when the owner hasn't picked one (the plugin's `max_w` default). */
        const val P1_DEFAULT_MAX_W = 4000
        const val P1_INSTANCE = "builtin-homewizard-p1"
        const val WEATHER_STATION = "kinframe.weather-station"
        const val STATION_INSTANCE = "builtin-weather-station"
        val USER_AGENT = "kinframe-frame/${BuildConfig.VERSION_NAME} (+https://github.com/jeroentrappers/kinframe)"

        /** Debug builds: cycles the example views from docs/api/plugin-views (dev preview). */
        const val DEMO = "kinframe.demo"
        const val DEMO_INSTANCE = "debug-demo"
    }
}

/** The weather station's temperature for the weather corner, and when it was measured. */
data class StationNow(val tempC: Double, val atMs: Long)
