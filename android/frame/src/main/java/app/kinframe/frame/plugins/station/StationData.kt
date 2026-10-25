package app.kinframe.frame.plugins.station

import app.kinframe.frame.plugins.PluginHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * One reading of a personal weather station, always metric. Null = the station doesn't report it (no rain gauge, no
 * solar sensor) or sent something implausible.
 */
data class StationReading(
    /** When the station measured this (epoch ms), if the source says so. */
    val atMs: Long?,
    val tempC: Double? = null,
    val humidityPct: Double? = null,
    val dewPointC: Double? = null,
    /** Heat index / feels-like as the station computes it. */
    val feelsLikeC: Double? = null,
    val pressureHpa: Double? = null,
    val windMs: Double? = null,
    /** Where the wind comes **from**, degrees clockwise from north. */
    val windDirDeg: Double? = null,
    val windAvgMs: Double? = null,
    val windAvgDirDeg: Double? = null,
    val gustMs: Double? = null,
    val rainTodayMm: Double? = null,
    val rainRateMmH: Double? = null,
    val solarWm2: Double? = null,
    val uvIndex: Double? = null,
)

/** What a source knows about the station itself (for the detail card). */
data class StationInfo(val city: String?, val altitudeM: Double?)

/**
 * Where readings come from. Weathercloud is the first; the same widget can later read an Ecowitt gateway's local API,
 * the Weather Underground PWS API with the owner's key, or readings a station uploads to the kinframe server ("custom
 * server" upload), each as one more implementation (docs/plugins.md §9.4).
 */
interface StationSource {
    /** Settings value of `source`. */
    val id: String

    /** The hosts this source calls: the plugin's fixed `http` permission (§3.1), checked before a socket opens. */
    fun allows(url: HttpUrl): Boolean

    suspend fun read(http: PluginHttp): StationReading

    suspend fun info(http: PluginHttp): StationInfo? = null
}

/**
 * Weathercloud (weathercloud.net). There is **no published API**: this reads the same JSON the station's public web
 * page loads (`/device/values?code=…` and `/device/info/<code>`, requested as XMLHttpRequest), so it may change
 * without notice. The widget polls it at most every 5 minutes (the station uploads about every 10).
 */
class WeathercloudSource(val code: String, private val base: HttpUrl = BASE) : StationSource {
    override val id = ID

    override fun allows(url: HttpUrl): Boolean = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    override suspend fun read(http: PluginHttp): StationReading {
        val url = base.newBuilder().addPathSegments("device/values").addQueryParameter("code", code).build()
        return WeathercloudData.parseValues(http.get(url, HEADERS))
    }

    override suspend fun info(http: PluginHttp): StationInfo? {
        val url = base.newBuilder().addPathSegments("device/info").addPathSegment(code).build()
        return WeathercloudData.parseInfo(http.get(url, HEADERS))
    }

    companion object {
        const val ID = "weathercloud"
        val BASE = "https://app.weathercloud.net/".toHttpUrl()
        private val HEADERS = mapOf("X-Requested-With" to "XMLHttpRequest")
    }
}

object WeathercloudData {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * `GET /device/values?code=…`: `{"epoch":1792941451,"bar":988.8,"temp":12.8,"hum":56,"wdir":253,"wspd":2.3,
     * "dew":4.2,"heat":12.8,"rainrate":0,"rain":15.1,"uvi":0,"solarrad":31.7,"wspdavg":2.3,"wdiravg":253}`. Numbers may
     * also come as strings (the info endpoint sends them that way); missing or implausible values become null.
     */
    fun parseValues(body: String): StationReading {
        val o = json.parseToJsonElement(body).jsonObject
        return readingOf(o, epoch(o["epoch"]))
    }

    /**
     * `GET /device/info/<code>`: `{"device":{"city":"…","altitude":"40.0","update":13,…},"values":{"temp":"12.6",…}}`.
     */
    fun parseInfo(body: String): StationInfo {
        val o = json.parseToJsonElement(body).jsonObject
        val d = o["device"] as? JsonObject ?: JsonObject(emptyMap())
        return StationInfo(
            city = (d["city"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
            altitudeM = num(d["altitude"])?.takeIf { it in -500.0..9000.0 },
        )
    }

    private fun readingOf(o: JsonObject, atMs: Long?) = StationReading(
        atMs = atMs,
        tempC = num(o["temp"])?.takeIf { it in -90.0..70.0 },
        humidityPct = num(o["hum"])?.takeIf { it in 0.0..100.0 },
        dewPointC = num(o["dew"])?.takeIf { it in -90.0..70.0 },
        feelsLikeC = (num(o["heat"]) ?: num(o["chill"]))?.takeIf { it in -90.0..80.0 },
        pressureHpa = num(o["bar"])?.takeIf { it in 850.0..1100.0 },
        windMs = num(o["wspd"])?.takeIf { it in 0.0..120.0 },
        windDirDeg = num(o["wdir"])?.takeIf { it in 0.0..360.0 },
        windAvgMs = num(o["wspdavg"])?.takeIf { it in 0.0..120.0 },
        windAvgDirDeg = num(o["wdiravg"])?.takeIf { it in 0.0..360.0 },
        gustMs = (num(o["wspdhi"]) ?: num(o["gust"]))?.takeIf { it in 0.0..150.0 },
        rainTodayMm = num(o["rain"])?.takeIf { it in 0.0..2000.0 },
        rainRateMmH = num(o["rainrate"])?.takeIf { it in 0.0..2000.0 },
        solarWm2 = num(o["solarrad"])?.takeIf { it in 0.0..2000.0 },
        uvIndex = num(o["uvi"])?.takeIf { it in 0.0..25.0 },
    )

    /** A JSON number or a numeric string ("12.6"); null for anything else. */
    internal fun num(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        val v = if (p.isString) p.content.trim().replace(',', '.').toDoubleOrNull() else p.doubleOrNull
        return v?.takeIf { it.isFinite() }
    }

    /** Seconds (or, defensively, milliseconds) since the epoch. */
    private fun epoch(e: JsonElement?): Long? {
        val v = num(e) ?: return null
        if (v <= 0) return null
        return if (v > 1e12) v.toLong() else (v * 1000).toLong()
    }
}

/** The owner types a station code or pastes the station's page. */
object StationCode {
    private val digits = Regex("^d?(\\d{6,12})$", RegexOption.IGNORE_CASE)
    private val inPath = Regex("(?:^|/)d(\\d{6,12})(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    private val inQuery = Regex("[?&]code=(\\d{6,12})(?:&|#|$)", RegexOption.IGNORE_CASE)

    /**
     * "9287156658", "d9287156658", "https://app.weathercloud.net/d9287156658", "app.weathercloud.net/d9287156658#current"
     * or ".../device/values?code=9287156658" → "9287156658"; null when there is no code in it.
     */
    fun parse(input: String?): String? {
        val t = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        digits.matchEntire(t)?.let { return it.groupValues[1] }
        if (!t.contains("weathercloud", ignoreCase = true) && !t.contains('/')) return null
        inQuery.find(t)?.let { return it.groupValues[1] }
        inPath.find(t)?.let { return it.groupValues[1] }
        return null
    }
}
