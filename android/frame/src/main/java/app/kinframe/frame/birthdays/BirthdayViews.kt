package app.kinframe.frame.birthdays

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val photoShadow = Shadow(Color(0x80000000), Offset(0f, 1f), 6f)

/** Warm palette for balloons and the photo placeholder (calendar `rose` first). */
internal val BalloonColors = listOf(Color(0xFFDA6C92), Color(0xFFE7A23A), Color(0xFFC0623E), Color(0xFFE8795A), Color(0xFF9A5B6F), Color(0xFF5F9E8A))
private val Rose = BalloonColors[0]

/** Stands for the name in a localized phrase, so the name alone can be ellipsized (never the phrase). */
private const val NAME_MARK = ""

// ------------------------------------------------------------------------------------------ photos

/** Photos of [people], loaded (and cached on disk) in the background; missing ones are absent from the map. */
@Composable
fun rememberBirthdayPhotos(c: FrameController, people: List<BirthdayOccurrence>): Map<String, ImageBitmap> {
    val photos = remember { mutableStateMapOf<String, ImageBitmap>() }
    val urls = people.mapNotNull { it.person.photoUrl?.takeIf { u -> u.isNotBlank() } }.distinct()
    LaunchedEffect(urls) {
        for (u in urls) if (u !in photos) c.birthdays.photo(u)?.let { photos[u] = it }
    }
    return photos
}

private fun Map<String, ImageBitmap>.of(o: BirthdayOccurrence): ImageBitmap? = o.person.photoUrl?.let { this[it] }

@Composable
private fun PersonPhoto(photo: ImageBitmap?, size: Dp) {
    if (photo != null) {
        Image(photo, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        // Without a photo: a balloon on a warm rose disc.
        Box(Modifier.size(size).clip(CircleShape).background(Rose.copy(alpha = 0.28f).compositeOverWhite()), contentAlignment = Alignment.Center) {
            BalloonGlyph(Rose, Modifier.size(size * 0.62f))
        }
    }
}

private fun Color.compositeOverWhite(): Color = Color(
    red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha), alpha = 1f,
)

/** A small balloon: the countdown tile's marker and the placeholder photo. */
@Composable
fun BalloonGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawBalloon(color, size) }
}

private fun DrawScope.drawBalloon(color: Color, area: Size) {
    val w = area.width
    val h = area.height
    val bodyW = minOf(w, h * 0.66f)
    val bodyH = bodyW * 1.18f
    val left = (w - bodyW) / 2f
    drawOval(color, Offset(left, 0f), Size(bodyW, bodyH))
    // Highlight: one translucent oval, no gradient.
    drawOval(Color.White.copy(alpha = 0.32f), Offset(left + bodyW * 0.2f, bodyH * 0.14f), Size(bodyW * 0.22f, bodyH * 0.3f))
    val cx = w / 2f
    val knot = Path().apply {
        moveTo(cx, bodyH - 1f)
        lineTo(cx - bodyW * 0.08f, bodyH + bodyW * 0.1f)
        lineTo(cx + bodyW * 0.08f, bodyH + bodyW * 0.1f)
        close()
    }
    drawPath(knot, color)
    if (h > bodyH + bodyW * 0.2f) {
        val string = Path().apply {
            moveTo(cx, bodyH + bodyW * 0.1f)
            cubicTo(cx - bodyW * 0.12f, bodyH + (h - bodyH) * 0.4f, cx + bodyW * 0.12f, bodyH + (h - bodyH) * 0.7f, cx, h)
        }
        drawPath(string, color.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1f, w * 0.025f)))
    }
}

// ------------------------------------------------------------------------------------------ wording

/** [template] with [NAME_MARK] in place of the name, rendered with the name alone ellipsized. */
@Composable
private fun NamePhrase(template: String, name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val at = template.indexOf(NAME_MARK)
    if (at < 0) {
        Text(template, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val before = template.substring(0, at)
    val after = template.substring(at + NAME_MARK.length)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (before.isNotEmpty()) Text(before, style = style, color = color, maxLines = 1, softWrap = false)
        Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (after.isNotEmpty()) Text(after, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** "🎈 Oma is 80 today!" / "🎈 It's Oma's birthday today!" with [NAME_MARK] for the name. */
@Composable
internal fun todayTemplate(o: BirthdayOccurrence): String =
    o.age?.let { pluralStringResource(R.plurals.birthday_card_age, it, NAME_MARK, it) } ?: stringResource(R.string.birthday_card_plain, NAME_MARK)

/** The same sentence with the name filled in (calendar sidebar, split dashboard). */
@Composable
fun todayPhrase(o: BirthdayOccurrence): String = todayTemplate(o).replace(NAME_MARK, o.name)

/** Countdown tile title: "Oma turns 80", "Oma's birthday", "Oma and Lotte", "Oma and 2 more". */
@Composable
fun birthdayTileTitle(people: List<BirthdayOccurrence>): String {
    val first = people.firstOrNull() ?: return ""
    return when (people.size) {
        1 -> first.age?.let { pluralStringResource(R.plurals.birthday_countdown_turns, it, first.name, it) }
            ?: stringResource(R.string.birthday_countdown_plain, first.name)
        2 -> stringResource(R.string.birthday_countdown_two, first.name, people[1].name)
        else -> pluralStringResource(R.plurals.birthday_countdown_more, people.size - 1, first.name, people.size - 1)
    }
}

// ------------------------------------------------------------------------------------------ the card

/**
 * The day-of card (birthdays.md §6.3), first in the top-right countdown column with the same width and smoked glass
 * on photos (one translucent fill, no blur); a surface card on the paper empty state. One person: the round photo and
 * "🎈 Oma is 80 today!". Several: "🎈 Birthdays today", up to three lines with small photos, then "and N more".
 * Tapping opens the greeting sheet.
 */
@Composable
fun BirthdayCard(people: List<BirthdayOccurrence>, photos: Map<String, ImageBitmap>, onPaper: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    val ink = if (onPaper) Kin.colors.ink else Color.White
    val soft = if (onPaper) Kin.colors.inkSoft else Color.White.copy(alpha = 0.85f)
    val shadow = if (onPaper) null else photoShadow
    Box(
        modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(18.dp))
            .background(if (onPaper) Kin.colors.surface else Color(0x47000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (people.size == 1) {
            val o = people[0]
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonPhoto(photos.of(o), 56.dp)
                NamePhrase(
                    todayTemplate(o), o.name, Kin.type.bodyStrong.copy(fontSize = 19.sp, shadow = shadow), ink,
                    Modifier.padding(start = 14.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.birthday_card_title_many), style = Kin.type.bodyStrong.copy(fontSize = 19.sp, shadow = shadow), color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
                people.take(BirthdayLogic.CARD_LINES).forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonPhoto(photos.of(o), 32.dp)
                        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val style = Kin.type.body.copy(fontSize = 17.sp, shadow = shadow)
                            Text(o.name, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            o.age?.let { Text(" · $it", style = style, color = soft, maxLines = 1, softWrap = false) }
                        }
                    }
                }
                val more = people.size - BirthdayLogic.CARD_LINES
                if (more > 0) {
                    Text(
                        pluralStringResource(R.plurals.birthday_card_more, more, more), style = Kin.type.caption.copy(shadow = shadow), color = soft,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ greeting sheet

/** Closes like the quick menu after this long without a touch. */
private const val SHEET_IDLE_MS = 15_000L

/**
 * "Say happy birthday" (birthdays.md §7): one large button per person on today's card. One tap sends; the button turns
 * into "Sent ✓ — the family will see it" for the rest of the day. No confirmation, no text entry.
 */
@Composable
fun BirthdayGreetingSheet(
    visible: Boolean,
    people: List<BirthdayOccurrence>,
    sent: Set<String>,
    photos: Map<String, ImageBitmap>,
    onGreet: (BirthdayOccurrence) -> Unit,
    onClose: () -> Unit,
) {
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible, touched) { if (visible) { delay(SHEET_IDLE_MS); onClose() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = if (maxWidth > 640.dp) 560.dp else maxWidth - 48.dp
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.Center),
            enter = fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.96f), exit = fadeOut(tween(180)),
        ) {
            Box(
                Modifier.width(w).heightIn(max = maxHeight - 48.dp)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched = System.currentTimeMillis() } } },
            ) {
                GreetingSheetContent(people, sent, photos, onGreet, onClose)
            }
        }
    }
}

/** The sheet's content (separate so screenshot tests can render it directly). */
@Composable
fun GreetingSheetContent(
    people: List<BirthdayOccurrence>,
    sent: Set<String>,
    photos: Map<String, ImageBitmap>,
    onGreet: (BirthdayOccurrence) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(28.dp)).background(Kin.colors.paper)
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BalloonGlyph(Rose, Modifier.size(width = 22.dp, height = 30.dp))
            Text(
                stringResource(R.string.birthday_greet_title), style = Kin.type.title, color = Kin.colors.ink,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.Close, stringResource(R.string.action_close), size = 26.dp)
            }
        }
        Column(
            Modifier.padding(top = 12.dp, end = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            people.forEach { o -> GreetButton(o, o.id in sent, photos.of(o)) { onGreet(o) } }
        }
    }
}

@Composable
private fun GreetButton(o: BirthdayOccurrence, sent: Boolean, photo: ImageBitmap?, onClick: () -> Unit) {
    val bg = if (sent) Kin.colors.sageSoft else Kin.colors.accent
    val fg = if (sent) Kin.colors.onSageSoft else Kin.colors.onAccent
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(22.dp)).background(bg)
            .then(if (sent) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonPhoto(photo, 52.dp)
        if (sent) {
            Text(stringResource(R.string.birthday_greet_sent), style = Kin.type.bodyStrong, color = fg, modifier = Modifier.padding(start = 16.dp))
        } else {
            NamePhrase(stringResource(R.string.birthday_greet_button, NAME_MARK), o.name, Kin.type.bodyStrong.copy(fontSize = 19.sp), fg, Modifier.padding(start = 16.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------ balloons

private data class BalloonSpec(val color: Int, val x: Float, val delay: Float, val span: Float, val sway: Float, val phase: Float, val scale: Float)

/** Balloon bitmaps, drawn once per colour and size and reused for every run. */
private object BalloonBitmaps {
    private val cache = HashMap<String, ImageBitmap>()

    fun get(color: Int, w: Int, h: Int, density: Density): ImageBitmap = synchronized(cache) {
        cache.getOrPut("$color:$w:$h") {
            val bmp = ImageBitmap(w, h)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                drawBalloon(BalloonColors[color % BalloonColors.size], size)
            }
            bmp
        }
    }
}

/**
 * Balloons drifting up across the screen for about six seconds (birthdays.md §6.4): 5–7 cached bitmaps, each one
 * translated layer with a slight sideways sway; no full-screen fill, no blur, no shadow. Draws above the slideshow
 * but takes no touches. Calls [onDone] when the last one has left the top edge, so the layer can be removed.
 */
@Composable
fun BalloonsLayer(seed: Int, previewProgress: Float? = null, onDone: () -> Unit) {
    val density = LocalDensity.current
    val specs = remember(seed) {
        val r = Random(seed)
        val n = 5 + r.nextInt(3)
        List(n) { i ->
            BalloonSpec(
                color = i, x = (i + 0.2f + r.nextFloat() * 0.6f) / n, delay = r.nextFloat() * 0.25f, span = 0.7f + r.nextFloat() * 0.05f,
                sway = 0.015f + r.nextFloat() * 0.02f, phase = r.nextFloat() * 6.28f, scale = 0.8f + r.nextFloat() * 0.35f,
            )
        }
    }
    val progress = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) {
        if (previewProgress != null) { progress.snapTo(previewProgress); return@LaunchedEffect }
        progress.animateTo(1f, tween(BalloonPlanner.DURATION_MS, easing = LinearEasing))
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        specs.forEach { s ->
            val bw = with(density) { (64.dp * s.scale).roundToPx() }
            val bh = (bw * 1.9f).toInt()
            val bmp = remember(s, bw) { BalloonBitmaps.get(s.color, bw, bh, density) }
            Image(
                bmp, null,
                Modifier.size(with(density) { bw.toDp() }, with(density) { bh.toDp() }).graphicsLayer {
                    val t = ((progress.value - s.delay) / s.span).coerceIn(0f, 1f)
                    translationY = hPx - t * (hPx + bh)
                    translationX = s.x * wPx - bw / 2f + sin(t * 2f * PI.toFloat() * 1.5f + s.phase) * s.sway * wPx
                },
            )
        }
    }
}
