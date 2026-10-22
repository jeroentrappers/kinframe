package app.kinframe.frame.weather

import androidx.annotation.StringRes
import app.kinframe.frame.R

/** The kinframe weather icon set (drawn in [WeatherIcon]). */
enum class WeatherGlyph { Sun, Moon, PartlyCloudyDay, PartlyCloudyNight, Cloud, Fog, Drizzle, Rain, HeavyRain, Snow, Sleet, Thunder }

/** WMO weather interpretation codes (as used by Open-Meteo) → icon and localized label. */
object WeatherCodes {
    fun glyph(code: Int, isDay: Boolean): WeatherGlyph = when (code) {
        0 -> if (isDay) WeatherGlyph.Sun else WeatherGlyph.Moon
        1, 2 -> if (isDay) WeatherGlyph.PartlyCloudyDay else WeatherGlyph.PartlyCloudyNight
        3 -> WeatherGlyph.Cloud
        45, 48 -> WeatherGlyph.Fog
        51, 53, 55 -> WeatherGlyph.Drizzle
        56, 57, 66, 67 -> WeatherGlyph.Sleet
        61, 63, 80, 81 -> WeatherGlyph.Rain
        65, 82 -> WeatherGlyph.HeavyRain
        71, 73, 75, 77, 85, 86 -> WeatherGlyph.Snow
        95, 96, 99 -> WeatherGlyph.Thunder
        else -> when {
            // Unknown codes: fall back by WMO range rather than showing nothing.
            code in 40..49 -> WeatherGlyph.Fog
            code in 50..69 -> WeatherGlyph.Rain
            code in 70..79 -> WeatherGlyph.Snow
            code in 80..89 -> WeatherGlyph.Rain
            code in 90..99 -> WeatherGlyph.Thunder
            else -> WeatherGlyph.Cloud
        }
    }

    @StringRes
    fun label(code: Int, isDay: Boolean): Int = when (code) {
        0 -> if (isDay) R.string.wmo_clear_day else R.string.wmo_clear_night
        1 -> if (isDay) R.string.wmo_mainly_clear_day else R.string.wmo_mainly_clear_night
        2 -> R.string.wmo_partly_cloudy
        3 -> R.string.wmo_overcast
        45 -> R.string.wmo_fog
        48 -> R.string.wmo_rime_fog
        51 -> R.string.wmo_drizzle_light
        53 -> R.string.wmo_drizzle
        55 -> R.string.wmo_drizzle_dense
        56, 57 -> R.string.wmo_freezing_drizzle
        61 -> R.string.wmo_rain_light
        63 -> R.string.wmo_rain
        65 -> R.string.wmo_rain_heavy
        66, 67 -> R.string.wmo_freezing_rain
        71 -> R.string.wmo_snow_light
        73 -> R.string.wmo_snow
        75 -> R.string.wmo_snow_heavy
        77 -> R.string.wmo_snow_grains
        80 -> R.string.wmo_showers_light
        81 -> R.string.wmo_showers
        82 -> R.string.wmo_showers_violent
        85 -> R.string.wmo_snow_showers_light
        86 -> R.string.wmo_snow_showers
        95 -> R.string.wmo_thunderstorm
        96, 99 -> R.string.wmo_thunderstorm_hail
        else -> when (glyph(code, isDay)) {
            WeatherGlyph.Fog -> R.string.wmo_fog
            WeatherGlyph.Snow -> R.string.wmo_snow
            WeatherGlyph.Thunder -> R.string.wmo_thunderstorm
            WeatherGlyph.Rain -> R.string.wmo_rain
            else -> R.string.wmo_overcast
        }
    }
}
