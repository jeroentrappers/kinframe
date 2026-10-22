package app.kinframe.frame.kiosk

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/** What the keypad shows below the dots. */
sealed interface PinMessage {
    data object None : PinMessage
    data object Checking : PinMessage
    data class Wrong(val left: Int) : PinMessage
    data class Locked(val secondsLeft: Int) : PinMessage
    data object Mismatch : PinMessage
}

/**
 * The owner-PIN keypad: a calm, dark full-screen layer with large round keys (frame touch targets), dots for the
 * digits typed so far, and the "forgot your PIN" hint. Stateless; [OwnerPinPrompt] and [SetPinFlow] drive it.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    entered: Int,
    message: PinMessage,
    hint: String?,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    shake: Int = 0,
) {
    val locked = message is PinMessage.Locked
    val busy = message == PinMessage.Checking
    val enabled = !locked && !busy
    val shakeAnim = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) {
            shakeAnim.snapTo(0f)
            shakeAnim.animateTo(1f, tween(420))
        }
    }
    Box(
        Modifier.fillMaxSize().background(Kin.colors.night.copy(alpha = 0.94f))
            // Swallow touches so nothing underneath reacts.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val header = @Composable { center: Boolean ->
                Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Kin.colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        KinIcon(KinIcons.Lock, null, tint = Kin.colors.accentSoft, size = 30.dp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(title, style = Kin.type.headline, color = Color.White, textAlign = if (center) TextAlign.Center else TextAlign.Start)
                    if (subtitle != null) {
                        Text(
                            subtitle, style = Kin.type.body, color = Color.White.copy(alpha = 0.72f),
                            textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = Modifier.padding(top = 8.dp).widthIn(max = 500.dp),
                        )
                    }
                    Spacer(Modifier.height(36.dp))
                    PinDots(entered, Modifier.graphicsLayer {
                        val p = shakeAnim.value
                        translationX = if (p in 0.001f..0.999f) sin(p * Math.PI.toFloat() * 6) * (1 - p) * 28f * density else 0f
                    })
                    Box(Modifier.heightIn(min = 64.dp).padding(top = 20.dp), contentAlignment = if (center) Alignment.TopCenter else Alignment.TopStart) {
                        PinMessageLine(message, center)
                    }
                }
            }
            val footer = @Composable { center: Boolean ->
                Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                    if (hint != null) {
                        Row(Modifier.widthIn(max = 460.dp), verticalAlignment = Alignment.Top) {
                            KinIcon(KinIcons.Info, null, tint = Color.White.copy(alpha = 0.5f), size = 20.dp, modifier = Modifier.padding(top = 2.dp))
                            Text(hint, style = Kin.type.caption, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(start = 10.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    CancelButton(onCancel)
                }
            }
            val keys = @Composable { Keypad(enabled, entered, onDigit, onBackspace, onSubmit) }
            if (landscape) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
                ) {
                    Column(Modifier.weight(1f).widthIn(max = 480.dp)) {
                        header(false)
                        Spacer(Modifier.height(28.dp))
                        footer(false)
                    }
                    keys()
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically),
                ) {
                    header(true)
                    keys()
                    footer(true)
                }
            }
        }
    }
}

@Composable
private fun PinDots(entered: Int, modifier: Modifier) {
    val slots = maxOf(OwnerPin.MIN_LENGTH, minOf(OwnerPin.MAX_LENGTH, entered + if (entered >= OwnerPin.MIN_LENGTH) 1 else 0))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(slots) { i ->
            val filled = i < entered
            val scale by animateFloatAsState(if (filled) 1f else 0.7f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "dot")
            Box(
                Modifier.size(20.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                    .background(if (filled) Color.White else Color.Transparent)
                    .border(2.dp, Color.White.copy(alpha = if (filled) 1f else 0.45f), CircleShape),
            )
        }
    }
}

@Composable
private fun PinMessageLine(message: PinMessage, center: Boolean) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    when (message) {
        PinMessage.None -> Unit
        PinMessage.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp)
            Text(stringResource(R.string.pin_checking), style = Kin.type.body, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 12.dp))
        }
        is PinMessage.Wrong -> Text(
            if (message.left > 0) pluralStringResource(R.plurals.pin_wrong_left, message.left, message.left) else stringResource(R.string.pin_wrong),
            style = Kin.type.bodyStrong, color = WarmError, textAlign = align,
        )
        is PinMessage.Locked -> Text(
            stringResource(R.string.pin_locked, "%d:%02d".format(message.secondsLeft / 60, message.secondsLeft % 60)),
            style = Kin.type.bodyStrong, color = WarmError, textAlign = align,
        )
        PinMessage.Mismatch -> Text(stringResource(R.string.pin_mismatch), style = Kin.type.bodyStrong, color = WarmError, textAlign = align)
    }
}

/** A soft coral that stays readable on the night background (the paper-theme danger red is too dark there). */
private val WarmError = Color(0xFFF2A48A)

@Composable
private fun Keypad(enabled: Boolean, entered: Int, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) =
    // A number pad keeps 1-2-3 from left to right in every language, also in Arabic and Hebrew.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) { KeypadLtr(enabled, entered, onDigit, onBackspace, onSubmit) }

@Composable
private fun KeypadLtr(enabled: Boolean, entered: Int, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "keys")
    Column(Modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEach { d -> Key(enabled && entered < OwnerPin.MAX_LENGTH, { onDigit(d) }) { Digit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            Key(enabled && entered > 0, onBackspace, background = Color.Transparent, label = stringResource(R.string.pin_key_delete)) {
                KinIcon(KinIcons.Backspace, null, tint = Color.White.copy(alpha = 0.85f), size = 32.dp)
            }
            Key(enabled && entered < OwnerPin.MAX_LENGTH, { onDigit('0') }) { Digit('0') }
            val ready = entered >= OwnerPin.MIN_LENGTH
            Key(enabled && ready, onSubmit, background = if (ready) Kin.colors.accent else Color.White.copy(alpha = 0.06f), label = stringResource(R.string.pin_key_ok)) {
                KinIcon(KinIcons.Check, null, tint = if (ready) Color.White else Color.White.copy(alpha = 0.35f), size = 34.dp)
            }
        }
    }
}

private val KEY_SIZE = 88.dp
private val KEY_GAP = 22.dp

@Composable
private fun Digit(d: Char) {
    Text(d.toString(), style = Kin.type.headline.copy(fontSize = 36.sp, fontWeight = FontWeight.Light), color = Color.White)
}

@Composable
private fun Key(
    enabled: Boolean,
    onClick: () -> Unit,
    background: Color = Color.White.copy(alpha = 0.1f),
    label: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "key")
    Box(
        Modifier.size(KEY_SIZE).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(if (pressed) Color.White.copy(alpha = 0.24f) else background)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinIcon(KinIcons.Close, null, tint = Color.White.copy(alpha = 0.8f), size = 22.dp)
        Text(stringResource(R.string.action_cancel), style = Kin.type.label, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 10.dp))
    }
}

/** Subtitle for each gated action. */
@Composable
fun reasonText(reason: OwnerGate.Reason): String = stringResource(
    when (reason) {
        OwnerGate.Reason.OpenSettings -> R.string.pin_reason_settings
        OwnerGate.Reason.ExitKiosk -> R.string.pin_reason_exit
        OwnerGate.Reason.Wifi -> R.string.pin_reason_wifi
        OwnerGate.Reason.PowerOff -> R.string.pin_reason_power_off
        OwnerGate.Reason.Restart -> R.string.pin_reason_restart
        OwnerGate.Reason.ChangeServer -> R.string.pin_reason_server
        OwnerGate.Reason.FactoryReset -> R.string.pin_reason_reset
    },
)

/** Asks for the owner PIN whenever [OwnerGate.request] is set. */
@Composable
fun OwnerPinPrompt(c: FrameController) {
    val request by c.gate.request.collectAsState()
    val r = request ?: return
    val scope = rememberCoroutineScope()
    var digits by remember(r) { mutableStateOf("") }
    var message by remember(r) { mutableStateOf<PinMessage>(PinMessage.None) }
    var shake by remember(r) { mutableIntStateOf(0) }
    var lockedUntil by remember(r) { mutableLongStateOf((c.gate.attemptState() as? PinAttempts.State.Locked)?.untilMs ?: 0L) }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > 0) {
            val left = ((lockedUntil - c.g.clock.nowMs()) / 1000).toInt()
            if (left <= 0) { lockedUntil = 0; message = PinMessage.None; break }
            message = PinMessage.Locked(left + 1)
            delay(250)
        }
    }
    // The keypad gives up after a minute without a key press, like the quick menu.
    var lastKey by remember(r) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r, lastKey) { delay(60_000); c.gate.cancel() }
    PinPad(
        title = stringResource(R.string.pin_enter_title),
        subtitle = reasonText(r.reason),
        entered = digits.length,
        message = message,
        hint = stringResource(R.string.pin_forgot),
        onDigit = { d ->
            lastKey = System.currentTimeMillis()
            if (digits.length < OwnerPin.MAX_LENGTH) digits += d
            if (message is PinMessage.Wrong) message = PinMessage.None
        },
        onBackspace = { lastKey = System.currentTimeMillis(); digits = digits.dropLast(1) },
        onSubmit = {
            lastKey = System.currentTimeMillis()
            val pin = digits
            message = PinMessage.Checking
            scope.launch {
                when (val res = c.gate.submit(pin)) {
                    OwnerGate.Result.Granted -> Unit
                    is OwnerGate.Result.Wrong -> { digits = ""; message = PinMessage.Wrong(res.left); shake++ }
                    is OwnerGate.Result.Locked -> { digits = ""; shake++; lockedUntil = res.untilMs }
                }
            }
        },
        onCancel = { c.gate.cancel() },
        shake = shake,
    )
}

/** Choose a new PIN: enter it, then enter it again. [onDone] gets the confirmed PIN. */
@Composable
fun SetPinFlow(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var digits by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<PinMessage>(PinMessage.None) }
    var shake by remember { mutableIntStateOf(0) }
    PinPad(
        title = stringResource(if (first == null) R.string.pin_new_title else R.string.pin_confirm_title),
        subtitle = stringResource(if (first == null) R.string.pin_new_body else R.string.pin_confirm_body),
        entered = digits.length,
        message = message,
        hint = null,
        onDigit = { d -> if (digits.length < OwnerPin.MAX_LENGTH) digits += d; message = PinMessage.None },
        onBackspace = { digits = digits.dropLast(1) },
        onSubmit = {
            val f = first
            when {
                f == null -> { first = digits; digits = "" }
                f == digits -> onDone(digits)
                else -> { first = null; digits = ""; message = PinMessage.Mismatch; shake++ }
            }
        },
        onCancel = onCancel,
        shake = shake,
    )
}
