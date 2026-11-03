package app.kinframe.family.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.Role as FrameRole
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.history.Stage
import app.kinframe.family.util.Format
import coil3.compose.AsyncImage

/** Widest a reading column gets on tablets. */
val ContentMaxWidth = 720.dp

/** Loading / ready / error for a screen's data. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Error(@StringRes val message: Int) : Load<Nothing>
}

val <T> Load<T>.valueOrNull: T? get() = (this as? Load.Ready<T>)?.value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = Kin.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(KinIcons.ChevronLeft, stringResource(R.string.common_back), tint = Kin.colors.ink) }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Kin.colors.paper, scrolledContainerColor = Kin.colors.paperDeep),
    )
}

/** A centred, width-limited, scrollable column: the standard page body. */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Kin.type.title, color = Kin.colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** Card with the app's quiet surface and hairline border. */
@Composable
fun Surface(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Kin.colors
    var m = modifier.clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null) {
    KinButton(
        text, onClick, modifier, KinButtonStyle.Primary, enabled = enabled && !busy,
        leading = when {
            busy -> ({ CircularProgressIndicator(Modifier.size(18.dp), color = Kin.colors.onAccent, strokeWidth = 2.dp) })
            icon != null -> ({ Icon(icon, null, Modifier.size(20.dp), tint = Kin.colors.onAccent) })
            else -> null
        },
    )
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    KinButton(text, onClick, modifier, KinButtonStyle.Secondary, enabled = enabled,
        leading = icon?.let { { Icon(it, null, Modifier.size(20.dp), tint = Kin.colors.ink) } })
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    if (danger) KinButton(text, onClick, modifier, KinButtonStyle.Danger, enabled = enabled)
    else KinButton(text, onClick, modifier, KinButtonStyle.Quiet, enabled = enabled)
}

@Composable
fun KinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val c = Kin.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.inkFaint) } },
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        enabled = enabled,
        textStyle = textStyle ?: Kin.type.body,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedLabelColor = c.accent,
            unfocusedContainerColor = c.surface, focusedContainerColor = c.surface, cursorColor = c.accent,
        ),
    )
}

/** A full-width row with a title, optional hint and a switch; the whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Kin.type.bodyStrong, color = if (enabled) c.ink else c.inkFaint)
            if (hint != null) Text(hint, style = Kin.type.caption, color = c.inkSoft)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedBorderColor = c.line),
        )
    }
}

/** Empty state: a small illustration, a title, a sentence and one clear next step. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, art: @Composable () -> Unit = { FrameArt() }, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        art()
        Text(title, style = Kin.type.headline, color = Kin.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

/** The little picture frame with a sun and a hill, from the web app's frame cards. */
@Composable
fun FrameArt(modifier: Modifier = Modifier, width: Dp = 140.dp, portrait: Boolean = false, tilt: Float = -3f) {
    val dark = Kin.colors.isDark
    Canvas(modifier.width(width).aspectRatio(if (portrait) 10f / 16f else 16f / 10f).rotate(tilt).alpha(if (dark) 0.85f else 1f)) {
        val pad = size.minDimension * 0.07f
        drawRoundRect(Color(0xFF2F2823), cornerRadius = CornerRadius(size.minDimension * 0.1f))
        val inner = Size(size.width - pad * 2, size.height - pad * 2)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFFDE7CF), Color(0xFFF3C9A3)), startY = pad, endY = pad + inner.height),
            topLeft = Offset(pad, pad), size = inner, cornerRadius = CornerRadius(size.minDimension * 0.04f),
        )
        drawCircle(Color(0xFFE7A23A), radius = inner.width * 0.08f, center = Offset(pad + inner.width * 0.74f, pad + inner.height * 0.3f))
        drawOval(
            Color(0xFF8FB08F), topLeft = Offset(pad - inner.width * 0.1f, pad + inner.height * 0.62f),
            size = Size(inner.width * 0.8f, inner.height * 0.7f),
        )
        drawOval(
            Color(0xFF6E9A75), topLeft = Offset(pad + inner.width * 0.4f, pad + inner.height * 0.7f),
            size = Size(inner.width * 0.75f, inner.height * 0.6f),
        )
    }
}

/** A calm per-frame tint so frames are recognisable at a glance (as on the web). */
fun frameTint(id: String): Pair<Color, Color> {
    val tints = listOf(
        Color(0xFFF6D9C2) to Color(0xFFE9B38F),
        Color(0xFFDBE8D6) to Color(0xFFA9C7A1),
        Color(0xFFF3E3BD) to Color(0xFFE2C27A),
        Color(0xFFDDE3EF) to Color(0xFFA8B6D4),
        Color(0xFFEFD9E0) to Color(0xFFD6A7B6),
        Color(0xFFD8E9E7) to Color(0xFF97C3BD),
    )
    return tints[id.sumOf { it.code } % tints.size]
}

@Composable
fun OnlineStatus(online: Boolean, lastSeen: String?, modifier: Modifier = Modifier) {
    val c = Kin.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) c.sage else c.inkFaint))
        Text(
            when {
                online -> stringResource(R.string.frame_online)
                lastSeen != null -> stringResource(R.string.frame_last_seen, Format.relative(lastSeen))
                else -> stringResource(R.string.frame_offline)
            },
            style = Kin.type.caption, color = if (online) c.onSageSoft else c.inkSoft,
        )
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = fg)
        Text(text, style = Kin.type.caption, color = fg, maxLines = 1)
    }
}

/** The "Live" mark on Live/Motion Photos (composer, history, library). */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.live_badge), style = Kin.type.caption, color = Color.White, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun RoleBadge(role: String) {
    val c = Kin.colors
    when (role) {
        FrameRole.OWNER -> Pill(stringResource(R.string.role_owner), c.accentSoft, c.onAccentSoft)
        FrameRole.ADMIN -> Pill(stringResource(R.string.role_admin), c.sageSoft, c.onSageSoft)
        else -> Pill(stringResource(R.string.role_member), c.paperDeep, c.inkSoft)
    }
}

@Composable
fun StagePill(stage: Stage) {
    val c = Kin.colors
    val (bg, fg) = when (stage) {
        Stage.Seen -> c.sageSoft to c.onSageSoft
        Stage.Delivered -> c.sageSoft to c.onSageSoft
        Stage.Failed -> c.dangerSoft to c.danger
        Stage.Removed -> c.paperDeep to c.inkFaint
        Stage.OnItsWay -> c.accentSoft to c.onAccentSoft
        Stage.Processing -> c.warnSoft to c.warn
    }
    val icon = when (stage) {
        Stage.Seen -> KinIcons.Eye
        Stage.Delivered -> KinIcons.Check
        Stage.Failed -> KinIcons.Info
        else -> null
    }
    Pill(stringResource(stage.label), bg, fg, icon = icon)
}

/** Remote thumbnail (signed URL) or a local URI, with a quiet placeholder. */
@Composable
fun Thumb(model: Any?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Kin.colors.paperDeep)) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Pulsing placeholder block for skeleton screens. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(modifier.clip(RoundedCornerShape(corner)).alpha(a).background(Kin.colors.paperDeep))
}

/** Skeleton list for screens that are still loading. */
@Composable
fun SkeletonList(rows: Int = 3, height: Dp = 92.dp) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(rows) { Skeleton(Modifier.fillMaxWidth().height(height), corner = 18.dp) }
    }
}

/** An error with a way forward. */
@Composable
fun ErrorPanel(@StringRes message: Int, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KinIcon(KinIcons.WifiOff, null, tint = Kin.colors.inkSoft, size = 36.dp)
        Text(stringResource(message), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center)
        if (onRetry != null) SecondaryButton(stringResource(R.string.common_try_again), onRetry, icon = KinIcons.Refresh)
    }
}

/** Inline note (offline frame, conflicts, hints). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, tone: NoteTone = NoteTone.Info, icon: ImageVector = KinIcons.Info) {
    val c = Kin.colors
    val (bg, fg) = when (tone) {
        NoteTone.Info -> c.paperDeep to c.inkSoft
        NoteTone.Warn -> c.warnSoft to c.warn
        NoteTone.Danger -> c.dangerSoft to c.danger
        NoteTone.Good -> c.sageSoft to c.onSageSoft
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Text(text, style = Kin.type.caption, color = fg)
    }
}

enum class NoteTone { Info, Warn, Danger, Good }

/** A selectable chip with a check mark, announced as selected or not. */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val c = Kin.colors
    val selectedLabel = stringResource(if (selected) R.string.common_selected else R.string.common_not_selected)
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) c.accentSoft else c.surface)
            .border(1.dp, if (selected) c.accent else c.line, CircleShape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { stateDescription = selectedLabel }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected) Icon(KinIcons.Check, null, Modifier.size(18.dp), tint = c.onAccentSoft)
        leading?.invoke()
        Text(text, style = Kin.type.label, color = if (selected) c.onAccentSoft else c.ink)
    }
}

/** Round avatar with initials when there is no picture. */
@Composable
fun Avatar(name: String, url: String?, size: Dp = 40.dp) {
    val c = Kin.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.trim().take(1).uppercase(), style = Kin.type.bodyStrong, color = c.onAccentSoft)
        }
    }
}

/** Loading spinner centred in the available space. */
@Composable
fun BoxScope.CenteredSpinner() {
    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Kin.colors.accent)
}

/** Labelled icon button with a 48 dp target (remote control, quick actions). */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Kin.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.paperDeep)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.ink)
        Text(label, style = Kin.type.caption, color = c.ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
