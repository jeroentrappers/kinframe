package app.kinframe.frame.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.R
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/** Closes by itself after this long without a touch (it is a glance, not a screen to leave open). */
const val FORECAST_IDLE_MS = 60_000L

private val sheetColor = Color(0xF51C1714)
private val hairline = Color.White.copy(alpha = 0.10f)
private val soft = Color.White.copy(alpha = 0.62f)
private val faint = Color.White.copy(alpha = 0.42f)
private val rainBlue = Color(0xFF8CC8FF)

/**
 * The forecast as a full-height sheet from the right over a dimmed photo. Translate + fade only (no blur, cheap on
 * the Mali-400). Tapping outside closes it, and so does [FORECAST_IDLE_MS] without a touch.
 */
@Composable
fun ForecastOverlay(
    visible: Boolean,
    weather: Weather?,
    nowMs: Long,
    unit: String,
    h24: Boolean,
    onClose: () -> Unit,
) {
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible, touched) {
        if (visible) {
            delay(FORECAST_IDLE_MS)
            onClose()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sheetWidth: Dp = if (landscape) minOf(760.dp, maxWidth * 0.62f) else maxWidth - 72.dp
        AnimatedVisibility(visible, enter = fadeIn(tween(260)), exit = fadeOut(tween(220))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible && weather != null,
            Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(200)),
        ) {
            if (weather != null) {
                Box(
                    Modifier.width(sheetWidth).fillMaxHeight()
                        .pointerInput(Unit) {
                            // Any touch inside keeps the sheet open; nothing is consumed, so scrolling still works.
                            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched = System.currentTimeMillis() } }
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { },
                ) {
                    ForecastSheet(weather, nowMs, unit, h24, wide = landscape, onClose = onClose)
                }
            }
        }
    }
}

/** The sheet's content (separate from the overlay so screenshot tests can render it directly). */
@Composable
fun ForecastSheet(weather: Weather, nowMs: Long, unit: String, h24: Boolean, wide: Boolean, onClose: () -> Unit) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val now = weather.nowConditions(nowMs)
    val today = weather.today(nowMs)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF7262019), sheetColor)))) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 40.dp, end = 28.dp, top = 28.dp, bottom = 52.dp),
    ) {
        // Header: place, freshness, close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(weather.location?.name ?: "", style = Kin.type.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                weather.fetchedAtMs?.let { at ->
                    val t = Instant.ofEpochMilli(at).atOffset(weather.offset)
                    Text(stringResource(R.string.weather_updated, WeatherFormat.time(t, h24, locale)), style = Kin.type.caption, color = faint)
                }
            }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KinIcon(KinIcons.Close, stringResource(R.string.weather_close), tint = Color.White, size = 24.dp) }
        }

        Spacer(Modifier.height(if (wide) 14.dp else 24.dp))
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hero(now, today, unit, Modifier.weight(1f), iconSize = 88.dp, tempSize = 72)
                Spacer(Modifier.width(16.dp))
                Stats(now, today, unit, h24, locale, Modifier.width(300.dp), columns = 2)
            }
        } else {
            Hero(now, today, unit, Modifier.fillMaxWidth(), iconSize = 120.dp, tempSize = 96)
            Spacer(Modifier.height(22.dp))
            Stats(now, today, unit, h24, locale, Modifier.fillMaxWidth(), columns = 4)
        }

        val hours = weather.upcomingHours(nowMs)
        if (hours.isNotEmpty()) {
            SectionLabel(stringResource(R.string.weather_next_hours), top = if (wide) 22.dp else 44.dp)
            HourStrip(hours, unit, h24, locale, iconSize = if (wide) 38.dp else 46.dp)
        }

        val days = weather.upcomingDays(nowMs)
        if (days.isNotEmpty()) {
            SectionLabel(stringResource(R.string.weather_next_days), top = if (wide) 18.dp else 40.dp)
            DayList(days, now.tempC, unit, locale, rowHeight = if (wide) 43.dp else 70.dp, iconSize = if (wide) 34.dp else 42.dp)
        }

    }
    // CC BY 4.0 attribution for Open-Meteo, always visible at the bottom.
    Text(
        stringResource(R.string.weather_attribution), style = Kin.type.caption.copy(fontSize = 12.sp), color = faint,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 18.dp),
    )
    }
}

@Composable
private fun Hero(now: Weather.Current, today: Weather.Day?, unit: String, modifier: Modifier, iconSize: Dp, tempSize: Int) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(WeatherCodes.glyph(now.code, now.isDay), iconSize)
        Spacer(Modifier.width(12.dp))
        Text(
            Units.tempLabel(now.tempC, unit),
            style = Kin.type.display.copy(fontSize = tempSize.sp, lineHeight = (tempSize + 4).sp, fontWeight = FontWeight.Light, color = Color.White),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(WeatherCodes.label(now.code, now.isDay)), style = Kin.type.title, color = Color.White, maxLines = 2)
            now.apparentC?.let { Text(stringResource(R.string.weather_feels_like, Units.tempLabel(it, unit)), style = Kin.type.body, color = soft) }
            today?.let { Text(stringResource(R.string.weather_high_low, Units.tempLabel(it.maxC, unit), Units.tempLabel(it.minC, unit)), style = Kin.type.body, color = soft) }
        }
    }
}

@Composable
private fun Stats(now: Weather.Current, today: Weather.Day?, unit: String, h24: Boolean, locale: Locale, modifier: Modifier, columns: Int) {
    val compass = stringArrayResource(R.array.weather_compass)
    val cells = buildList {
        now.windKmh?.let { kmh ->
            val dir = now.windDirDeg
            val text = (if (dir != null) compass[Units.compassSector(dir)] + " " else "") + Units.wind(kmh, unit)
            add(Stat(stringResource(R.string.weather_wind), text, Units.windUnit(unit), dir?.let { d -> { WindArrow(d) } }))
        }
        now.humidityPct?.let { add(Stat(stringResource(R.string.weather_humidity), "$it%")) }
        Weather.parseOffset(today?.sunrise)?.let { add(Stat(stringResource(R.string.weather_sunrise), WeatherFormat.time(it, h24, locale))) }
        Weather.parseOffset(today?.sunset)?.let { add(Stat(stringResource(R.string.weather_sunset), WeatherFormat.time(it, h24, locale))) }
    }
    if (cells.isEmpty()) return
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { st ->
                    Column(Modifier.weight(1f)) {
                        Text(st.label, style = Kin.type.caption, color = faint, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            st.lead?.let { it(); Spacer(Modifier.width(6.dp)) }
                            Text(st.value, style = Kin.type.bodyStrong, color = Color.White, maxLines = 1)
                            st.suffix?.let { Text(" $it", style = Kin.type.caption, color = soft, maxLines = 1) }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class Stat(val label: String, val value: String, val suffix: String? = null, val lead: (@Composable () -> Unit)? = null)

/** A small arrow pointing where the wind blows to (it comes from [fromDeg]). */
@Composable
private fun WindArrow(fromDeg: Int) {
    Canvas(Modifier.size(16.dp).rotate(fromDeg + 180f)) {
        val w = size.width
        val p = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w * 0.9f, w)
            lineTo(w / 2, w * 0.76f)
            lineTo(w * 0.1f, w)
            close()
        }
        drawPath(p, Color.White)
    }
}

@Composable
private fun ColumnScope.SectionLabel(text: String, top: Dp) {
    Text(
        text.uppercase(),
        style = Kin.type.caption.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
        color = faint,
        modifier = Modifier.padding(top = top, bottom = 8.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
}

@Composable
private fun HourStrip(hours: List<Weather.Hour>, unit: String, h24: Boolean, locale: Locale, iconSize: Dp) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp)) {
        hours.forEachIndexed { i, h ->
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val label = if (i == 0) stringResource(R.string.weather_now) else h.at?.let { WeatherFormat.hour(it, h24, locale) } ?: ""
                Text(label, style = Kin.type.caption, color = if (i == 0) Color.White else soft, maxLines = 1)
                WeatherIcon(WeatherCodes.glyph(h.code, h.isDay), iconSize, Modifier.padding(vertical = 4.dp))
                Text(Units.tempLabel(h.tempC, unit), style = Kin.type.bodyStrong, color = Color.White)
                val rain = WeatherFormat.rainChance(h.precipProb)
                Text(if (rain != null) "$rain%" else " ", style = Kin.type.caption.copy(fontSize = 13.sp), color = rainBlue, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DayList(days: List<Weather.Day>, nowC: Double, unit: String, locale: Locale, rowHeight: Dp, iconSize: Dp) {
    val weekMin = days.minOf { it.minC }
    val weekMax = days.maxOf { it.maxC }
    days.forEachIndexed { i, d ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            val name = if (i == 0) stringResource(R.string.weather_today) else d.localDate?.let { WeatherFormat.weekday(it, locale) } ?: d.date
            Text(name, style = Kin.type.body, color = Color.White, modifier = Modifier.width(104.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            WeatherIcon(WeatherCodes.glyph(d.code, true), iconSize)
            val rain = WeatherFormat.rainChance(d.precipProb)
            Text(
                if (rain != null) "$rain%" else "",
                style = Kin.type.caption, color = rainBlue, textAlign = TextAlign.Start,
                modifier = Modifier.width(56.dp).padding(start = 8.dp), maxLines = 1,
            )
            Text(Units.tempLabel(d.minC, unit), style = Kin.type.body, color = soft, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            RangeBar(d.minC, d.maxC, weekMin, weekMax, marker = if (i == 0) nowC else null, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
            Text(Units.tempLabel(d.maxC, unit), style = Kin.type.body, color = Color.White, textAlign = TextAlign.Start, modifier = Modifier.width(44.dp))
        }
    }
}

/** The day's min–max as a coloured segment on the week's range; today also marks the current temperature. */
@Composable
private fun RangeBar(min: Double, max: Double, weekMin: Double, weekMax: Double, marker: Double?, modifier: Modifier) {
    Canvas(modifier.height(12.dp)) {
        val h = 6.dp.toPx()
        val y = (size.height - h) / 2
        val span = (weekMax - weekMin).takeIf { it > 0.1 } ?: 1.0
        fun x(t: Double) = ((t - weekMin) / span).toFloat().coerceIn(0f, 1f) * size.width
        val r = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(0f, y), Size(size.width, h), r)
        val x0 = x(min)
        val x1 = maxOf(x(max), x0 + h)
        drawRoundRect(
            Brush.horizontalGradient(listOf(WeatherFormat.tempColor(min), WeatherFormat.tempColor(max)), startX = x0, endX = x1),
            Offset(x0, y), Size(x1 - x0, h), r,
        )
        marker?.let { m ->
            val cx = x(m.coerceIn(min, max)).coerceIn(x0 + h / 2, x1 - h / 2)
            drawCircle(Color(0xFF1C1714), h * 0.95f, Offset(cx, size.height / 2))
            drawCircle(Color.White, h * 0.62f, Offset(cx, size.height / 2))
        }
    }
}
