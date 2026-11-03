package app.kinframe.family.ui.tv

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.CommandType
import app.kinframe.core.api.FrameCommand
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.RedeemResult
import app.kinframe.core.api.TvLink
import app.kinframe.core.api.TvLinkCreate
import app.kinframe.core.api.TvPurpose
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.ToggleChip
import app.kinframe.family.util.Format
import app.kinframe.family.util.Problems
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What to show on the TV: a selection, one album or the whole visible library (web `TvScope`). */
sealed interface TvScope {
    data object All : TvScope
    data class Album(val albumId: String, val name: String) : TvScope
    data class Selection(val ids: List<String>) : TvScope
}

enum class TvMode { Show, Mirror, Frame }

object TvLinks {
    /** Slide intervals offered, in seconds (web `TV_INTERVALS`). */
    val INTERVALS = listOf(5, 10, 15, 30, 60)
    const val DEFAULT_INTERVAL = 10

    /** The body for `POST /tv-links` (web `linkBody`): cast links last 6 h, others 12 h; a mirror has no scope. */
    fun body(scope: TvScope, interval: Int, purpose: String): TvLinkCreate {
        val base = TvLinkCreate(intervalSeconds = interval, purpose = purpose, expiresInHours = if (purpose == TvPurpose.CAST) 6 else 12)
        if (purpose == TvPurpose.MIRROR) return base
        return when (scope) {
            is TvScope.Selection -> base.copy(placementIds = scope.ids.take(500))
            is TvScope.Album -> base.copy(albumId = scope.albumId)
            TvScope.All -> base
        }
    }
}

data class TvState(
    val mode: TvMode = TvMode.Show,
    val interval: Int = TvLinks.DEFAULT_INTERVAL,
    /** The link just made for the TV's browser. */
    val link: TvLink? = null,
    val active: List<TvLink> = emptyList(),
    /** `present`, `link` or `code` while that is running. */
    val busy: String? = null,
    val code: String = "",
)

sealed interface TvNotice {
    data class Message(@StringRes val message: Int, val arg: String? = null) : TvNotice
    /** A link for a TV session is ready: hand it to the [TvPresenter]. */
    data class Present(val link: TvLink) : TvNotice
}

/** The "Show on TV" dialog's state (web `TvDialog.svelte`). */
class TvViewModel(private val api: KinApi, private val frameId: String, private val scope: TvScope) : ViewModel() {
    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<TvNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<TvNotice> = _events.asSharedFlow()

    init { loadActive() }

    private fun loadActive() = viewModelScope.launch {
        val list = runCatching { api.tvLinks(frameId) }.getOrDefault(emptyList())
        _state.update { it.copy(active = list) }
    }

    fun setMode(m: TvMode) = _state.update { it.copy(mode = m, link = null) }
    fun setInterval(s: Int) = _state.update { it.copy(interval = s) }
    fun setCode(c: String) = _state.update { it.copy(code = c.filter { ch -> ch.isDigit() }.take(8)) }

    fun makeLink() = run("link") {
        val s = state.value
        val l = api.createTvLink(frameId, TvLinks.body(scope, s.interval, if (s.mode == TvMode.Mirror) TvPurpose.MIRROR else TvPurpose.TV))
        _state.update { it.copy(link = l) }
        loadActive()
    }

    /** A short-lived link for showing on a connected screen (`cast`, or `mirror` in mirror mode). */
    fun present() = run("present") {
        val s = state.value
        val l = api.createTvLink(frameId, TvLinks.body(scope, s.interval, if (s.mode == TvMode.Mirror) TvPurpose.MIRROR else TvPurpose.CAST))
        _events.tryEmit(TvNotice.Present(l))
    }

    fun revoke(l: TvLink) = run(null) {
        api.revokeTvLink(frameId, l.id)
        _state.update { s -> s.copy(link = if (s.link?.id == l.id) null else s.link, active = s.active.filter { it.id != l.id }) }
        _events.tryEmit(TvNotice.Message(R.string.tv_revoked))
    }

    /** The mirror's remote: moves the frame (and so the TV) one photo. */
    fun remote(next: Boolean) = viewModelScope.launch {
        try {
            api.command(frameId, FrameCommand(if (next) CommandType.NEXT else CommandType.PREVIOUS))
        } catch (_: Exception) {
            _events.tryEmit(TvNotice.Message(R.string.tv_mirror_failed))
        }
    }

    /** "Use another screen": claim the frame a TV's browser shows the code of. */
    fun claim(onAdded: (RedeemResult) -> Unit) {
        val code = state.value.code
        if (code.length != 8) return
        run("code") {
            val r = api.redeem(code, UUID.randomUUID().toString())
            _state.update { it.copy(code = "") }
            r.frame?.let { _events.tryEmit(TvNotice.Message(R.string.tv_frame_added, it.name)) }
            onAdded(r)
        }
    }

    private fun run(what: String?, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        _state.update { it.copy(busy = what) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                val msg = if (what == "present" && (e as? ApiException)?.status == 404) R.string.tv_nothing else Problems.message(e)
                _events.tryEmit(TvNotice.Message(msg))
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}

/** What the "Show on TV" dialog can do besides its own state. */
class TvDialogActions(
    val vm: TvViewModel?,
    val presenter: TvPresenter.State,
    val onCopy: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onStop: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onToggle: () -> Unit,
    val onClaimed: (RedeemResult) -> Unit,
    val onClose: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvDialog(
    frameName: String,
    scope: TvScope,
    canMirror: Boolean,
    canRemote: Boolean,
    /** The server's web address, for "open … on the TV" (`<web>/tv/frame`). */
    webUrl: String?,
    state: TvState,
    a: TvDialogActions,
) {
    val c = Kin.colors
    val live = a.presenter.status != TvPresenter.Status.Idle
    Dialog(onDismissRequest = a.onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tv_title), style = Kin.type.title, color = c.ink, modifier = Modifier.weight(1f))
                IconButton(a.onClose) { Icon(KinIcons.Close, stringResource(R.string.common_close), tint = c.inkSoft) }
            }
            if (live) {
                LiveCard(a)
            } else {
                Way(KinIcons.Image, stringResource(R.string.tv_mode_show), stringResource(R.string.tv_mode_show_hint), selected = state.mode == TvMode.Show) { a.vm?.setMode(TvMode.Show) }
                Way(KinIcons.Cast, stringResource(R.string.tv_mode_mirror), stringResource(R.string.tv_mode_mirror_hint, frameName), selected = state.mode == TvMode.Mirror) { a.vm?.setMode(TvMode.Mirror) }
                Way(KinIcons.Tv, stringResource(R.string.tv_mode_frame), stringResource(R.string.tv_mode_frame_hint), selected = state.mode == TvMode.Frame) { a.vm?.setMode(TvMode.Frame) }
                HorizontalDivider(color = c.line)
            }
            if (!live && state.mode == TvMode.Show) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (scope is TvScope.Album) KinIcons.Album else KinIcons.Image, null, Modifier.size(18.dp), tint = c.inkSoft)
                    Text(
                        when (scope) {
                            TvScope.All -> stringResource(R.string.tv_scope_all, frameName)
                            is TvScope.Album -> stringResource(R.string.tv_scope_album, scope.name)
                            is TvScope.Selection -> pluralStringResource(R.plurals.tv_scope_selection, scope.ids.size, scope.ids.size)
                        },
                        style = Kin.type.body, color = c.ink,
                    )
                }
                Text(stringResource(R.string.tv_interval), style = Kin.type.label, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvLinks.INTERVALS.forEach { s -> ToggleChip(stringResource(R.string.tv_seconds, s.toString()), state.interval == s, { a.vm?.setInterval(s) }) }
                }
                Ways(state, a)
            }
            if (!live && state.mode == TvMode.Mirror) {
                if (!canMirror) Note(stringResource(R.string.tv_mirror_needs_library, frameName)) else Ways(state, a)
            }
            if (state.mode == TvMode.Mirror && canRemote && (!live || a.presenter.mirroring)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tv_mirror_remote), style = Kin.type.caption, color = c.inkSoft, modifier = Modifier.weight(1f))
                    IconButton({ a.vm?.remote(false) }) { Icon(KinIcons.SkipBack, stringResource(R.string.tv_prev), tint = c.ink) }
                    IconButton({ a.vm?.remote(true) }) { Icon(KinIcons.SkipForward, stringResource(R.string.tv_next), tint = c.ink) }
                }
            }
            if (!live && state.mode == TvMode.Frame) {
                Text(stringResource(R.string.tv_frame_elsewhere), style = Kin.type.bodyStrong, color = c.ink)
                webUrl?.let { Text(stringResource(R.string.tv_frame_elsewhere_hint, it.trimEnd('/').removePrefix("https://").removePrefix("http://") + "/tv/frame"), style = Kin.type.caption, color = c.inkSoft) }
                KinTextField(state.code, { a.vm?.setCode(it) }, stringResource(R.string.tv_frame_code), placeholder = "1234 5678",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                PrimaryButton(stringResource(R.string.tv_frame_add), { a.vm?.claim(a.onClaimed) }, enabled = state.code.length == 8, busy = state.busy == "code")
                Text(stringResource(R.string.tv_frame_note), style = Kin.type.caption, color = c.inkSoft)
            }
            state.link?.let { l -> LinkBox(l, a) }
            val others = state.active.filter { (it.purpose == TvPurpose.TV || it.purpose == TvPurpose.MIRROR) && it.id != state.link?.id }
            if (others.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.tv_active_links, others.size, others.size), style = Kin.type.label, color = c.ink)
                others.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(KinIcons.Link, null, Modifier.size(16.dp), tint = c.inkSoft)
                        Text(stringResource(R.string.tv_link_by, l.createdByName.orEmpty(), Format.dateTime(l.expiresAt)), style = Kin.type.caption, color = c.ink, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.tv_revoke), { a.vm?.revoke(l) })
                    }
                }
            }
        }
    }
}

/** Android's ways to reach a TV: a connected screen (system Cast screen / Miracast), or a link for the TV's browser. */
@Composable
private fun Ways(state: TvState, a: TvDialogActions) {
    Way(KinIcons.Tv, stringResource(R.string.tv_present), stringResource(R.string.tv_present_hint_android), busy = state.busy == "present", enabled = state.busy == null) { a.vm?.present() }
    Way(KinIcons.Globe, stringResource(R.string.tv_link), stringResource(R.string.tv_link_hint), busy = state.busy == "link", enabled = state.busy == null) { a.vm?.makeLink() }
}

@Composable
private fun Way(icon: ImageVector, title: String, hint: String, selected: Boolean = false, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) c.accent else c.inkSoft)
        Column(Modifier.weight(1f)) {
            Text(title, style = Kin.type.bodyStrong, color = c.ink)
            Text(hint, style = Kin.type.caption, color = c.inkSoft)
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        else Icon(KinIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.inkFaint)
    }
}


@Composable
private fun LinkBox(l: TvLink, a: TvDialogActions) {
    val c = Kin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.paperDeep).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tv_link_ready), style = Kin.type.bodyStrong, color = c.ink)
        Text(l.url, style = Kin.type.caption, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.tv_copy), { a.onCopy(l.url) }, icon = KinIcons.Copy)
            SecondaryButton(stringResource(R.string.people_share), { a.onShare(l.url) }, icon = KinIcons.Share)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(stringResource(R.string.tv_open), { a.onOpen(l.url) })
            QuietButton(stringResource(R.string.tv_revoke), { a.vm?.revoke(l) })
        }
        Text(stringResource(R.string.tv_valid_until, Format.dateTime(l.expiresAt)), style = Kin.type.caption, color = c.inkSoft)
    }
}

@Composable
private fun LiveCard(a: TvDialogActions) {
    val c = Kin.colors
    val p = a.presenter
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.paperDeep).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.status == TvPresenter.Status.Waiting) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            else Icon(KinIcons.Cast, null, Modifier.size(16.dp), tint = c.accent)
            Text(
                when {
                    p.status == TvPresenter.Status.Waiting -> stringResource(R.string.tv_connecting)
                    p.device != null -> stringResource(R.string.tv_casting_to, p.device)
                    else -> stringResource(R.string.tv_casting_no_name)
                },
                style = Kin.type.bodyStrong, color = c.ink,
            )
        }
        if (p.status == TvPresenter.Status.Waiting) Text(stringResource(R.string.tv_waiting_for_screen), style = Kin.type.caption, color = c.inkSoft)
        if (p.mirroring) Text(stringResource(R.string.tv_mirror_label, p.frameName), style = Kin.type.caption, color = c.inkSoft)
        TvControls(p, a.onPrevious, a.onToggle, a.onNext, a.onStop)
    }
}

/** Previous, play/pause (not while mirroring), next and stop: the dialog's live card and the mini bar. */
@Composable
fun TvControls(p: TvPresenter.State, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onStop: () -> Unit) {
    val c = Kin.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onPrevious, enabled = p.status == TvPresenter.Status.Live) { Icon(KinIcons.SkipBack, stringResource(R.string.tv_prev), tint = c.ink) }
        if (!p.mirroring) {
            IconButton(onToggle, enabled = p.status == TvPresenter.Status.Live) {
                Icon(if (p.playing) KinIcons.Pause else KinIcons.Play, stringResource(if (p.playing) R.string.tv_pause else R.string.tv_play), tint = c.accent)
            }
        }
        IconButton(onNext, enabled = p.status == TvPresenter.Status.Live) { Icon(KinIcons.SkipForward, stringResource(R.string.tv_next), tint = c.ink) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        QuietButton(stringResource(R.string.tv_stop), onStop, danger = true)
    }
}

/** Shown on every screen while something plays on a TV (web `TvMiniBar.svelte`). */
@Composable
fun TvMiniBar(p: TvPresenter.State, onOpen: () -> Unit, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onStop: () -> Unit) {
    if (p.status == TvPresenter.Status.Idle) return
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().background(c.accentSoft).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KinIcons.Cast, null, Modifier.size(18.dp), tint = c.onAccentSoft)
        Text(p.device ?: stringResource(R.string.tv_mini_label), style = Kin.type.label, color = c.onAccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onPrevious) { Icon(KinIcons.SkipBack, stringResource(R.string.tv_prev), tint = c.onAccentSoft) }
        if (!p.mirroring) IconButton(onToggle) { Icon(if (p.playing) KinIcons.Pause else KinIcons.Play, stringResource(if (p.playing) R.string.tv_pause else R.string.tv_play), tint = c.onAccentSoft) }
        IconButton(onNext) { Icon(KinIcons.SkipForward, stringResource(R.string.tv_next), tint = c.onAccentSoft) }
        IconButton(onStop) { Icon(KinIcons.Close, stringResource(R.string.tv_stop), tint = c.onAccentSoft) }
    }
}
