package app.kinframe.family.ui.send

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kinframe.core.design.Kin
import app.kinframe.family.R
import app.kinframe.family.send.Framing
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.currentLocale
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/*
 * Zoom and position per orientation (docs/spec/framing.md §4): the small previews show the result with the §3
 * geometry, a tap opens a large editor with the frame shape as a window over the photo.
 */

/** The photo cropped like a frame of [boxAspect] shows it for [view] (docs/spec/framing.md §3). */
@Composable
fun FramedImage(model: Any?, imageAspect: Float, boxAspect: Float, view: Framing.View, modifier: Modifier = Modifier) {
    val v = Framing.visible(view, imageAspect, boxAspect)
    Box(modifier.aspectRatio(boxAspect).clipToBounds()) {
        AsyncImage(
            model, null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().layout { m, c ->
                // The whole photo at the size where its visible part fills the box, moved so that part is in view.
                val bw = c.maxWidth
                val bh = c.maxHeight
                val iw = (bw / v.width).roundToInt().coerceAtLeast(1)
                val ih = (bh / v.height).roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(iw, ih))
                layout(bw, bh) { p.place(-(v.left * iw).roundToInt(), -(v.top * ih).roundToInt()) }
            },
        )
    }
}

/** How a photo looks on a frame of [boxAspect]: cropped to its view (fill), or whole with soft edges (fit). */
@Composable
fun FramePreview(model: Any?, imageAspect: Float, fill: Boolean, view: Framing.View, boxAspect: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(boxAspect).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2F2823)).padding(4.dp)
            .clip(RoundedCornerShape(6.dp)).background(Color.Black),
    ) {
        if (fill) {
            FramedImage(model, imageAspect, boxAspect, view, Modifier.fillMaxSize())
        } else {
            val blurred = if (Build.VERSION.SDK_INT >= 31) Modifier.fillMaxSize().blur(18.dp) else Modifier.fillMaxSize()
            AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = blurred)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            AsyncImage(model, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The landscape (16:10) and portrait (10:16) previews side by side. With [onEdit] (framing on and fill mode) a tap on
 * one opens its large editor.
 */
@Composable
fun FramePreviews(
    model: Any?, imageAspect: Float, fill: Boolean, landscape: Framing.View, portrait: Framing.View,
    onEdit: ((landscape: Boolean) -> Unit)? = null,
) {
    val c = Kin.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        listOf(true, false).forEach { isLandscape ->
            val label = stringResource(if (isLandscape) R.string.send_landscape else R.string.send_portrait)
            val desc = stringResource(R.string.a11y_framing_preview, label)
            val edit = stringResource(R.string.a11y_framing_edit)
            Column(Modifier.weight(if (isLandscape) 1.6f else 0.75f), horizontalAlignment = Alignment.CenterHorizontally) {
                FramePreview(
                    model, imageAspect, fill, if (isLandscape) landscape else portrait,
                    if (isLandscape) Framing.LANDSCAPE else Framing.PORTRAIT,
                    Modifier.fillMaxWidth().then(
                        if (onEdit == null) Modifier
                        else Modifier.clickable(onClickLabel = edit, role = Role.Button) { onEdit(isLandscape) }.semantics { contentDescription = desc },
                    ),
                )
                Text(label, style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** "2×", "1.5×" (one decimal at most, in the person's locale). */
@Composable
fun zoomLabel(zoom: Float): String {
    val f = java.text.NumberFormat.getNumberInstance(currentLocale()).apply { maximumFractionDigits = 1 }
    return stringResource(R.string.send_framing_zoom_value, f.format(zoom))
}

/**
 * The large editor for one orientation (docs/spec/framing.md §4): the whole photo with the frame-shaped window over
 * it. Drag (or tap) to move the window, pinch or the slider to zoom from 1× to 4×; the window never leaves the photo.
 * [initial] is the orientation's view, null when it has none (the focal point [focalX], [focalY] at 1×). "Done" calls
 * [onDone] with the view when the person changed something, "Reset" then "Done" calls [onReset] (the view is removed);
 * anything else just closes.
 */
@Composable
fun FramingDialog(
    model: Any?,
    imageAspect: Float,
    width: Int?,
    height: Int?,
    landscape: Boolean,
    initial: Framing.View?,
    focalX: Float,
    focalY: Float,
    onDone: (Framing.View) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Kin.colors
    val box = if (landscape) Framing.LANDSCAPE else Framing.PORTRAIT
    val default = remember(focalX, focalY, imageAspect, box) { Framing.clampView(Framing.View(focalX, focalY), imageAspect, box) }
    var view by remember { mutableStateOf(initial ?: default) }
    var touched by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    fun move(v: Framing.View, round: Boolean = false) {
        view = Framing.clampView(v, imageAspect, box, round)
        touched = true
    }
    fun done() = when {
        touched -> onDone(Framing.clampView(view, imageAspect, box))
        cleared -> onReset()
        else -> onDismiss()
    }

    val visible = Framing.visible(view, imageAspect, box)
    val orientation = stringResource(if (landscape) R.string.send_landscape else R.string.send_portrait)
    val zoomText = zoomLabel(view.zoom)
    val value = stringResource(R.string.a11y_framing_value, orientation, zoomText, (view.x * 100).roundToInt(), (view.y * 100).roundToInt())
    val desc = stringResource(R.string.a11y_framing_window)
    val zoomIn = stringResource(R.string.a11y_framing_zoom_in)
    val zoomOut = stringResource(R.string.a11y_framing_zoom_out)
    val left = stringResource(R.string.a11y_framing_move_left)
    val right = stringResource(R.string.a11y_framing_move_right)
    val up = stringResource(R.string.a11y_framing_move_up)
    val down = stringResource(R.string.a11y_framing_move_down)
    val reset = stringResource(R.string.send_framing_reset)
    fun doReset() { view = default; touched = false; cleared = true }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.paper).navigationBarsPadding()) {
            KinTopBar(stringResource(if (landscape) R.string.send_framing_title_landscape else R.string.send_framing_title_portrait), onBack = onDismiss)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(imageAspect)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.paperDeep)
                        .pointerInput(imageAspect, box) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val v = view
                                move(v.copy(x = v.x + pan.x / size.width, y = v.y + pan.y / size.height, zoom = v.zoom * zoom))
                            }
                        }
                        .pointerInput(imageAspect, box) {
                            detectTapGestures { o -> move(view.copy(x = o.x / size.width, y = o.y / size.height)) }
                        }
                        .semantics {
                            contentDescription = desc
                            stateDescription = value
                            // 10% of the visible part per step (docs/spec/framing.md §4).
                            customActions = listOf(
                                CustomAccessibilityAction(zoomIn) { move(view.copy(zoom = view.zoom + 0.5f), round = true); true },
                                CustomAccessibilityAction(zoomOut) { move(view.copy(zoom = view.zoom - 0.5f), round = true); true },
                                CustomAccessibilityAction(left) { move(view.copy(x = visible.centerX - visible.width * 0.1f), round = true); true },
                                CustomAccessibilityAction(right) { move(view.copy(x = visible.centerX + visible.width * 0.1f), round = true); true },
                                CustomAccessibilityAction(up) { move(view.copy(y = visible.centerY - visible.height * 0.1f), round = true); true },
                                CustomAccessibilityAction(down) { move(view.copy(y = visible.centerY + visible.height * 0.1f), round = true); true },
                                CustomAccessibilityAction(reset) { doReset(); true },
                            )
                        },
                ) {
                    AsyncImage(model, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val tl = Offset(visible.left * size.width, visible.top * size.height)
                        val ws = Size(visible.width * size.width, visible.height * size.height)
                        // Everything the frame won't show is dimmed; the window is the frame's shape.
                        clipRect(tl.x, tl.y, tl.x + ws.width, tl.y + ws.height, clipOp = ClipOp.Difference) {
                            drawRect(Color.Black.copy(alpha = 0.55f))
                        }
                        drawRect(Color.Black.copy(alpha = 0.35f), tl, ws, style = Stroke(5.dp.toPx()))
                        drawRect(Color.White, tl, ws, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.send_framing_help), style = Kin.type.caption, color = c.inkSoft)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val zoomName = stringResource(R.string.send_framing_zoom)
                        Text(zoomName, style = Kin.type.label, color = c.ink)
                        Slider(
                            value = view.zoom, onValueChange = { move(view.copy(zoom = it)) },
                            onValueChangeFinished = { view = Framing.clampView(view, imageAspect, box) },
                            valueRange = Framing.MIN_ZOOM..Framing.MAX_ZOOM,
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
                            modifier = Modifier.weight(1f).semantics { contentDescription = zoomName },
                        )
                        Text(zoomText, style = Kin.type.label, color = c.ink)
                    }
                    if (Framing.mayLookSoft(view, width, height, box)) Note(stringResource(R.string.send_framing_soft), tone = NoteTone.Warn)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        QuietButton(reset, { doReset() }, enabled = touched || (initial != null && !cleared))
                        PrimaryButton(stringResource(R.string.common_done), { done() }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Zoom and position of a photo already sent (`PATCH /v1/items/{id}`, docs/spec/framing.md §4): its two previews, a
 * tap opens the large editor, "Save" hands the whole new framing to [onSave] (null when no view is left).
 */
@Composable
fun SentFramingDialog(
    model: Any?,
    imageAspect: Float,
    width: Int?,
    height: Int?,
    focalX: Float,
    focalY: Float,
    initial: app.kinframe.core.api.Framing?,
    saving: Boolean,
    onSave: (app.kinframe.core.api.Framing?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Kin.colors
    fun of(v: app.kinframe.core.api.FramingView?) = v?.let { Framing.View(it.x.toFloat(), it.y.toFloat(), it.zoom.toFloat()) }
    val start = remember(initial) { of(initial?.landscape) to of(initial?.portrait) }
    var landscape by remember(initial) { mutableStateOf(start.first) }
    var portrait by remember(initial) { mutableStateOf(start.second) }
    var editing by remember { mutableStateOf<Boolean?>(null) }
    val focal = Framing.View(focalX, focalY)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = ContentMaxWidth).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.detail_framing), style = Kin.type.title, color = c.ink)
            Text(stringResource(R.string.send_previews_label), style = Kin.type.label, color = c.inkSoft)
            FramePreviews(model, imageAspect, fill = true, landscape ?: focal, portrait ?: focal, onEdit = { editing = it })
            Text(stringResource(R.string.send_framing_hint), style = Kin.type.caption, color = c.inkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(stringResource(R.string.common_cancel), onDismiss)
                PrimaryButton(
                    stringResource(R.string.common_save),
                    {
                        fun v(f: Framing.View?) = f?.let { app.kinframe.core.api.FramingView(it.x.toDouble(), it.y.toDouble(), it.zoom.toDouble()) }
                        onSave(if (landscape == null && portrait == null) null else app.kinframe.core.api.Framing(v(landscape), v(portrait)))
                    },
                    Modifier.weight(1f), enabled = (landscape to portrait) != start, busy = saving,
                )
            }
        }
    }
    editing?.let { isLandscape ->
        val box = if (isLandscape) Framing.LANDSCAPE else Framing.PORTRAIT
        FramingDialog(
            model, imageAspect, width, height, isLandscape,
            initial = if (isLandscape) landscape else portrait, focalX = focalX, focalY = focalY,
            onDone = { v -> val k = Framing.clampView(v, imageAspect, box); if (isLandscape) landscape = k else portrait = k; editing = null },
            onReset = { if (isLandscape) landscape = null else portrait = null; editing = null },
            onDismiss = { editing = null },
        )
    }
}
