package app.kinframe.frame.plugins

import android.os.SystemClock
import android.util.Log
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.RelativeWords
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The frame's plugin feature (docs/plugins.md §10): JavaScript plugins in the QuickJS host (step 2; for now only the
 * bundles built into the APK) and the remaining Kotlin built-ins, configured in the frame settings (§6). Owns the [PluginHost] and
 * starts instances when the slideshow is up and the screen is awake.
 */
class FramePlugins(private val c: FrameController) {
    private val g = c.g
    val prefs = PluginPrefs(g.app, g.settings.raw, c.scope) { patch ->
        val outbox = c.session.value?.outbox ?: return@PluginPrefs false
        c.scope.launch { outbox.settingsPatch(patch) }
        true
    }

    /** Kotlin widgets that aren't JavaScript plugins yet. Everything else comes from a bundle ([bundle]). */
    private val builtIns: Map<String, (InstanceConfig) -> PluginInstance> = buildMap {
        put(WEATHER_STATION) { cfg ->
            WeatherStation(cfg.values, onReading = { r ->
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
        val net = PluginNet(g.http, PluginPermissions(b.manifest, b.manifest.withDefaults(cfg.values)), userAgent = USER_AGENT)
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
        c.scope.launch { c.session.collectLatest { s -> if (s != null) report() } }
        c.scope.launch {
            combine(slideshowUp, c.display.mode.map { it != ScreenMode.Asleep && it != ScreenMode.FadingOut }, c.settingsOpen) { up, awake, _ -> up to awake }
                .distinctUntilChanged().collect { (up, awake) -> host.setActive(up, awake) }
        }
    }

    // ------------------------------------------------------------------------------- reports (docs/plugins.md §6)

    /** The plugin folders in the APK, and in debug builds the demo widget. */
    private fun catalogSources(): List<PluginReports.Source> {
        val a = g.app.assets
        val folders = a.list("plugins").orEmpty().sorted().mapNotNull { id ->
            val manifest = runCatching { a.open("plugins/$id/plugin.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return@mapNotNull null
            val locales = a.list("plugins/$id/locales").orEmpty().filter { it.endsWith(".json") }.associate { f ->
                f.removeSuffix(".json") to a.open("plugins/$id/locales/$f").bufferedReader().use { it.readText() }
            }
            PluginReports.Source(manifest, locales)
        }
        return if (BuildConfig.DEBUG) folders + PluginReports.Source(DEMO_MANIFEST, emptyMap()) else folders
    }

    /**
     * While the frame has a session: moves plugin settings kept on the frame into the frame settings, sends the
     * catalog, and then the status every 10 s when it changed (at least every 10 minutes), browsing the network for
     * the catalog's services for a minute every 10 minutes and listing Home Assistant entities per instance.
     */
    private suspend fun report() = coroutineScope {
        g.settings.raw.first { it.containsKey("version") }
        prefs.migrate()
        val sources = catalogSources()
        runCatching { g.api.putPluginCatalog(PluginReports.catalog(sources)) }
            .onFailure { Log.i("KinFramePlugins", "catalog not sent: ${it.message}") }

        val services = MutableStateFlow<Map<String, List<FoundService>>>(emptyMap())
        val types = PluginReports.serviceTypes(sources.mapNotNull { runCatching { app.kinframe.frame.plugins.js.PluginManifest.parse(it.manifest) }.getOrNull() })
        launch {
            val browser = ServiceBrowser(g.app)
            while (true) {
                for (t in types) withTimeoutOrNull(60_000) { browser.browse(t).collect { list -> services.value = services.value + (t to list) } }
                delay(9 * 60_000L)
            }
        }

        val entities = HashMap<String, Pair<String, Pair<Long, List<PluginReports.Entity>?>>>()
        var sent: JsonObject? = null
        var sentAt = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val states = host.states.value.associateBy { it.config.instanceId }
            val reports = prefs.instances.value.map { cfg ->
                val st = states[cfg.instanceId]
                PluginReports.InstanceReport(
                    instanceId = cfg.instanceId, running = st != null, error = st?.error,
                    updatedAtMs = st?.lastGoodAt?.takeIf { it > 0 }, entities = entitiesFor(cfg, entities, now),
                )
            }
            val compare = PluginReports.status(reports, services.value, withTimes = false)
            if (compare != sent || now - sentAt >= 10 * 60_000L) {
                val ok = runCatching { g.api.putPluginStatus(PluginReports.status(reports, services.value)) }
                    .onFailure { Log.i("KinFramePlugins", "status not sent: ${it.message}") }.isSuccess
                if (ok) { sent = compare; sentAt = now }
            }
            delay(10_000)
        }
    }

    /**
     * The Home Assistant entities an instance can see (§6.2), listed again when its address or token changes and every
     * 10 minutes; null while it has no connection or the last try failed (tried again after a minute).
     */
    private suspend fun entitiesFor(cfg: InstanceConfig, cache: HashMap<String, Pair<String, Pair<Long, List<PluginReports.Entity>?>>>, now: Long): List<PluginReports.Entity>? {
        if (cfg.pluginId in builtIns) return null
        val manifest = bundle(cfg.pluginId)?.manifest ?: return null
        val values = manifest.withDefaults(cfg.values)
        val (base, token) = PluginReports.entityConnection(manifest, values) ?: return null
        val key = PluginReports.connectionKey(base, token)
        val cached = cache[cfg.instanceId]
        if (cached != null && cached.first == key) {
            val (at, list) = cached.second
            if (now - at < if (list != null) 10 * 60_000L else 60_000L) return list
        }
        val net = PluginNet(g.http, PluginPermissions(manifest, values), userAgent = USER_AGENT)
        val list = runCatching { PluginReports.fetchEntities(net, base, token) }
            .onFailure { Log.i("KinFramePlugins", "[${cfg.instanceId}] entities: ${it.message}") }.getOrNull()
        cache[cfg.instanceId] = key to (now to list)
        return list
    }

    fun nameOf(pluginId: String): String = when (pluginId) {
        HOMEWIZARD_P1 -> Locales.wrap(g.app).getString(R.string.p1_name)
        WEATHER_STATION -> Locales.wrap(g.app).getString(R.string.ws_name)
        DEMO -> "Demo"
        else -> bundle(pluginId)?.name(Locales.effective(g.app)) ?: pluginId
    }

    fun format(): PluginFormat {
        val s = g.settings.settings.value
        return PluginFormat(Locale.forLanguageTag(Locales.effective(g.app)), g.clock.zone, s.widgets.clock24h, s.locale.temperatureUnit, relativeWords())
    }

    /** `ctx.format.day/ago/until` in the frame's language. */
    private fun relativeWords(): RelativeWords {
        val r = Locales.wrap(g.app)
        return RelativeWords(
            today = r.getString(R.string.plugin_rel_today), tomorrow = r.getString(R.string.plugin_rel_tomorrow),
            yesterday = r.getString(R.string.plugin_rel_yesterday), justNow = r.getString(R.string.plugin_rel_just_now),
            now = r.getString(R.string.plugin_rel_now), minutesAgo = r.getString(R.string.plugin_rel_min_ago),
            hoursAgo = r.getString(R.string.plugin_rel_h_ago), inMinutes = r.getString(R.string.plugin_rel_in_min),
            inHours = r.getString(R.string.plugin_rel_in_h),
        )
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
            settings = cfg.values,
            state = HashMap(),
            store = prefs.store(cfg.instanceId),
            http = http,
            format = ::format,
            t = { key -> translate(key) },
            log = { msg -> Log.i("KinFramePlugins", "[${cfg.instanceId}] $msg") },
            now = { g.clock.nowMs() },
            zone = { g.clock.zone },
            invalidate = invalidate,
            location = {
                val l = g.settings.settings.value.location
                val lat = l?.lat
                val lon = l?.lon
                if (lat != null && lon != null) lat to lon else null
            },
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
        private const val DEMO_MANIFEST =
            """{"id":"kinframe.demo","name":"Demo","version":"1.0.0","api":1,"settings":[{"key":"fixture","type":"text","label":"Fixture"}]}"""
    }
}

/** The weather station's temperature for the weather corner, and when it was measured. */
data class StationNow(val tempC: Double, val atMs: Long)
