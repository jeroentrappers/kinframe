package app.kinframe.family.ui.send

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.send.Framing
import app.kinframe.family.send.TrimHandle
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SwitchRow
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import kotlin.math.roundToInt

/** Per-item framing, caption and trim (product spec 4.3 "Adjust"), full screen over the Send tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditor(
    state: ComposerState,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onCaption: (String, String) -> Unit,
    onApplyAll: (String) -> Unit,
    onFill: (String, Boolean) -> Unit,
    onFocal: (String, Float, Float) -> Unit,
    onTrim: (String, Long, Long, TrimHandle) -> Unit,
    onAudio: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    val item = state.items.getOrNull(index) ?: return
    val c = Kin.colors
    val count = state.items.size
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(
            stringResource(if (item.isVideo) R.string.send_video_n else R.string.send_photo_n, index + 1) + " / $count",
            onBack = onClose,
        ) {
            IconButton(onClick = { onIndex(index - 1) }, enabled = index > 0) {
                Icon(KinIcons.ChevronLeft, stringResource(R.string.cmd_previous), tint = if (index > 0) c.ink else c.inkFaint)
            }
            IconButton(onClick = { onIndex(index + 1) }, enabled = index < count - 1) {
                Icon(KinIcons.ChevronRight, stringResource(R.string.cmd_next), tint = if (index < count - 1) c.ink else c.inkFaint)
            }
        }
        Box(Modifier.weight(1f).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val issue = state.issue(item)
                if (issue == ItemIssue.TooLarge) Note(stringResource(R.string.send_issue_too_large, Format.bytes(LocalContext.current, state.limits.maxUploadBytes)), tone = NoteTone.Danger)
                if (issue == ItemIssue.TooLong) Note(stringResource(R.string.send_issue_too_long, Format.clock(state.limits.maxVideoSeconds * 1000L)), tone = NoteTone.Danger)
                if (issue == ItemIssue.TooShort) Note(stringResource(R.string.send_trim_min), tone = NoteTone.Danger)

                FocalPicker(item, enabled = item.fill) { x, y -> onFocal(item.id, x, y) }
                if (item.fill) Text(stringResource(R.string.send_focal_hint), style = Kin.type.caption, color = c.inkSoft)

                SectionTitle(stringResource(R.string.send_fit_legend))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(stringResource(R.string.send_fill), item.fill, { onFill(item.id, true) })
                    ToggleChip(stringResource(R.string.send_fit), !item.fill, { onFill(item.id, false) })
                }
                Text(stringResource(if (item.fill) R.string.send_fill_hint else R.string.send_fit_hint), style = Kin.type.caption, color = c.inkSoft)

                Text(stringResource(R.string.send_previews_label), style = Kin.type.label, color = c.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FramePreview(item, Framing.LANDSCAPE, Modifier.fillMaxWidth())
                        Text(stringResource(R.string.send_landscape), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(Modifier.weight(0.75f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FramePreview(item, Framing.PORTRAIT, Modifier.fillMaxWidth())
                        Text(stringResource(R.string.send_portrait), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                if (item.isVideo && item.info.durationMs != null) TrimEditor(item, state.limits.maxVideoSeconds * 1000L, onTrim, onAudio)

                SectionTitle(stringResource(R.string.send_caption))
                KinTextField(
                    item.caption, { onCaption(item.id, it) }, stringResource(R.string.send_caption),
                    placeholder = stringResource(R.string.send_caption_placeholder),
                    singleLine = false, minLines = 2,
                    supporting = stringResource(R.string.send_chars_left, ComposerViewModel.CAPTION_MAX - item.caption.length, ComposerViewModel.CAPTION_MAX),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (count > 1 && item.caption.isNotBlank()) {
                    QuietButton(stringResource(R.string.send_apply_all, count), { onApplyAll(item.id) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.common_done), onClose, Modifier.weight(1f))
                    QuietButton(stringResource(R.string.common_remove), { onRemove(item.id) }, danger = false)
                }
            }
        }
    }
}

/** The whole photo; tap to set the point the frame keeps in view when it crops. */
@Composable
private fun FocalPicker(item: DraftItem, enabled: Boolean, onFocal: (Float, Float) -> Unit) {
    val c = Kin.colors
    val aspect = item.aspect ?: (4f / 3f)
    val desc = stringResource(R.string.a11y_focal_picker)
    val value = stringResource(R.string.send_focal_value, (item.focalX * 100).roundToInt(), (item.focalY * 100).roundToInt())
    val left = stringResource(R.string.cmd_previous)
    val right = stringResource(R.string.cmd_next)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 420.dp)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(16.dp))
                .background(c.paperDeep)
                .pointerInput(enabled, item.id) {
                    if (enabled) detectTapGestures { o -> onFocal(o.x / size.width, o.y / size.height) }
                }
                .semantics {
                    contentDescription = desc
                    if (enabled) {
                        stateDescription = value
                        customActions = listOf(
                            CustomAccessibilityAction(left) { onFocal(item.focalX - 0.1f, item.focalY); true },
                            CustomAccessibilityAction(right) { onFocal(item.focalX + 0.1f, item.focalY); true },
                        )
                    }
                },
        ) {
            AsyncImage(model = item.info.uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (enabled) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = Offset(item.focalX * size.width, item.focalY * size.height)
                    drawCircle(Color.Black.copy(alpha = 0.35f), 26.dp.toPx(), p, style = Stroke(6.dp.toPx()))
                    drawCircle(Color.White, 24.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 4.dp.toPx(), p)
                }
            }
        }
    }
}

/** How the item looks on a frame of [boxAspect]: cropped at the focal point, or whole with soft edges. */
@Composable
fun FramePreview(item: DraftItem, boxAspect: Float, modifier: Modifier = Modifier) {
    val imageAspect = item.aspect ?: (4f / 3f)
    Box(
        modifier.aspectRatio(boxAspect).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2F2823)).padding(4.dp)
            .clip(RoundedCornerShape(6.dp)).background(Color.Black),
    ) {
        if (item.fill) {
            val (bx, by) = Framing.coverBias(item.focalX, item.focalY, imageAspect, boxAspect)
            AsyncImage(item.info.uri, null, contentScale = ContentScale.Crop, alignment = BiasAlignment(bx, by), modifier = Modifier.fillMaxSize())
        } else {
            val blurred = if (Build.VERSION.SDK_INT >= 31) Modifier.fillMaxSize().blur(18.dp) else Modifier.fillMaxSize()
            AsyncImage(item.info.uri, null, contentScale = ContentScale.Crop, modifier = blurred)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            AsyncImage(item.info.uri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimEditor(item: DraftItem, maxMs: Long, onTrim: (String, Long, Long, TrimHandle) -> Unit, onAudio: (String, Boolean) -> Unit) {
    val c = Kin.colors
    val duration = item.info.durationMs ?: return
    val (start, end) = item.trim ?: (0L to duration)
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.send_trim))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(start, end).forEach { ms ->
            AsyncImage(
                ImageRequest.Builder(context).data(item.info.uri).videoFrameMillis(ms).build(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.weight(1f).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(c.paperDeep),
            )
        }
    }
    val label = stringResource(R.string.send_trim_label)
    RangeSlider(
        value = start.toFloat()..end.toFloat(),
        onValueChange = { r ->
            val moved = if (r.start.toLong() != start) TrimHandle.Start else TrimHandle.End
            onTrim(item.id, r.start.toLong(), r.endInclusive.toLong(), moved)
        },
        valueRange = 0f..duration.toFloat(),
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
        modifier = Modifier.semantics { contentDescription = label },
    )
    Text(
        stringResource(R.string.send_trim_range, Format.clock(start), Format.clock(end), Format.clock(end - start)),
        style = Kin.type.label, color = c.ink,
    )
    if (duration > maxMs) Text(stringResource(R.string.send_issue_too_long, Format.clock(maxMs)), style = Kin.type.caption, color = c.inkSoft)
    SwitchRow(stringResource(R.string.send_include_audio), item.includeAudio, { onAudio(item.id, it) })
}

/** Caption applied to every item: the snackbar text. */
@Composable
fun captionAppliedText(count: Int) = pluralStringResource(R.plurals.send_applied_all, count, count)
