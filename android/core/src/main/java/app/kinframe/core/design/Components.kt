package app.kinframe.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val KinShapeL = RoundedCornerShape(28.dp)
val KinShapeM = RoundedCornerShape(20.dp)
val KinShapeS = RoundedCornerShape(14.dp)

enum class KinButtonStyle { Primary, Secondary, Quiet, Danger }

/** Large, calm pill button with a subtle press scale. Minimum height is the frame touch target. */
@Composable
fun KinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KinButtonStyle = KinButtonStyle.Primary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Kin.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val (bg, fg, border) = when (style) {
        KinButtonStyle.Primary -> Triple(if (pressed) c.accentPressed else c.accent, c.onAccent, Color.Transparent)
        KinButtonStyle.Secondary -> Triple(if (pressed) c.paperDeep else c.surface, c.ink, c.line)
        KinButtonStyle.Quiet -> Triple(if (pressed) c.paperDeep else Color.Transparent, c.accent, Color.Transparent)
        KinButtonStyle.Danger -> Triple(if (pressed) c.accentPressed else c.danger, c.onAccent, Color.Transparent)
    }
    val bgAnim by animateColorAsState(if (enabled) bg else c.line, label = "bg")
    Row(
        modifier
            .scale(scale)
            .defaultMinSize(minHeight = Kin.space.touch, minWidth = 120.dp)
            .clip(CircleShape)
            .background(bgAnim)
            .border(1.dp, if (enabled) border else Color.Transparent, CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = Kin.type.label, color = if (enabled) fg else c.inkFaint, textAlign = TextAlign.Center)
    }
}

/** Rounded surface card. */
@Composable
fun KinCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    val c = Kin.colors
    val borderColor by animateColorAsState(if (selected) c.accent else c.line, label = "border")
    var m = modifier
        .clip(KinShapeM)
        .background(if (selected) c.accentSoft.copy(alpha = 0.45f) else c.surface)
        .border(if (selected) 2.dp else 1.dp, borderColor, KinShapeM)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Box(m.padding(padding)) { content() }
}

/** Animated page dots for wizards. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = Kin.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = i == current
            val w by androidx.compose.animation.core.animateDpAsState(if (active) 28.dp else 10.dp, label = "dot")
            Box(
                Modifier
                    .size(width = w, height = 10.dp)
                    .clip(CircleShape)
                    .background(if (i <= current) c.accent else c.line),
            )
        }
    }
}

/** A horizontal row of selectable chips (segmented choice), large enough for a frame. */
@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Kin.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(CircleShape)
                    .background(if (sel) c.accent else c.surface)
                    .border(1.dp, if (sel) c.accent else c.line, CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Kin.type.label, color = if (sel) c.onAccent else c.ink)
            }
        }
    }
}

/** Title plus explanation block used by empty states and wizard steps. */
@Composable
fun KinHeading(title: String, body: String?, modifier: Modifier = Modifier, center: Boolean = false, onDark: Boolean = false) {
    val c = Kin.colors
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            title, style = Kin.type.headline, color = if (onDark) Color.White else c.ink,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
        if (body != null) {
            Text(
                body, style = Kin.type.body, color = if (onDark) Color.White.copy(alpha = 0.8f) else c.inkSoft,
                textAlign = if (center) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Small status pill (e.g. "Offline"). */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, dot: Color? = null, dark: Boolean = true, leading: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (dark) Color.Black.copy(alpha = 0.55f) else Kin.colors.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        leading?.invoke(this)
        Text(text, style = Kin.type.caption, color = if (dark) Color.White else Kin.colors.ink)
    }
}
