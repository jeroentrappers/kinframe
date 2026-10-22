package app.kinframe.frame.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R

private val photoShadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f)

/**
 * Current weather for the corner of the slideshow: icon and temperature on the clock's line, condition and today's
 * range on the date's line, so it reads as one block with the clock. Same treatment as the clock on photos (white
 * with a soft text shadow, no box); ink on the paper empty state. Tapping it opens the forecast.
 */
@Composable
fun WeatherNow(
    weather: Weather,
    nowMs: Long,
    unit: String,
    onPaper: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = weather.nowConditions(nowMs)
    val today = weather.today(nowMs)
    val color = if (onPaper) Kin.colors.ink else Color.White
    val shadow = if (onPaper) null else photoShadow
    val label = stringResource(WeatherCodes.label(now.code, now.isDay))
    Column(
        modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.weather_show_forecast), onClick = onClick)
            .padding(start = 8.dp, top = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), 64.dp, Modifier.offset(y = 2.dp), onPaper = onPaper)
            Text(Units.tempLabel(now.tempC, unit), style = Kin.type.display.copy(color = color, shadow = shadow, fontWeight = FontWeight.Light))
        }
        val range = today?.let { "${Units.tempLabel(it.minC, unit)} – ${Units.tempLabel(it.maxC, unit)}" }
        Text(
            if (range != null) "$label  ·  $range" else label,
            style = Kin.type.body.copy(color = color.copy(alpha = 0.9f), shadow = shadow),
            maxLines = 1,
        )
    }
}
