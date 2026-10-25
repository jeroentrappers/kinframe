package app.kinframe.frame.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.plugin.ui.PluginPalette
import app.kinframe.core.plugin.ui.ViewForm
import app.kinframe.frame.weather.Units
import app.kinframe.frame.weather.Weather
import app.kinframe.frame.weather.WeatherCodes
import app.kinframe.frame.weather.WeatherIcon

/**
 * The clock and current weather moved into the band ("so the photo has no overlays at all", §2.1): at the foot of
 * the column in landscape, at the end of the strip in portrait.
 */
@Composable
fun BandClock(form: ViewForm, palette: PluginPalette, clock: String?, date: String?, weather: Weather?, nowMs: Long, unit: String, onPaper: Boolean) {
    if (clock == null && weather == null) return
    val now = weather?.nowConditions(nowMs)
    val color = palette.text
    val scale = Kin.type.body.fontSize.value / 19f
    if (form == ViewForm.Column) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.padding(bottom = 10.dp).width(36.dp).height(1.dp).background(palette.faint))
            if (now != null) {
                WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 38.dp, onPaper = onPaper)
                Text(Units.tempLabel(now.tempC, unit), style = Kin.type.body.copy(color = color, shadow = palette.shadow, fontSize = (17 * scale).sp))
            }
            if (clock != null) {
                Text(clock, style = Kin.type.title.copy(color = color, shadow = palette.shadow, fontSize = (22 * scale).sp, fontWeight = FontWeight.Normal), maxLines = 1)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(1.dp).height(40.dp).background(palette.faint))
            if (now != null) {
                WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 40.dp, onPaper = onPaper)
                Text(Units.tempLabel(now.tempC, unit), style = Kin.type.title.copy(color = color, shadow = palette.shadow, fontWeight = FontWeight.Light))
            }
            if (clock != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, style = Kin.type.headline.copy(color = color, shadow = palette.shadow, fontWeight = FontWeight.Light, fontSize = (30 * scale).sp, lineHeight = (34 * scale).sp), maxLines = 1)
                    date?.let { Text(it, style = Kin.type.caption.copy(color = palette.soft, shadow = palette.shadow), maxLines = 1) }
                }
            }
        }
    }
}
