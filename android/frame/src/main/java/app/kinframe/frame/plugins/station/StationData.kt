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
    /** Today's lowest and highest temperature so far (the server keeps the uploads). */
    val todayMinC: Double? = null,
    val todayMaxC: Double? = null,
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
 * The station uploads to kinframe (docs/plugins.md §9.4): its readings are on the frame's own server,
 * `GET /v1/stations/latest`, named by the station's upload ID and key (headers, so they stay out of logs). No third
 * party is involved; the server already turned the station's imperial units into metric.
 */
class UploadSource(private val latest: HttpUrl, private val stationId: String, private val stationKey: String) : StationSource {
    override val id = ID

    override fun allows(url: HttpUrl): Boolean = allows(latest, url)

    override suspend fun read(http: PluginHttp): StationReading =
        UploadData.parse(http.get(latest, mapOf("X-Station-Id" to stationId, "X-Station-Key" to stationKey)))

    companion object {
        const val ID = "upload"

        /** The reading endpoint on the frame's server ([apiBase] ends in `/v1/`). */
        fun latestUrl(apiBase: HttpUrl): HttpUrl = apiBase.newBuilder().addPathSegments("stations/latest").build()

        fun allows(latest: HttpUrl, url: HttpUrl): Boolean =
            url.scheme == latest.scheme && url.host == latest.host && url.port == latest.port && url.encodedPath == latest.encodedPath
    }
}

object UploadData {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `{"received_at":"2026-10-27T10:15:02Z","reading":{"temp_c":10.0,"humidity_pct":81,…},"today":{…}}`; when the
     * station sent its own clock (`station_time`) that is when it measured, else when the server received it.
     */
    fun parse(body: String): StationReading {
        val o = json.parseToJsonElement(body).jsonObject
        val r = o["reading"] as? JsonObject ?: JsonObject(emptyMap())
        val today = o["today"] as? JsonObject
        fun time(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return StationReading(
            atMs = time(r["station_time"]) ?: time(o["received_at"]),
            tempC = num(r["temp_c"])?.takeIf { it in -90.0..70.0 },
            humidityPct = num(r["humidity_pct"])?.takeIf { it in 0.0..100.0 },
            dewPointC = num(r["dew_point_c"])?.takeIf { it in -90.0..70.0 },
            feelsLikeC = num(r["feels_like_c"])?.takeIf { it in -90.0..80.0 },
            pressureHpa = num(r["pressure_hpa"])?.takeIf { it in 850.0..1100.0 },
            windMs = num(r["wind_ms"])?.takeIf { it in 0.0..120.0 },
            windDirDeg = num(r["wind_dir_deg"])?.takeIf { it in 0.0..360.0 },
            gustMs = num(r["gust_ms"])?.takeIf { it in 0.0..150.0 },
            rainTodayMm = num(r["rain_today_mm"])?.takeIf { it in 0.0..2000.0 },
            rainRateMmH = num(r["rain_rate_mm_h"])?.takeIf { it in 0.0..2000.0 },
            solarWm2 = num(r["solar_wm2"])?.takeIf { it in 0.0..2000.0 },
            uvIndex = num(r["uv_index"])?.takeIf { it in 0.0..25.0 },
            todayMinC = num(today?.get("temp_min_c"))?.takeIf { it in -90.0..70.0 },
            todayMaxC = num(today?.get("temp_max_c"))?.takeIf { it in -90.0..70.0 },
        )
    }

    /** A JSON number or a numeric string ("12.6"); null for anything else. */
    internal fun num(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        val v = if (p.isString) p.content.trim().replace(',', '.').toDoubleOrNull() else p.doubleOrNull
        return v?.takeIf { it.isFinite() }
    }
}
