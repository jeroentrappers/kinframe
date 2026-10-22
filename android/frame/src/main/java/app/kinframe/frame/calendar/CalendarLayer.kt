package app.kinframe.frame.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.kinframe.frame.FrameController
import app.kinframe.frame.weather.Weather

/**
 * The calendar's full-screen layers over the slideshow: the calendar itself, the event details sheet and the
 * invitation card (on top, so an invitation shows even while the calendar is open).
 */
@Composable
fun CalendarLayer(c: FrameController, nowMs: Long, weather: Weather?) {
    val cc = c.calendar
    val open by cc.open.collectAsState()
    val data by cc.data.collectAsState()
    val detail by cc.detail.collectAsState()
    val focused by cc.focused.collectAsState()
    val syncing by cc.syncing.collectAsState()
    val settings by c.g.settings.settings.collectAsState()

    AnimatedVisibility(open, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        CalendarScreen(
            data, weather, nowMs, cc.zone, settings.calendar, settings.locale.firstDayOfWeek, settings.locale.temperatureUnit,
            settings.widgets.clock24h, syncing,
            onEvent = { cc.showEvent(it) }, onSync = { cc.syncNow() }, onClose = { cc.closeCalendar() },
        )
    }

    var detailPhoto by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(detail?.invitationId, detail?.hasPhoto) {
        detailPhoto = null
        val d = detail
        if (d?.hasPhoto == true && d.invitationId != null) detailPhoto = cc.photo(d.invitationId)
    }
    EventDetailOverlay(detail, detailPhoto, settings.widgets.clock24h, onCountdown = { e, on -> cc.setCountdown(e, on) }, onClose = { cc.showEvent(null) })

    var cardPhoto by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(focused?.id) {
        cardPhoto = null
        val f = focused
        if (f != null && f.hasPhoto) cardPhoto = cc.photo(f.id)
    }
    AnimatedVisibility(focused != null, enter = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.96f), exit = fadeOut(tween(250))) {
        val inv = focused ?: return@AnimatedVisibility
        InvitationCard(
            inv, cardPhoto, cc.zone, settings.widgets.clock24h,
            onAccept = { cc.decide(inv, accept = true) }, onDecline = { cc.decide(inv, accept = false) }, onLater = { cc.later() },
        )
    }
}
