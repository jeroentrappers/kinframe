package app.kinframe.frame.plugins

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.ui.PluginPalette
import app.kinframe.core.plugin.ui.PluginViewContent
import app.kinframe.core.plugin.ui.ScaleToFit
import app.kinframe.core.plugin.ui.ViewForm
import app.kinframe.frame.R
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------- one instance

/** An instance's view in [form], greyed when stale, or the discreet "unavailable" mark. */
@Composable
fun InstanceContent(state: InstanceState, form: ViewForm, palette: PluginPalette, modifier: Modifier = Modifier) {
    val stale = state.status == InstanceStatus.Stale
    val alpha by animateFloatAsState(if (stale) 0.45f else 1f, tween(600), label = "stale")
    val view = state.view
    when {
        view != null -> PluginViewContent(view, form, palette, modifier.graphicsLayer { this.alpha = alpha })
        state.status == InstanceStatus.Unavailable -> UnavailableMark(form, palette, modifier)
        else -> Unit
    }
}

@Composable
private fun UnavailableMark(form: ViewForm, p: PluginPalette, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KinIcon(KinIcons.WifiOff, null, tint = p.soft, size = if (form.compact) 20.dp else 24.dp)
        if (!form.compact) {
            Text(
                stringResource(R.string.plugins_unavailable), style = Kin.type.caption.copy(color = p.soft, shadow = p.shadow),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- corners

/**
 * A soft vignette into the top corner behind a widget, the top-corner twin of the clock's scrim: one radial gradient,
 * no blur, so white text holds up on bright photos without a visible box.
 */
private fun Modifier.topCornerScrim(end: Boolean): Modifier = drawBehind {
    val c = Offset(if (end) size.width * 0.75f else size.width * 0.25f, size.height * 0.2f)
    val r = maxOf(size.width * 1.0f, 300.dp.toPx())
    scale(scaleX = 1f, scaleY = 0.6f, pivot = c) {
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.24f), 0.4f to Color.Black.copy(alpha = 0.15f), 0.75f to Color.Black.copy(alpha = 0.05f),
                1f to Color.Transparent, center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }
}

/**
 * One top corner of the photo (overlay layout): up to two instances stacked, each at most 30 % of the screen width
 * and 20 % of its height (scaled to fit). Fades out while [hidden] (swipes, video with sound, new photos).
 */
@Composable
fun CornerSlot(
    states: List<InstanceState>,
    end: Boolean,
    screenWidth: Dp,
    screenHeight: Dp,
    hidden: Boolean,
    onTap: (InstanceState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(if (hidden) 180 else 400), label = "slotHide")
    Column(
        modifier.graphicsLayer { this.alpha = alpha },
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        states.take(MAX_PER_SLOT).forEach { s ->
            androidx.compose.runtime.key(s.config.instanceId) {
                AnimatedVisibility(s.shown && (s.view != null || s.status == InstanceStatus.Unavailable), enter = fadeIn(tween(500)), exit = fadeOut(tween(400))) {
                    Box(
                        Modifier.topCornerScrim(end)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, enabled = alpha > 0.5f) { onTap(s) }
                            .padding(6.dp),
                    ) {
                        ScaleToFit(screenWidth * 0.3f, screenHeight * 0.2f) { InstanceContent(s, ViewForm.Corner, PluginPalette.OnPhoto) }
                    }
                }
            }
        }
    }
}

const val MAX_PER_SLOT = 2

/** The instances that go in [slot], in their order, at most two (docs/plugins.md §2). */
fun List<InstanceState>.inSlot(slot: Slot) = filter { it.config.slot == slot }.take(MAX_PER_SLOT)

/** The instances the band shows: top-left first, then top-right (§2.1). */
fun List<InstanceState>.forBand() = inSlot(Slot.TopLeft) + inSlot(Slot.TopRight)

fun List<InstanceState>.anyVisible() = any { it.shown && (it.view != null || it.status == InstanceStatus.Unavailable) }

// ---------------------------------------------------------------------------------------------- band

/** The compact form for a band of this shape; a wide band (16:9 screens) has room for the normal form. */
fun bandForm(vertical: Boolean, thicknessDp: Float): ViewForm = when {
    vertical && thicknessDp >= 180f -> ViewForm.Corner
    vertical -> ViewForm.Column
    thicknessDp >= 140f -> ViewForm.Corner
    else -> ViewForm.Strip
}

/**
 * The band beside (landscape) or above (portrait) the 3:2 photo. Its size is fixed; [fraction] (read in the draw
 * phase only) slides it out of the screen edge and fades it as the band collapses, so the animation never
 * recomposes the slideshow.
 */
@Composable
fun PluginBand(
    band: BandGeometry.Band,
    fraction: () -> Float,
    states: List<InstanceState>,
    background: BandBackground,
    photo: ImageBitmap?,
    onTap: (InstanceState) -> Unit,
    clock: (@Composable (ViewForm, PluginPalette) -> Unit)?,
) {
    val d = LocalDensity.current
    val thicknessDp = with(d) { band.thickness.toDp() }
    val form = bandForm(band.vertical, thicknessDp.value)
    val palette = if (background == BandBackground.Paper) PluginPalette.onPaper(Kin.colors) else PluginPalette.OnPhoto
    val paper = Kin.colors.paper
    Box(
        Modifier
            .offset { IntOffset(band.band.left, band.band.top) }
            .size(with(d) { band.band.width.toDp() }, with(d) { band.band.height.toDp() })
            .graphicsLayer {
                val f = fraction()
                alpha = f
                val out = band.thickness * (1f - f)
                when (band.edge) {
                    BandGeometry.Edge.Left -> translationX = -out
                    BandGeometry.Edge.Right -> translationX = out
                    BandGeometry.Edge.Top -> translationY = -out
                }
            },
    ) {
        if (background == BandBackground.Paper) Box(Modifier.fillMaxSize().background(paper))
        else PhotoWash(photo, band.edge)
        val items = states.forBand()
        if (band.vertical) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 24.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    ScaleToFit(maxWidth - 4.dp, maxHeight, wrapFactor = 1f) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items.forEachIndexed { i, s -> BandItem(s, form, palette, onTap, divider = i > 0, verticalBand = true) }
                        }
                    }
                }
                clock?.invoke(form, palette)
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    // A strip that is a little too full shrinks (to 0.75 at most) rather than cutting off the last widget.
                    ScaleToFit(maxWidth, maxHeight, wrapFactor = 1.33f) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            items.forEachIndexed { i, s -> BandItem(s, form, palette, onTap, divider = i > 0, verticalBand = false) }
                        }
                    }
                }
                clock?.invoke(form, palette)
            }
        }
    }
}

@Composable
private fun BandItem(s: InstanceState, form: ViewForm, palette: PluginPalette, onTap: (InstanceState) -> Unit, divider: Boolean, verticalBand: Boolean) {
    AnimatedVisibility(s.shown && (s.view != null || s.status == InstanceStatus.Unavailable), enter = fadeIn(tween(500)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(300))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (divider && verticalBand) Box(Modifier.padding(bottom = 14.dp).width(36.dp).height(1.dp).background(palette.faint))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (divider && !verticalBand) Box(Modifier.padding(end = 20.dp).width(1.dp).height(40.dp).background(palette.faint))
                InstanceContent(
                    s, form, palette,
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onTap(s) },
                )
            }
        }
    }
}

/**
 * The "from the photo" band background: the photo's edge next to the band, shrunk to a few pixels and stretched back
 * with bilinear filtering (a blur for free), darkened for white text. Built once per photo.
 */
@Composable
private fun PhotoWash(photo: ImageBitmap?, edge: BandGeometry.Edge) {
    // A few hundred sampled pixels: cheap enough to do in composition, once per photo.
    val tiny = remember(photo, edge) { photo?.let { runCatching { edgeColours(it, edge) }.getOrNull() } }
    var current by remember { mutableStateOf(tiny) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(tiny) {
        if (tiny == null || tiny == current) return@LaunchedEffect
        previous = current
        current = tiny
        fade.snapTo(0f)
        fade.animateTo(1f, tween(800))
        previous = null
    }
    val night = Kin.colors.night
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(night)
            fun wash(img: ImageBitmap, a: Float) {
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(size.width.toInt() + 1, size.height.toInt() + 1), alpha = a, filterQuality = FilterQuality.Low)
            }
            previous?.let { wash(it, 1f) }
            current?.let { wash(it, if (previous != null) fade.value else 1f) }
            drawRect(Color.Black.copy(alpha = 0.38f))
        },
    )
}

/** A 1×8 (column) or 8×1 (strip) image of the photo's colours along the edge that touches the band. */
private fun edgeColours(src: ImageBitmap, edge: BandGeometry.Edge): ImageBitmap {
    val bmp = src.asAndroidBitmap()
    val small = Bitmap.createScaledBitmap(bmp, 24, 16, true)
    val tiny = Bitmap.createScaledBitmap(small, 6, 8, true)
    val out = when (edge) {
        BandGeometry.Edge.Left -> Bitmap.createBitmap(tiny, 0, 0, 1, tiny.height)
        BandGeometry.Edge.Right -> Bitmap.createBitmap(tiny, tiny.width - 1, 0, 1, tiny.height)
        BandGeometry.Edge.Top -> Bitmap.createScaledBitmap(Bitmap.createBitmap(small, 0, 0, small.width, 2), 8, 1, true)
    }
    if (small != bmp) small.recycle()
    return out.asImageBitmap()
}

// ---------------------------------------------------------------------------------------------- detail card

/** The larger view on tap (§2): closed by tapping outside it or after 20 s. */
@Composable
fun PluginDetailCard(state: InstanceState?, detail: PluginView?, onClose: () -> Unit) {
    AnimatedVisibility(state != null, enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f), exit = fadeOut(tween(200))) {
        var shown by remember { mutableStateOf(state) }
        if (state != null) shown = state
        val s = shown ?: return@AnimatedVisibility
        LaunchedEffect(s.config.instanceId) { delay(DETAIL_MS); onClose() }
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val maxW = maxWidth * 0.84f
            val maxH = maxHeight * 0.84f
            Column(
                Modifier.widthIn(max = maxW).clip(KinShapeL).background(Kin.colors.surface)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 36.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = Kin.type.title, color = Kin.colors.ink, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(24.dp))
                    if (s.status == InstanceStatus.Stale) KinIcon(KinIcons.WifiOff, null, tint = Kin.colors.inkFaint, size = 22.dp)
                }
                Spacer(Modifier.height(20.dp))
                val v = detail ?: s.view
                if (v != null) {
                    // Wide cards (two charts side by side) scale down on a portrait screen instead of squeezing their last column.
                    ScaleToFit(maxW - 72.dp, maxH - 120.dp, wrapFactor = 2f) { PluginViewContent(v, ViewForm.Detail, PluginPalette.onPaper(Kin.colors)) }
                } else {
                    Text(stringResource(R.string.plugins_unavailable), style = Kin.type.body, color = Kin.colors.inkSoft)
                }
            }
        }
    }
}

const val DETAIL_MS = 20_000L
