package app.kinframe.family.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Item
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.Send
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.history.Delivery
import app.kinframe.family.history.FrameDelivery
import app.kinframe.family.history.Stage
import app.kinframe.family.send.OutboxProgress
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.OutboxState
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.EmptyState
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.ui.StagePill
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.Thumb
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import app.kinframe.family.util.Problems

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    state: HistoryState,
    outbox: List<OutboxSend>,
    progress: Map<String, OutboxProgress>,
    onFilter: (HistoryFilter) -> Unit,
    onOpen: (Send) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryOutbox: (String) -> Unit,
    onDiscardOutbox: (String) -> Unit,
) {
    val c = Kin.colors
    val context = LocalContext.current
    PullToRefreshBox(state.refreshing, onRefresh, Modifier.fillMaxSize().background(c.paper)) {
        val list = (state.sends as? Load.Ready)?.value
        val lazy = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(lazy, list?.size) {
            snapshotFlow { lazy.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                if (last >= lazy.layoutInfo.totalItemsCount - 3) onLoadMore()
            }
        }
        LazyColumn(
            state = lazy,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.history_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.history_lede), style = Kin.type.caption, color = c.inkSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryFilter.entries.forEach { f ->
                            ToggleChip(
                                stringResource(when (f) { HistoryFilter.All -> R.string.history_filter_all; HistoryFilter.Ongoing -> R.string.history_filter_ongoing; HistoryFilter.Delivered -> R.string.history_filter_delivered }),
                                state.filter == f, { onFilter(f) },
                            )
                        }
                    }
                }
            }
            items(outbox, key = { "o-" + it.id }) { o ->
                Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    OutboxCard(o, progress[o.id], { onRetryOutbox(o.id) }, { onDiscardOutbox(o.id) })
                }
            }
            when (val s = state.sends) {
                Load.Loading -> item { SkeletonList(3, 150.dp) }
                is Load.Error -> item { ErrorPanel(s.message, onRefresh) }
                is Load.Ready -> if (s.value.isEmpty() && outbox.isEmpty()) {
                    item {
                        if (state.filter == HistoryFilter.All) EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body), art = { FrameArt(width = 160.dp) })
                        else EmptyState(stringResource(R.string.history_empty_filtered), "", art = {})
                    }
                } else {
                    var lastDay = ""
                    s.value.forEach { send ->
                        val day = Format.day(context, send.createdAt)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "d-$day-${send.id}") {
                                Text(day, style = Kin.type.label, color = c.inkSoft,
                                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() })
                            }
                        }
                        item(key = send.id) {
                            Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) { SendCard(send) { onOpen(send) } }
                        }
                    }
                    if (state.loadingMore) item { SkeletonList(1, 120.dp) }
                }
            }
        }
    }
}

@Composable
fun OutboxCard(o: OutboxSend, p: OutboxProgress?, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumb(o.items.firstOrNull()?.let { it.localPath?.let { p -> java.io.File(p) } ?: android.net.Uri.parse(it.uri) }, null, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notif_sending_title, o.frameNames.joinToString(", ")), style = Kin.type.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val text = when {
                    o.state == OutboxState.FAILED -> stringResource(Problems.byCode(o.error) ?: R.string.outbox_failed)
                    p != null -> stringResource(R.string.outbox_uploading, p.itemIndex + 1, p.itemCount, (p.fraction * 100).toInt())
                    o.error == "network" -> stringResource(R.string.outbox_waiting_network)
                    else -> pluralStringResource(R.plurals.notif_sending_items, o.items.size, o.items.size)
                }
                Text(text, style = Kin.type.caption, color = if (o.state == OutboxState.FAILED) c.danger else c.inkSoft)
            }
        }
        if (o.state != OutboxState.FAILED) {
            val frac = p?.fraction ?: (o.uploadedBytes.toFloat() / o.totalBytes.coerceAtLeast(1))
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = c.accent, trackColor = c.line)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.outbox_retry), onRetry, icon = KinIcons.Refresh)
                QuietButton(stringResource(R.string.outbox_discard), onDiscard)
            }
        }
    }
}

@Composable
fun SendCard(send: Send, onOpen: () -> Unit) {
    val c = Kin.colors
    val deliveries = Delivery.frames(send)
    val (photos, videos) = Delivery.kindCounts(send)
    val kinds = listOfNotNull(
        photos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.history_photos, it, it) },
        videos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.history_videos, it, it) },
    ).joinToString(" · ")
    val label = stringResource(R.string.history_open_send, Format.time(send.createdAt))
    Surface(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            send.items.take(4).forEachIndexed { i, item ->
                Box(Modifier.weight(1f).aspectRatio(1f)) {
                    Thumb(item.thumbUrl, null, Modifier.fillMaxSize())
                    if (item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, null, Modifier.align(Alignment.Center).size(22.dp), tint = Color.White)
                    if (i == 3 && send.items.size > 4) {
                        Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Text("+${send.items.size - 3}", style = Kin.type.title, color = Color.White)
                        }
                    }
                }
            }
            repeat((4 - send.items.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kinds, style = Kin.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            Text(Format.time(send.createdAt), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.semantics { contentDescriptionOf(label) })
        }
        send.items.firstOrNull { !it.caption.isNullOrBlank() }?.caption?.let {
            Text("“$it”", style = Kin.type.body, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        deliveries.forEach { d -> DeliveryRow(d, compact = true) }
    }
}

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.contentDescriptionOf(s: String) {
    contentDescription = s
}

@Composable
fun DeliveryRow(d: FrameDelivery, compact: Boolean) {
    val c = Kin.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KinIcons.Frame, null, Modifier.size(18.dp), tint = c.inkSoft)
            Text(d.frameName, style = Kin.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val total = d.counts.total - d.counts[Stage.Removed]
            val done = d.counts[Stage.Delivered] + d.counts[Stage.Seen]
            if (d.stage != Stage.Seen && d.stage != Stage.Removed && done in 1 until total) {
                Text(stringResource(R.string.history_partial, done, total), style = Kin.type.caption, color = c.inkSoft)
            }
            StagePill(d.stage)
        }
        if (d.stage != Stage.Failed && d.stage != Stage.Removed) {
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = if (d.stage == Stage.Seen) c.sage else c.accent, trackColor = c.line, drawStopIndicator = {})
        }
        if (!d.online && d.stage.rank < Stage.Delivered.rank && d.stage != Stage.Failed && !compact) {
            Text(stringResource(R.string.detail_offline_waiting), style = Kin.type.caption, color = c.inkFaint)
        }
        Reactions(d.reactions, d.replies, d.frameName)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reactions(reactions: List<String?>, replies: List<String>, frame: String) {
    val c = Kin.colors
    val tally = Delivery.tally(reactions)
    if (tally.isEmpty() && replies.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tally.forEach { (r, n) ->
            val label = stringResource(R.string.reaction_from, stringResource(Delivery.reactionLabel(r)), frame)
            Row(
                Modifier.clip(CircleShape).background(c.paperDeep).padding(horizontal = 10.dp, vertical = 4.dp).semantics(mergeDescendants = true) { contentDescriptionOf("$label ×$n") },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(Delivery.EMOJI[r].orEmpty(), style = Kin.type.body)
                if (n > 1) Text("$n", style = Kin.type.caption, color = c.inkSoft)
            }
        }
        replies.forEach { p ->
            Row(
                Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(KinIcons.Message, null, Modifier.size(14.dp), tint = c.onAccentSoft)
                Text(stringResource(Delivery.replyLabel(p)), style = Kin.type.caption, color = c.onAccentSoft)
            }
        }
    }
}

@Composable
fun SendDetailScreen(
    state: SendDetailState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEditCaption: (String) -> Unit,
    onCaption: (String) -> Unit,
    onSaveCaption: () -> Unit,
    onCancelEdit: () -> Unit,
    onUnsend: () -> Unit,
) {
    val c = Kin.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.detail_title), onBack = onBack)
        when (val s = state.send) {
            Load.Loading -> SkeletonList(3, 120.dp)
            is Load.Error -> ErrorPanel(s.message, onRetry)
            is Load.Ready -> {
                val send = s.value
                PageColumn {
                    Text(stringResource(R.string.detail_sent_at, Format.date(send.createdAt) + " " + Format.time(send.createdAt)), style = Kin.type.caption, color = c.inkSoft)
                    if (state.unsent) Note(stringResource(R.string.detail_unsent), tone = NoteTone.Good, icon = KinIcons.Check)
                    send.items.forEachIndexed { i, item -> ItemRow(i, item, state, onEditCaption, onCaption, onSaveCaption, onCancelEdit) }
                    SectionTitle(stringResource(R.string.detail_delivery_label))
                    Delivery.frames(send).forEach { d ->
                        Surface(Modifier.fillMaxWidth()) {
                            DeliveryRow(d, compact = false)
                            Text(stringResource(R.string.detail_counts, d.counts[Stage.Seen], d.counts[Stage.Delivered] + d.counts[Stage.Seen], d.counts.total),
                                style = Kin.type.caption, color = c.inkSoft)
                        }
                    }
                    state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                    if (!state.unsent) QuietButton(stringResource(R.string.detail_unsend), { confirm = true }, danger = true)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.detail_unsend_title)) },
            text = { Text(stringResource(R.string.detail_unsend_body)) },
            confirmButton = { TextButton({ confirm = false; onUnsend() }) { Text(stringResource(R.string.detail_remove_now), color = c.danger) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ItemRow(
    index: Int,
    item: Item,
    state: SendDetailState,
    onEdit: (String) -> Unit,
    onCaption: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Kin.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp)) {
            Thumb(item.thumbUrl, stringResource(if (item.kind == MediaKind.VIDEO) R.string.a11y_video else R.string.a11y_photo), Modifier.fillMaxSize())
            if (item.kind == MediaKind.VIDEO) Icon(KinIcons.Play, null, Modifier.align(Alignment.Center).size(24.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.editingItem == item.id) {
                KinTextField(state.captionDraft, onCaption, stringResource(R.string.send_caption), singleLine = false,
                    supporting = stringResource(R.string.send_chars_left, 280 - state.captionDraft.length, 280),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.common_save), onSave, busy = state.saving)
                    QuietButton(stringResource(R.string.common_cancel), onCancel)
                }
            } else {
                if (item.status == "failed") Text(stringResource(Problems.failure(item.failureCode)), style = Kin.type.caption, color = c.danger)
                Text(item.caption?.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_no_caption), style = Kin.type.body,
                    color = if (item.caption.isNullOrBlank()) c.inkFaint else c.ink)
                if (item.captionEdited) Text(stringResource(R.string.detail_edited), style = Kin.type.caption, color = c.inkFaint)
            }
        }
        if (state.editingItem != item.id) {
            IconButton({ onEdit(item.id) }) { Icon(KinIcons.Edit, stringResource(R.string.detail_edit_caption, index + 1), tint = c.inkSoft) }
        }
    }
}
