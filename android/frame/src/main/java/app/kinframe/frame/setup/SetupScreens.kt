package app.kinframe.frame.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.ServerUrl
import app.kinframe.core.design.ChoiceChips
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinHeading
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.core.design.KinShapeL
import app.kinframe.core.design.PageDots
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameApp
import app.kinframe.frame.Locales
import app.kinframe.frame.R
import app.kinframe.frame.ui.Format
import app.kinframe.frame.ui.LanguageGrid
import app.kinframe.frame.ui.QrCode
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SetupFlow(controller: SetupController) {
    val state by controller.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(420)) { if (forward) it / 8 else -it / 8 } + fadeIn(tween(420))) togetherWith
                    (slideOutHorizontally(tween(300)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(300)))
            },
            label = "setup",
        ) { step ->
            when (step) {
                SetupStep.Language -> LanguageStep(controller)
                SetupStep.Wifi -> WifiStep(controller)
                SetupStep.Server -> ServerStep(controller, state)
                SetupStep.Enrolling -> EnrollingStep(controller, state)
                SetupStep.Code -> CodeStep(controller, state)
                SetupStep.Celebrate -> CelebrateStep(state.ownerName)
            }
        }
    }
}

/** Common wizard frame: wordmark and page dots on top, content, actions at the bottom. */
@Composable
internal fun StepScaffold(
    index: Int,
    onBack: (() -> Unit)?,
    primary: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            PageDots(count = 4, current = index)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp)) { content() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                KinButton(stringResource(R.string.action_back), onBack, style = KinButtonStyle.Quiet,
                    leading = { KinIcon(KinIcons.ChevronLeft, null, tint = Kin.colors.accent, size = 22.dp) })
            }
            Spacer(Modifier.weight(1f))
            primary?.invoke()
        }
    }
}

@Composable
fun Wordmark(onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(KinShapeL).background(Kin.colors.accent), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        }
        Text(
            "kinframe", style = Kin.type.title.copy(fontWeight = FontWeight.Medium),
            color = if (onDark) Color.White else Kin.colors.ink, modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------- language

@Composable
private fun LanguageStep(c: SetupController) {
    val context = LocalContext.current
    // Starts on the system language when we have it; tapping a language switches the whole wizard to it at once.
    val selected = remember { Locales.effective(context) }
    StepScaffold(
        index = 0, onBack = null,
        primary = {
            KinButton(stringResource(R.string.action_continue), {
                if (Locales.stored(context) == null) c.chooseLanguage(selected)
                c.goTo(SetupStep.Wifi)
            })
        },
    ) {
        LanguageStepContent(selected) { c.chooseLanguage(it) }
    }
}

/** The language step's body, separate from [SetupController] for screenshot tests. */
@Composable
internal fun LanguageStepContent(selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KinIcon(KinIcons.Globe, null, tint = Kin.colors.accent, size = 34.dp)
            Spacer(Modifier.width(16.dp))
            KinHeading(stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_language_body))
        }
        Spacer(Modifier.height(20.dp))
        LanguageGrid(selected, onSelect, Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------------------------- wifi

@Composable
private fun WifiStep(c: SetupController) {
    val context = LocalContext.current
    val graph = remember { FrameApp.graph(context) }
    val net by graph.network.status.collectAsState()
    val scanner = remember { WifiScanner(context.applicationContext) }
    val networks by scanner.networks.collectAsState()
    var hasPermission by remember { mutableStateOf(scanner.hasPermission()) }
    var joining by remember { mutableStateOf<WifiScanner.Network?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) scanner.start()
    }
    DisposableEffect(Unit) {
        if (scanner.inAppSupported && hasPermission) scanner.start()
        onDispose { scanner.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(10_000); graph.network.refresh(); scanner.scan() }
    }
    val openSystem: () -> Unit = {
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    StepScaffold(
        index = 1, onBack = { c.back() },
        primary = { KinButton(stringResource(R.string.action_continue), { c.goTo(SetupStep.Server) }, enabled = net.connected) },
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f)) {
                KinHeading(stringResource(R.string.setup_wifi_title), stringResource(R.string.setup_wifi_body))
                Spacer(Modifier.height(28.dp))
                KinCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KinIcon(if (net.connected) KinIcons.Wifi else KinIcons.WifiOff, null, tint = if (net.connected) Kin.colors.sage else Kin.colors.inkSoft, size = 36.dp)
                        Column(Modifier.padding(start = 18.dp)) {
                            Text(
                                if (net.connected) stringResource(R.string.wifi_connected_to, net.ssid ?: stringResource(R.string.wifi_network)) else stringResource(R.string.wifi_not_connected),
                                style = Kin.type.bodyStrong,
                            )
                            Text(
                                when {
                                    !net.connected -> stringResource(R.string.wifi_not_connected_hint)
                                    !net.validated -> stringResource(R.string.wifi_no_internet)
                                    else -> stringResource(R.string.wifi_signal, signalWord(net.level))
                                },
                                style = Kin.type.caption, color = Kin.colors.inkSoft,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                KinButton(stringResource(R.string.wifi_open_settings), openSystem, style = KinButtonStyle.Secondary)
            }
            if (scanner.inAppSupported) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wifi_networks), style = Kin.type.label, color = Kin.colors.inkSoft)
                    Spacer(Modifier.height(10.dp))
                    when {
                        !hasPermission -> KinCard(Modifier.fillMaxWidth()) {
                            Column {
                                Text(stringResource(R.string.wifi_permission_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                                Spacer(Modifier.height(14.dp))
                                KinButton(stringResource(R.string.wifi_show_networks), { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, style = KinButtonStyle.Secondary)
                            }
                        }
                        networks.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Kin.colors.accent, strokeWidth = 2.dp)
                            Text(stringResource(R.string.wifi_scanning), style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(start = 12.dp))
                        }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(networks, key = { it.ssid }) { n ->
                                KinCard(Modifier.fillMaxWidth(), selected = n.connected, padding = 18.dp, onClick = { if (!n.connected) joining = n }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        KinIcon(if (n.level >= 2) KinIcons.Wifi else KinIcons.WifiWeak, null, tint = Kin.colors.inkSoft)
                                        Text(n.ssid, style = Kin.type.bodyStrong, modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 1)
                                        if (n.secured) KinIcon(KinIcons.Lock, null, tint = Kin.colors.inkFaint, size = 20.dp)
                                        if (n.connected) KinIcon(KinIcons.Check, null, tint = Kin.colors.accent, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    joining?.let { n ->
        WifiPasswordDialog(n, onCancel = { joining = null }) { pw ->
            scanner.connect(n.ssid, pw)
            joining = null
        }
    }
}

@Composable
fun signalWord(level: Int): String = stringResource(
    when (level) { 0 -> R.string.signal_0; 1 -> R.string.signal_1; 2 -> R.string.signal_2; 3 -> R.string.signal_3; else -> R.string.signal_4 },
)

@Composable
private fun WifiPasswordDialog(n: WifiScanner.Network, onCancel: () -> Unit, onJoin: (String?) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Kin.colors.scrim).clickable(onClick = onCancel), contentAlignment = Alignment.TopCenter) {
        KinCard(Modifier.padding(top = 60.dp).widthIn(max = 560.dp).clickable(enabled = false) {}) {
            Column {
                Text(n.ssid, style = Kin.type.title)
                Spacer(Modifier.height(16.dp))
                if (n.secured) {
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.wifi_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onJoin(pw) }),
                        modifier = Modifier.fillMaxWidth(), textStyle = Kin.type.body,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    KinButton(stringResource(R.string.action_cancel), onCancel, style = KinButtonStyle.Quiet)
                    KinButton(stringResource(R.string.wifi_join), { onJoin(pw.ifEmpty { null }) }, enabled = !n.secured || pw.length >= 8)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- server

@Composable
private fun ServerStep(c: SetupController, s: SetupState) {
    LaunchedEffect(s.useCustomServer) { if (!s.useCustomServer && s.check == ServerCheck.Idle) c.checkServer() }
    val ok = s.check as? ServerCheck.Ok
    StepScaffold(
        index = 2, onBack = { c.back() },
        primary = {
            if (ok != null) KinButton(stringResource(R.string.action_continue), { c.confirmServer() })
            else KinButton(stringResource(R.string.server_check), { c.checkServer() }, enabled = s.check != ServerCheck.Checking)
        },
    ) {
        ServerStepContent(s, c::setUseCustom, c::setCustomUrl, c::checkServer)
    }
}

/** The server step's body (screenshot tests render it without a controller). */
@Composable
internal fun ServerStepContent(s: SetupState, onUseCustom: (Boolean) -> Unit, onUrl: (String) -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KinHeading(stringResource(R.string.setup_server_title), stringResource(R.string.setup_server_body))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            KinCard(Modifier.weight(1f).heightIn(min = 120.dp), selected = !s.useCustomServer, onClick = { onUseCustom(false) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KinIcon(KinIcons.Server, null, tint = Kin.colors.accent)
                        Text(ServerUrl.display(BuildConfig.DEFAULT_SERVER.toHttpUrl()), style = Kin.type.bodyStrong, modifier = Modifier.padding(start = 12.dp))
                    }
                    Text(stringResource(R.string.server_default_body), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 8.dp))
                }
            }
            KinCard(Modifier.weight(1f).heightIn(min = 120.dp), selected = s.useCustomServer, onClick = { onUseCustom(true) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KinIcon(KinIcons.Settings, null, tint = Kin.colors.accent)
                        Text(stringResource(R.string.server_custom), style = Kin.type.bodyStrong, modifier = Modifier.padding(start = 12.dp))
                    }
                    Text(stringResource(R.string.server_custom_body), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (s.useCustomServer) {
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = s.customUrl, onValueChange = onUrl, singleLine = true,
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text("https://frames.example.org") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                textStyle = Kin.type.body,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Kin.colors.accent, focusedLabelColor = Kin.colors.accent, cursorColor = Kin.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(20.dp))
        ServerCheckResult(s.check)
    }
}

@Composable
private fun ServerCheckResult(check: ServerCheck) {
    when (check) {
        ServerCheck.Idle -> Unit
        ServerCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Kin.colors.accent, strokeWidth = 2.5.dp)
            Text(stringResource(R.string.server_checking), style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.padding(start = 14.dp))
        }
        is ServerCheck.Ok -> Row(
            Modifier.clip(KinShapeL).background(Kin.colors.sageSoft).padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KinIcon(KinIcons.Check, null, tint = Kin.colors.sage)
            Column(Modifier.padding(start = 14.dp)) {
                Text(check.discovery.name, style = Kin.type.bodyStrong)
                val insecure = check.url.scheme == "http"
                Text(
                    ServerUrl.display(check.url) + (if (check.discovery.version.isNotBlank()) " · " + stringResource(R.string.server_version, check.discovery.version) else "") +
                        (if (insecure) " · " + stringResource(R.string.server_insecure) else ""),
                    style = Kin.type.caption, color = if (insecure) Kin.colors.danger else Kin.colors.inkSoft,
                )
            }
        }
        is ServerCheck.Failed -> Text(
            stringResource(
                when (check.reason) {
                    ServerCheck.Reason.InvalidUrl -> R.string.server_error_invalid
                    ServerCheck.Reason.Unreachable -> R.string.server_error_unreachable
                    ServerCheck.Reason.NotKinframe -> R.string.server_error_not_kinframe
                },
            ),
            style = Kin.type.body, color = Kin.colors.danger,
        )
    }
}

// ---------------------------------------------------------------------------------------------- enrolling

@Composable
private fun EnrollingStep(c: SetupController, s: SetupState) {
    StepScaffold(index = 2, onBack = if (s.enrollError != null) ({ c.goTo(SetupStep.Server) }) else null, primary = {
        if (s.enrollError != null) KinButton(stringResource(R.string.action_try_again), { c.enroll() })
    }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (s.enrollError == null) {
                CircularProgressIndicator(Modifier.size(48.dp), color = Kin.colors.accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(24.dp))
                KinHeading(stringResource(R.string.enroll_title), stringResource(R.string.enroll_body), center = true)
            } else {
                KinHeading(stringResource(R.string.enroll_failed_title), stringResource(R.string.enroll_failed_body, s.enrollError), center = true)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- code

@Composable
private fun CodeStep(c: SetupController, s: SetupState) {
    val context = LocalContext.current
    val graph = remember { FrameApp.graph(context) }
    var now by remember { mutableLongStateOf(graph.clock.nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = graph.clock.nowMs() } }
    StepScaffold(index = 3, onBack = null, primary = null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            when (val code = s.code) {
                CodeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(48.dp), color = Kin.colors.accent, strokeWidth = 3.dp)
                }
                is CodeState.Error -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    KinHeading(
                        stringResource(if (code.offline) R.string.code_offline_title else R.string.code_error_title),
                        stringResource(if (code.offline) R.string.code_offline_body else R.string.code_error_body), center = true,
                    )
                    Spacer(Modifier.height(24.dp))
                    KinButton(stringResource(R.string.action_try_again), { c.retryCode() })
                }
                is CodeState.Ready -> CodeReadyContent(
                    code = Format.code(code.code.code),
                    joinUrl = code.code.joinUrl,
                    webHost = graph.webHostDisplay(),
                    validFor = Format.remaining(context, code.expiresAtMs - now),
                    landscape = landscape,
                )
            }
        }
    }
}

/** The frame code with instructions and QR code (screenshot tests render it without a controller). */
@Composable
internal fun CodeReadyContent(code: String, joinUrl: String, webHost: String, validFor: String, landscape: Boolean) {
    val instructions = @Composable {
        Column {
            KinHeading(stringResource(R.string.code_title), null)
            Spacer(Modifier.height(20.dp))
            CodeInstruction(1, stringResource(R.string.code_step1, webHost))
            CodeInstruction(2, stringResource(R.string.code_step2))
            CodeInstruction(3, stringResource(R.string.code_step3))
            Spacer(Modifier.height(24.dp))
            Text(code, style = Kin.type.code, color = Kin.colors.ink)
            Text(
                stringResource(R.string.code_valid_for, validFor),
                style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                WaitingDots()
                Text(stringResource(R.string.code_waiting), style = Kin.type.caption, color = Kin.colors.inkSoft, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
    val qr = @Composable {
        Box(
            Modifier.clip(KinShapeL).background(Color.White).padding(22.dp),
            contentAlignment = Alignment.Center,
        ) { QrCode(joinUrl, Modifier.size(if (landscape) 300.dp else 260.dp)) }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Box(Modifier.weight(1f)) { instructions() }
            qr()
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically)) {
            instructions()
            qr()
        }
    }
}

@Composable
private fun CodeInstruction(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Kin.colors.accentSoft), contentAlignment = Alignment.Center) {
            Text("$n", style = Kin.type.label, color = Kin.colors.accent)
        }
        Text(text, style = Kin.type.body, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun WaitingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(700, delayMillis = i * 180, easing = LinearEasing), RepeatMode.Reverse), label = "d$i",
            )
            Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Kin.colors.accent))
        }
    }
}

// ---------------------------------------------------------------------------------------------- celebrate

@Composable
internal fun CelebrateStep(owner: String?) {
    val t = rememberInfiniteTransition(label = "celebrate")
    val pulse by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ring")
    val accent = Kin.colors.accent
    val sage = Kin.colors.sage
    Box(Modifier.fillMaxSize().background(Kin.colors.paper), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Rings and dots orbit the heart; drawn outside the 140 dp box on purpose.
                Canvas(Modifier.size(140.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    for (k in 0 until 3) {
                        val p = (ring + k / 3f) % 1f
                        drawCircle(accent.copy(alpha = (1 - p) * 0.3f), radius = size.minDimension / 2 + p * 260f, center = center, style = Stroke(width = 3f))
                    }
                    for (i in 0 until 12) {
                        val ang = i * (Math.PI * 2 / 12) + ring * 0.6
                        val r = size.minDimension / 2 + 26f + 10f * sin(ring * 6.28f + i)
                        drawCircle(if (i % 2 == 0) accent.copy(alpha = 0.6f) else sage.copy(alpha = 0.6f), 6f,
                            Offset(center.x + (cos(ang) * r).toFloat(), center.y + (sin(ang) * r).toFloat()))
                    }
                }
                Box(Modifier.size(140.dp).scale(pulse).clip(CircleShape).background(Kin.colors.accent), contentAlignment = Alignment.Center) {
                    KinIcon(KinIcons.HeartFilled, null, tint = Color.White, size = 64.dp)
                }
            }
            Spacer(Modifier.height(72.dp))
            Text(
                if (owner != null) stringResource(R.string.celebrate_title, owner) else stringResource(R.string.celebrate_title_anon),
                style = Kin.type.display, color = Kin.colors.ink, textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.celebrate_body), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

