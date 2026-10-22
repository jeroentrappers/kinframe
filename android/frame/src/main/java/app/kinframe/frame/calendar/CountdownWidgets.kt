package app.kinframe.frame.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val photoShadow = Shadow(Color(0x80000000), Offset(0f, 1f), 6f)

/**
 * Countdowns on the slideshow (behaviour spec B.2: up to 3, refreshed with the clock): small, quiet tiles with the
 * number of days in front. A light smoked glass on photos (one translucent fill, no blur) and surface cards on the
 * paper empty state. Tapping one opens the calendar.
 */
@Composable
fun CountdownWidgets(items: List<CountdownItem>, today: LocalDate, onPaper: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(
        modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        items.take(3).forEach { c -> CountdownTile(c, today, onPaper) }
    }
}

@Composable
private fun CountdownTile(c: CountdownItem, today: LocalDate, onPaper: Boolean) {
    val days = ChronoUnit.DAYS.between(today, c.day).toInt().coerceAtLeast(0)
    val sw = CalendarPalette.of(c.color)
    val ink = if (onPaper) Kin.colors.ink else Color.White
    val soft = if (onPaper) Kin.colors.inkSoft else Color.White.copy(alpha = 0.82f)
    val shadow = if (onPaper) null else photoShadow
    Row(
        Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(16.dp))
            .background(if (onPaper) Kin.colors.surface else Color(0x47000000))
            .padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (onPaper) sw.strong else sw.onDark))
        if (days >= 2) {
            Text(
                "$days", style = Kin.type.title.copy(fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, shadow = shadow), color = ink,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                pluralStringResource(R.plurals.countdown_days, days), style = Kin.type.caption.copy(shadow = shadow), color = soft,
                modifier = Modifier.padding(start = 5.dp, top = 6.dp),
            )
        } else {
            Text(
                stringResource(if (days == 0) R.string.countdown_today else R.string.countdown_tomorrow),
                style = Kin.type.bodyStrong.copy(shadow = shadow), color = ink, modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            c.title, style = Kin.type.body.copy(fontSize = 17.sp, shadow = shadow), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
