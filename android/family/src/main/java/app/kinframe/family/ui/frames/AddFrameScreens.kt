package app.kinframe.family.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.api.InvitePreview
import app.kinframe.core.api.RedeemResult
import app.kinframe.core.api.RedeemStatus
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
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
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SkeletonList
import app.kinframe.family.util.FrameCodes
import kotlinx.coroutines.launch

@Composable
fun AddFrameScreen(
    state: AddFrameState,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onLocation: (String) -> Unit,
    onSaveName: () -> Unit,
    onSend: (String) -> Unit,
    onViewFrame: (String) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        KinTopBar(stringResource(R.string.add_title), onBack = onBack)
        Box(Modifier.weight(1f).imePadding()) {
            val r = state.result
            if (r == null) CodeEntry(state, onInput, onSubmit)
            else RedeemResultContent(r, state, onName, onLocation, onSaveName, onSend, onViewFrame, onDone)
        }
    }
}

@Composable
private fun CodeEntry(state: AddFrameState, onInput: (String) -> Unit, onSubmit: () -> Unit) {
    val c = Kin.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val spoken = FrameCodes.spoken(state.digits)
    PageColumn {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 150.dp) }
        Text(stringResource(R.string.add_body), style = Kin.type.body, color = c.inkSoft)
        KinTextField(
            value = state.digits,
            onValueChange = onInput,
            label = stringResource(R.string.add_code_label),
            placeholder = "1234 5678",
            supporting = stringResource(R.string.add_code_hint),
            error = state.error?.let { stringResource(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            visualTransformation = FrameCodes.visualTransformation,
            textStyle = Kin.type.code.copy(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 4.sp),
            modifier = Modifier.focusRequester(focus).semantics { stateDescription = spoken },
            trailing = {
                IconButton(onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (text != null) onInput(text)
                    }
                }) { Icon(KinIcons.Clipboard, stringResource(R.string.add_paste), tint = c.inkSoft) }
            },
        )
        PrimaryButton(
            stringResource(if (state.submitting) R.string.add_checking else R.string.add_submit), onSubmit,
            Modifier.fillMaxWidth(), enabled = state.complete, busy = state.submitting,
        )
    }
}

@Composable
private fun RedeemResultContent(
    r: RedeemResult,
    state: AddFrameState,
    onName: (String) -> Unit,
    onLocation: (String) -> Unit,
    onSaveName: () -> Unit,
    onSend: (String) -> Unit,
    onViewFrame: (String) -> Unit,
    onDone: () -> Unit,
) {
    val c = Kin.colors
    val frame = r.frame
    PageColumn(spacing = 18.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 170.dp, portrait = frame.screen?.orientation == "portrait") }
        when (r.status) {
            RedeemStatus.CLAIMED -> {
                Title(stringResource(R.string.add_claimed_title))
                Text(stringResource(R.string.add_claimed_body), style = Kin.type.body, color = c.inkSoft)
                KinTextField(state.name, onName, stringResource(R.string.frame_name), placeholder = stringResource(R.string.add_name_placeholder),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next))
                KinTextField(state.location, onLocation, stringResource(R.string.frame_location), placeholder = stringResource(R.string.add_location_placeholder),
                    supporting = stringResource(R.string.common_optional),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveName() }))
                state.saveError?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                PrimaryButton(stringResource(R.string.add_save_and_send), onSaveName, Modifier.fillMaxWidth(), enabled = state.name.isNotBlank(), busy = state.saving, icon = KinIcons.Send)
                QuietButton(stringResource(R.string.add_later), { onViewFrame(frame.id) }, Modifier.fillMaxWidth())
            }
            RedeemStatus.JOINED -> {
                Title(stringResource(R.string.add_joined_title, frame.name))
                Text(stringResource(R.string.add_joined_body, frame.ownerName ?: stringResource(R.string.role_owner)), style = Kin.type.body, color = c.inkSoft)
                PrimaryButton(stringResource(R.string.add_send_first), { onSend(frame.id) }, Modifier.fillMaxWidth(), icon = KinIcons.Send)
                SecondaryButton(stringResource(R.string.add_view_frame), { onViewFrame(frame.id) }, Modifier.fillMaxWidth())
            }
            RedeemStatus.PENDING -> {
                Title(stringResource(R.string.add_pending_title))
                Text(stringResource(R.string.add_pending_body, frame.name), style = Kin.type.body, color = c.inkSoft)
                PrimaryButton(stringResource(R.string.add_back_to_frames), onDone, Modifier.fillMaxWidth())
            }
            else -> {
                Title(stringResource(R.string.add_already_title))
                Text(stringResource(R.string.add_already_body, frame.name), style = Kin.type.body, color = c.inkSoft)
                PrimaryButton(stringResource(R.string.add_view_frame), { onViewFrame(frame.id) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = Kin.type.headline, color = Kin.colors.ink, textAlign = TextAlign.Start, modifier = Modifier.semantics { heading() })
}

@Composable
fun InviteScreen(
    state: InviteState,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onViewFrame: (String) -> Unit,
) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(stringResource(R.string.invite_title), onBack = onBack)
        when (val p = state.preview) {
            Load.Loading -> SkeletonList(2, 120.dp)
            is Load.Error -> if (state.invalid) {
                EmptyState(stringResource(R.string.invite_invalid_title), stringResource(p.message)) {
                    SecondaryButton(stringResource(R.string.add_back_to_frames), onBack)
                }
            } else ErrorPanel(p.message, onRetry)
            is Load.Ready -> InviteContent(p.value, state, onAccept, onBack, onSend, onViewFrame)
        }
    }
}

@Composable
private fun InviteContent(
    preview: InvitePreview,
    state: InviteState,
    onAccept: () -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onViewFrame: (String) -> Unit,
) {
    val c = Kin.colors
    val result = state.result
    PageColumn(spacing = 18.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 170.dp) }
        Text(stringResource(R.string.invite_from, preview.invitedByName), style = Kin.type.caption, color = c.inkSoft)
        Title(stringResource(R.string.invite_join, preview.frameName))
        preview.frameLocation?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
        when {
            result?.status == RedeemStatus.PENDING -> {
                Note(stringResource(R.string.invite_pending_body, preview.ownerName ?: preview.invitedByName), tone = NoteTone.Good, icon = KinIcons.Clock)
                PrimaryButton(stringResource(R.string.add_back_to_frames), onBack, Modifier.fillMaxWidth())
            }
            result != null -> {
                Note(stringResource(R.string.invite_joined_body), tone = NoteTone.Good, icon = KinIcons.Check)
                PrimaryButton(stringResource(R.string.add_send_first), { onSend(result.frame.id) }, Modifier.fillMaxWidth(), icon = KinIcons.Send)
                SecondaryButton(stringResource(R.string.add_view_frame), { onViewFrame(result.frame.id) }, Modifier.fillMaxWidth())
            }
            preview.alreadyMember -> {
                Note(stringResource(R.string.invite_already))
                SecondaryButton(stringResource(R.string.add_back_to_frames), onBack, Modifier.fillMaxWidth())
            }
            else -> {
                Text(
                    if (preview.requiresApproval) stringResource(R.string.invite_needs_approval, preview.ownerName ?: preview.invitedByName)
                    else stringResource(R.string.invite_instant),
                    style = Kin.type.body, color = c.inkSoft,
                )
                state.error?.let { Note(stringResource(it), tone = NoteTone.Danger) }
                PrimaryButton(stringResource(R.string.invite_accept), onAccept, Modifier.fillMaxWidth(), busy = state.accepting)
                QuietButton(stringResource(R.string.invite_not_now), onBack, Modifier.fillMaxWidth())
            }
        }
    }
}
