package app.kinframe.family.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.ErrorPanel
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.Pill
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SkeletonList

/** The frame picker for "Share to another frame", as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(state: ShareState, onToggle: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit) {
    if (!state.open) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kin.colors.surface) {
        ShareSheetContent(state, onToggle, onConfirm, onDismiss, onRetry)
    }
}

@Composable
fun ShareSheetContent(state: ShareState, onToggle: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
    val c = Kin.colors
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.share_action), style = Kin.type.title, color = c.ink)
        when (val t = state.targets) {
            Load.Loading -> SkeletonList(2, 56.dp)
            is Load.Error -> ErrorPanel(t.message, onRetry)
            is Load.Ready -> if (t.value.isEmpty()) {
                Text(stringResource(R.string.share_no_frames), style = Kin.type.body, color = c.inkSoft)
                QuietButton(stringResource(R.string.common_cancel), onCancel)
            } else {
                Text(stringResource(R.string.share_hint), style = Kin.type.caption, color = c.inkSoft)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.value.forEach { target -> TargetRow(target, target.frame.id in state.chosen) { onToggle(target.frame.id) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    QuietButton(stringResource(R.string.common_cancel), onCancel)
                    PrimaryButton(stringResource(R.string.share_submit), onConfirm, enabled = state.chosen.isNotEmpty(), busy = state.busy, icon = KinIcons.Share)
                }
            }
        }
    }
}

@Composable
private fun TargetRow(target: ShareTarget, checked: Boolean, onToggle: () -> Unit) {
    val c = Kin.colors
    val full = target.coverage == Coverage.All
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .border(1.5.dp, if (checked) c.accent else c.line, shape)
            .background(if (checked) c.accentSoft.copy(alpha = 0.5f) else c.surface)
            .toggleable(value = checked || full, enabled = !full, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (checked) c.accent else c.paperDeep),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (checked || full) KinIcons.Check else KinIcons.Frame, null, Modifier.size(18.dp), tint = if (checked) c.onAccent else c.inkSoft)
        }
        Text(target.frame.name, style = Kin.type.label, color = if (full) c.inkFaint else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (target.coverage) {
            Coverage.All -> Pill(stringResource(R.string.share_already_there), c.paperDeep, c.inkSoft)
            Coverage.Some -> Pill(stringResource(R.string.share_partly), c.warnSoft, c.warn)
            Coverage.None -> Unit
        }
    }
}
