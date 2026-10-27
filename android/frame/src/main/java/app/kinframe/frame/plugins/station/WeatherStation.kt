package app.kinframe.frame.plugins.station

import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.StackDirection
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.Trend
import app.kinframe.core.plugin.ViewJson
import app.kinframe.core.plugin.ViewLimits
import app.kinframe.frame.plugins.PluginContext
import app.kinframe.frame.plugins.PluginInstance
import app.kinframe.frame.plugins.PluginStore
import app.kinframe.frame.weather.Units
import app.kinframe.frame.weather.WeatherSources
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The built-in weather station widget (docs/plugins.md §9.4), written against [PluginInstance] like the HomeWizard
 * widget so a JavaScript plugin (`kinframe.weather-station`) can replace it.
 *
 * Settings: `source` ("upload": the station uploads to kinframe), `station_id` and the secret `station_key` (what
 * the station uploads with), `wind_unit` ("auto", "ms", "kmh", "mph", "bft") and `style` ("compact" or "rows"). The
 * host adds `hide_temp` while the forecast weather is on: the clock's weather block then shows the station's
 * temperature, so the widget leaves it out.
 */
class WeatherStation(
    settings: JsonObject,
    /** Every good reading: the clock's weather block shows the station's temperature while the forecast is on. */
    private val onReading: (StationReading) -> Unit = {},
    /** Where the frame's server answers `GET /v1/stations/latest`. */
    private val sourceFor: (JsonObject) -> StationSource?,
) : PluginInstance {
    override val refreshSeconds: Int = REFRESH_S
    override val staleAfterSeconds: Int = STALE_S

    override suspend fun render(ctx: PluginContext): JsonElement? {
        val source = sourceFor(ctx.settings) ?: return null
        val st = StationState.of(ctx)
        val r = source.read(ctx.http)
        st.last = r
        if (st.history.add(r)) st.history.save(ctx.store)
        ctx.dataTime = r.atMs ?: ctx.now()
        onReading(r)
        val unit = WindUnit.of(ctx.settings)
        val withTemp = setting(ctx.settings, WeatherSources.HIDE_TEMP) != "true"
        return when (setting(ctx.settings, "style")) {
            "rows" -> StationViews.rows(r, st.history.tempTrend(), st.history.pressureTrend(), unit, ctx.format(), ctx.t, withTemp)
            else -> StationViews.compact(r, st.history.tempTrend(), unit, ctx.format(), ctx.t, withTemp)
        }
    }

    override suspend fun detail(ctx: PluginContext): JsonElement? {
        val st = StationState.of(ctx)
        val r = st.last ?: return null
        // The station's place changes rarely: asked once a day at most, and only when someone opens the card.
        if (ctx.now() - st.infoAt > INFO_EVERY_MS) {
            st.infoAt = ctx.now()
            sourceFor(ctx.settings)?.let { s -> runCatching { s.info(ctx.http) }.getOrNull()?.let { st.info = it } }
        }
        return StationViews.detail(r, st.info, st.history, WindUnit.of(ctx.settings), ctx.format(), ctx.t, ctx.now())
    }

    companion object {
        /** A reading older than this greys the widget (stations upload about every 10 minutes). */
        const val STALE_S = 30 * 60
        /** Stations upload every 16 s to a minute; once a minute is plenty for a wall display. */
        const val REFRESH_S = 60
        private const val INFO_EVERY_MS = 24 * 3_600_000L

        /** The station's source from the settings: uploads read back from [latest] on the frame's own server. */
        fun sourceFor(settings: JsonObject, latest: HttpUrl): StationSource? {
            if ((setting(settings, "source") ?: UploadSource.ID) != UploadSource.ID) return null
            val id = setting(settings, "station_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val key = setting(settings, "station_key")?.takeIf { it.isNotEmpty() } ?: return null
            return UploadSource(latest, id, key)
        }

        internal fun setting(settings: JsonObject, key: String) = (settings[key] as? JsonPrimitive)?.contentOrNull
    }
}

// ---------------------------------------------------------------------------------------------- units

enum class WindUnit(val json: String) {
    /** km/h, or mph when the frame shows °F (as the forecast panel does). */
    Auto("auto"), Ms("ms"), Kmh("kmh"), Mph("mph"), Bft("bft");

    companion object {
        fun of(json: String?): WindUnit = entries.firstOrNull { it.json == json } ?: Auto
        fun of(settings: JsonObject): WindUnit = of(WeatherStation.setting(settings, "wind_unit"))
    }
}

object StationUnits {
    /** Lower bounds of Beaufort 1–12 in m/s (WMO). */
    private val BEAUFORT = doubleArrayOf(0.5, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)

    fun beaufort(ms: Double): Int {
        val v = (ms * 10).roundToInt() / 10.0 // stations report one decimal; 1.55 is 1.6
        var b = 0
        while (b < BEAUFORT.size && v >= BEAUFORT[b]) b++
        return b
    }

    fun resolve(unit: WindUnit, temperatureUnit: String): WindUnit =
        if (unit != WindUnit.Auto) unit else if (Units.fahrenheit(temperatureUnit)) WindUnit.Mph else WindUnit.Kmh

    /** "2,3 m/s", "8 km/h", "5 mph", "2 Bft". */
    fun wind(ms: Double, unit: WindUnit, f: PluginFormat): String = when (resolve(unit, f.temperatureUnit)) {
        WindUnit.Ms -> f.number(ms, 1, "m/s")
        WindUnit.Mph -> f.number(ms * 2.2369363, 0, "mph")
        WindUnit.Bft -> f.number(beaufort(ms).toDouble(), 0, "Bft")
        else -> f.number(ms * 3.6, 0, "km/h")
    }

    /** Compass sector 0–7 (N, NE … NW) of a direction in degrees. */
    fun sector(deg: Double): Int = Units.compassSector(deg.roundToInt())
}

// ---------------------------------------------------------------------------------------------- history

/**
 * The last 26 hours of temperature and pressure, one sample per 5 minutes at most, kept in `ctx.store` (a few KB) so
 * the trends and the detail card's 24 h charts survive a restart.
 */
class StationHistory {
    /** Epoch seconds; temperature °C and pressure hPa, NaN when the reading had none. */
    private val at = ArrayList<Long>()
    private val temp = ArrayList<Float>()
    private val hpa = ArrayList<Float>()

    val size: Int get() = at.size

    /** Adds [r] if it is a new measurement; false when it is the same as (or older than) the last one. */
    fun add(r: StationReading): Boolean {
        val s = (r.atMs ?: return false) / 1000
        if (r.tempC == null && r.pressureHpa == null) return false
        val last = at.lastOrNull()
        if (last != null && s - last < MIN_STEP_S) return false
        at += s; temp += r.tempC?.toFloat() ?: Float.NaN; hpa += r.pressureHpa?.toFloat() ?: Float.NaN
        val cutoff = s - KEEP_S
        var drop = 0
        while (drop < at.size && at[drop] < cutoff) drop++
        if (drop > 0) { at.subList(0, drop).clear(); temp.subList(0, drop).clear(); hpa.subList(0, drop).clear() }
        return true
    }

    /** Temperature over the last hour: up or down by 0.5 °C or more, else null (steady needs no arrow). */
    fun tempTrend(): Trend? {
        val d = delta(temp, HOUR_S, tolerance = 20 * 60L) ?: return null
        return when {
            d >= 0.5f -> Trend.Up
            d <= -0.5f -> Trend.Down
            else -> null
        }
    }

    /** The barometric tendency over 3 hours: rising or falling by 1 hPa or more, else steady. */
    fun pressureTrend(): Trend? {
        val d = delta(hpa, 3 * HOUR_S, tolerance = 30 * 60L) ?: return null
        return when {
            d >= 1f -> Trend.Up
            d <= -1f -> Trend.Down
            else -> Trend.Flat
        }
    }

    /** The newest value minus the one closest to [span] before it (within [tolerance]). */
    private fun delta(values: List<Float>, span: Long, tolerance: Long): Float? {
        val i = values.indexOfLast { !it.isNaN() }.takeIf { it >= 0 } ?: return null
        val target = at[i] - span
        var best = -1
        for (k in 0 until i) {
            if (values[k].isNaN()) continue
            if (abs(at[k] - target) <= tolerance && (best < 0 || abs(at[k] - target) < abs(at[best] - target))) best = k
        }
        if (best < 0) return null
        return values[i] - values[best]
    }

    fun tempSeries(endS: Long) = series(temp, endS)
    fun pressureSeries(endS: Long) = series(hpa, endS)

    /** The last 24 hours up to [endS] in 96 buckets of 15 minutes, averaged; null where there was no reading. */
    private fun series(values: List<Float>, endS: Long): List<Float?> {
        val start = endS - DAY_S
        val sums = FloatArray(BUCKETS); val ns = IntArray(BUCKETS)
        for (k in at.indices) {
            val v = values[k]
            if (v.isNaN() || at[k] <= start || at[k] > endS) continue
            val b = (((at[k] - start).toDouble() / DAY_S) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            sums[b] += v; ns[b]++
        }
        return (0 until BUCKETS).map { if (ns[it] == 0) null else sums[it] / ns[it] }
    }

    fun encode(): String = at.indices.joinToString(";") { k -> "${at[k]},${enc(temp[k])},${enc(hpa[k])}" }

    fun save(store: PluginStore) { runCatching { store.put(KEY, encode()) } }

    private fun enc(v: Float) = if (v.isNaN()) "" else ((v * 10).roundToInt() / 10f).toString()

    companion object {
        const val KEY = "history"
        private const val MIN_STEP_S = 4 * 60L
        private const val HOUR_S = 3600L
        private const val DAY_S = 24 * HOUR_S
        private const val KEEP_S = 26 * HOUR_S
        const val BUCKETS = 96

        fun decode(s: String?): StationHistory {
            val h = StationHistory()
            s?.split(';')?.forEach { part ->
                val p = part.split(',')
                if (p.size != 3) return@forEach
                val t = p[0].toLongOrNull() ?: return@forEach
                if (h.at.isNotEmpty() && t <= h.at.last()) return@forEach
                h.at += t; h.temp += p[1].toFloatOrNull() ?: Float.NaN; h.hpa += p[2].toFloatOrNull() ?: Float.NaN
            }
            return h
        }
    }
}

/** What the instance remembers between calls (`ctx.state`); the history is restored from `ctx.store`. */
internal class StationState(val history: StationHistory) {
    var last: StationReading? = null
    var info: StationInfo? = null
    var infoAt: Long = Long.MIN_VALUE / 2

    companion object {
        fun of(ctx: PluginContext): StationState =
            ctx.state.getOrPut("station") { StationState(StationHistory.decode(runCatching { ctx.store.get(StationHistory.KEY) }.getOrNull())) } as StationState
    }
}

// ---------------------------------------------------------------------------------------------- views

/**
 * The widget's views. Keys for `t`: temperature, humidity, dew_point, wind, gusts, rain_today, pressure, sunlight,
 * uv_index, updated_now, updated_ago (%1$s = "4 min"), no_reading_since (%1$s = "13:05"), feels_like (%1$s = "13°"),
 * compass_0 … compass_7.
 */
object StationViews {
    /**
     * Short (the *Temperature* style): the temperature with today's low and high, then the wind and, while it rains,
     * the rain, else the humidity. Without the temperature (the clock shows it) the wind leads.
     */
    fun compact(r: StationReading, trend: Trend?, unit: WindUnit, f: PluginFormat, t: (String) -> String, withTemp: Boolean = true): JsonObject {
        val second = raining(r, f) ?: r.humidityPct?.let { f.percent(it) }
        if (!withTemp) {
            val w = wind(r, unit, f, t, withGusts = false, label = second)
            return w ?: r.humidityPct?.let { ViewJson.value(f.percent(it), label = fit(t("humidity")), icon = "water") } ?: ViewJson.value("–", icon = "wind")
        }
        val line = listOfNotNull(range(r, f), windShort(r, unit, f, t), second).joinToString(" · ")
        return ViewJson.value(temp(r.tempC, f), label = fit(line).ifEmpty { null }, icon = "thermometer", trend = trend)
    }

    /**
     * Everything that matters now (the *All values* style): the temperature on top, then at most four rows picked by
     * what is worth saying: the wind always, rain only on a day it rained, the humidity, the pressure only while it
     * rises or falls, UV only from 3 up. The sunlight in W/m² and the dew point are in the detail card.
     */
    fun rows(
        r: StationReading, tempTrend: Trend?, pressureTrend: Trend?, unit: WindUnit, f: PluginFormat, t: (String) -> String,
        withTemp: Boolean = true,
    ): JsonObject {
        val list = listOfNotNull(
            wind(r, unit, f, t),
            rain(r, f, t, onlyIfAny = true),
            humidity(r, f, t, withDew = false),
            pressure(r, pressureTrend, f, t, onlyIfMoving = true),
            uv(r, f, t),
        ).take(ROWS)
        val head = if (withTemp && r.tempC != null) {
            val label = listOfNotNull(range(r, f), feelsLike(r, f, t)).joinToString(" · ").ifEmpty { null }
            ViewJson.value(temp(r.tempC, f), label = label?.let { fit(it) }, icon = "thermometer", trend = tempTrend)
        } else null
        return when {
            head != null && list.isNotEmpty() -> ViewJson.stack(listOf(head, ViewJson.rows(list)))
            head != null -> head
            list.isNotEmpty() -> ViewJson.rows(list)
            else -> ViewJson.value("–", icon = "thermometer")
        }
    }

    fun detail(r: StationReading, info: StationInfo?, h: StationHistory, unit: WindUnit, f: PluginFormat, t: (String) -> String, nowMs: Long): JsonObject {
        val place = listOfNotNull(info?.city, info?.altitudeM?.let { f.number(it, 0, "m") }).joinToString(" · ").ifEmpty { null }
        val headLabel = listOfNotNull(range(r, f), place).joinToString(" · ").ifEmpty { null }
        val head = ViewJson.value(temp(r.tempC, f), label = headLabel?.let { fit(it) }, icon = "thermometer", trend = h.tempTrend())
        val lines = listOfNotNull(updated(r.atMs, nowMs, f, t), feelsLike(r, f, t)).map { fit(it, ViewLimits.MAX_TEXT_LINE) }
        val items = mutableListOf<JsonElement>(
            if (lines.isEmpty()) head else ViewJson.stack(listOf(head, ViewJson.text(lines)), StackDirection.Horizontal),
        )
        val end = (r.atMs ?: nowMs) / 1000
        val charts = listOfNotNull(
            h.tempSeries(end).takeIf { s -> s.count { it != null } >= 2 }?.let {
                ViewJson.sparkline(it, label = fit("${t("temperature")} · 24 h"), showRange = true, tone = Tone.Heat)
            },
            h.pressureSeries(end).takeIf { s -> s.count { it != null } >= 2 }?.let {
                ViewJson.sparkline(it, label = fit("${t("pressure")} · 24 h"), showRange = true, tone = Tone.Info)
            },
        )
        if (charts.isNotEmpty()) items += ViewJson.stack(charts, StackDirection.Horizontal)
        // Two tables, side by side in a wide card: the air on one side, rain, pressure and sun on the other.
        val air = listOfNotNull(
            humidity(r, f, t, withDew = false),
            r.dewPointC?.let { ViewJson.value(f.temperature(it, decimals = 1), label = fit(t("dew_point").replaceFirstChar { c -> c.titlecase() }), icon = "water") },
            wind(r, unit, f, t),
        )
        val rest = listOfNotNull(rain(r, f, t), pressure(r, h.pressureTrend(), f, t), sun(r, f, t))
        if (air.isNotEmpty()) items += ViewJson.rows(air)
        if (rest.isNotEmpty()) items += ViewJson.rows(rest)
        return ViewJson.stack(items.take(ViewLimits.MAX_STACK_ITEMS))
    }

    private const val ROWS = 4

    /** "↓6,1° ↑12,4°": today's low and high, when the server sent them and they differ from now. */
    private fun range(r: StationReading, f: PluginFormat): String? {
        val lo = r.todayMinC ?: return null
        val hi = r.todayMaxC ?: return null
        if (hi - lo < 0.5) return null
        return "↓${f.temperature(lo)} ↑${f.temperature(hi)}"
    }

    private fun feelsLike(r: StationReading, f: PluginFormat, t: (String) -> String): String? =
        r.feelsLikeC?.takeIf { r.tempC != null && abs(it - r.tempC) >= 1.0 }?.let { arg(t("feels_like"), f.temperature(it)) }

    /** "2,0 mm/h" while it rains, else null. */
    private fun raining(r: StationReading, f: PluginFormat): String? = r.rainRateMmH?.takeIf { it >= 0.1 }?.let { f.number(it, 1, "mm/h") }

    // ------------------------------------------------------------------ pieces

    private fun temp(c: Double?, f: PluginFormat) = c?.let { f.temperature(it, decimals = 1) } ?: "–"

    private fun humidity(r: StationReading, f: PluginFormat, t: (String) -> String, withDew: Boolean = true): JsonObject? {
        val h = r.humidityPct ?: return null
        val dew = r.dewPointC?.takeIf { withDew }?.let { "${t("dew_point")} ${f.temperature(it, decimals = 1)}" }
        return ViewJson.value(f.percent(h), label = fit(listOfNotNull(t("humidity"), dew).joinToString(" · ")), icon = "water", tone = Tone.Water)
    }

    /** "ZW 8 km/h" (current wind, else the 10-minute average); no direction in calm air. */
    private fun windShort(r: StationReading, unit: WindUnit, f: PluginFormat, t: (String) -> String): String? {
        val ms = r.windMs ?: r.windAvgMs ?: return null
        val dir = direction(r)?.takeIf { ms >= CALM_MS }?.let { t("compass_${StationUnits.sector(it)}") }
        return listOfNotNull(dir, StationUnits.wind(ms, unit, f)).joinToString(" ")
    }

    private fun direction(r: StationReading) = if (r.windMs != null) r.windDirDeg ?: r.windAvgDirDeg else r.windAvgDirDeg ?: r.windDirDeg

    /**
     * Speed, with an arrow pointing where the wind blows to (the station reports where it comes from), the compass
     * point and gusts when they are clearly stronger ([label] replaces that line).
     */
    private fun wind(r: StationReading, unit: WindUnit, f: PluginFormat, t: (String) -> String, withGusts: Boolean = true, label: String? = null): JsonObject? {
        val ms = r.windMs ?: r.windAvgMs ?: return null
        val dir = direction(r)?.takeIf { ms >= CALM_MS }
        val line = label ?: listOfNotNull(
            dir?.let { t("compass_${StationUnits.sector(it)}") } ?: t("wind"),
            r.gustMs?.takeIf { withGusts && it >= ms + GUSTY_MS }?.let { "${t("gusts")} ${StationUnits.wind(it, unit, f)}" },
        ).joinToString(" · ")
        return ViewJson.value(
            StationUnits.wind(ms, unit, f), label = fit(line),
            icon = if (dir != null) "direction" else "wind", iconRotation = dir?.let { ((it + 180.0) % 360.0).toFloat() },
        )
    }

    /** Today's rain (with the rate while it rains); with [onlyIfAny] nothing on a dry day. */
    private fun rain(r: StationReading, f: PluginFormat, t: (String) -> String, onlyIfAny: Boolean = false): JsonObject? {
        val mm = r.rainTodayMm ?: return null
        val rate = raining(r, f)
        if (onlyIfAny && mm < 0.1 && rate == null) return null
        return ViewJson.value(f.number(mm, 1, "mm"), label = fit(listOfNotNull(t("rain_today"), rate).joinToString(" · ")), icon = "rain", tone = Tone.Water)
    }

    /** With [onlyIfMoving] only while it rises or falls (1 hPa in 3 hours). */
    private fun pressure(r: StationReading, trend: Trend?, f: PluginFormat, t: (String) -> String, onlyIfMoving: Boolean = false): JsonObject? {
        val p = r.pressureHpa ?: return null
        if (onlyIfMoving && trend != Trend.Up && trend != Trend.Down) return null
        return ViewJson.value(f.number(p, 0, "hPa"), label = fit(t("pressure")), icon = "meter", trend = trend)
    }

    /** UV from 3 up (where it starts to matter), highlighted from 6. */
    private fun uv(r: StationReading, f: PluginFormat, t: (String) -> String): JsonObject? {
        val u = r.uvIndex?.takeIf { it >= 3.0 } ?: return null
        return ViewJson.value(f.number(u, 0), label = fit(t("uv_index")), icon = "sun", tone = if (u >= 6.0) Tone.Warn else Tone.Neutral)
    }

    /** Sunlight and UV, for the detail card. */
    private fun sun(r: StationReading, f: PluginFormat, t: (String) -> String): JsonObject? {
        val uv = r.uvIndex?.let { "UV ${f.number(it, 0)}" }
        r.solarWm2?.let { w ->
            return ViewJson.value(f.number(w, 0, "W/m²"), label = fit(listOfNotNull(t("sunlight"), uv).joinToString(" · ")), icon = "sun")
        }
        val u = r.uvIndex ?: return null
        return ViewJson.value(f.number(u, 0), label = fit(t("uv_index")), icon = "sun")
    }

    /** "Updated 4 min ago", or "No new reading since 13:05" once the station is quiet for 30 minutes. */
    fun updated(atMs: Long?, nowMs: Long, f: PluginFormat, t: (String) -> String): String? {
        val at = atMs ?: return null
        val age = ((nowMs - at) / 1000).coerceAtLeast(0)
        return when {
            age >= WeatherStation.STALE_S -> arg(t("no_reading_since"), f.time(at))
            age < 60 -> t("updated_now")
            else -> arg(t("updated_ago"), f.duration(age))
        }
    }

    private fun arg(template: String, value: String) = if (template.contains("%1\$s")) template.replace("%1\$s", value) else "$template $value"

    /** Translations can be longer than English: cut to the view limit instead of failing validation. */
    fun fit(s: String, max: Int = ViewLimits.MAX_LABEL): String {
        if (s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }

    private const val CALM_MS = 0.5
    /** Gusts are worth mentioning from about 9 km/h above the wind. */
    private const val GUSTY_MS = 2.5
}
