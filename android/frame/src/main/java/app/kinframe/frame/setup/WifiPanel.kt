package app.kinframe.frame.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinButtonStyle
import app.kinframe.core.design.KinCard
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameApp
import app.kinframe.frame.R
import app.kinframe.frame.system.ReturnButton
import app.kinframe.frame.ui.StatusCard
import kotlinx.coroutines.delay

/**
 * The in-app Wi-Fi picker: connection status on the left, nearby networks on the right. Used by the setup wizard and
 * by frame settings. The Denver frames have no navigation bar, so Android's own Wi-Fi settings are a dead end; this
 * keeps people inside the app. Android 10+ can't join networks from an app, so there only the system panel is offered.
 */
class WifiPicker(val scanner: WifiScanner) {
    /** The network whose password dialog is open. */
    var joining by mutableStateOf<WifiScanner.Network?>(null)
}

@Composable
fun rememberWifiPicker(): WifiPicker {
    val context = LocalContext.current
    return remember { WifiPicker(WifiScanner(context.applicationContext)) }
}

/** The picker's content; the caller also places [WifiJoinDialog] over the whole screen. */
@Composable
fun WifiContent(picker: WifiPicker, modifier: Modifier = Modifier, heading: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember { FrameApp.graph(context) }
    val net by graph.network.status.collectAsState()
    val scanner = picker.scanner
    val networks by scanner.networks.collectAsState()
    var hasPermission by remember { mutableStateOf(scanner.hasPermission()) }
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
    Box(modifier) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f)) {
                heading()
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
                val portal by graph.controller.portal.loginUrl.collectAsState()
                LaunchedEffect(net.connected, net.validated, net.captivePortal) {
                    if (net.connected) graph.controller.portal.check()
                }
                if (net.connected && portal != null) {
                    Spacer(Modifier.height(16.dp))
                    StatusCard(
                        KinIcons.Login, stringResource(R.string.status_card_portal_title), stringResource(R.string.status_card_portal_body),
                        Kin.colors.accent, onDismiss = {}, action = stringResource(R.string.status_card_portal_action),
                        onAction = { graph.controller.portalOpen.value = true }, dismissible = false,
                    )
                }
                Spacer(Modifier.height(20.dp))
                // Still offered for what the list can't do (hidden networks, enterprise Wi-Fi); with a way back.
                KinButton(stringResource(R.string.wifi_open_settings), { openSystemWifi(context) }, style = KinButtonStyle.Secondary)
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
                                KinCard(Modifier.fillMaxWidth(), selected = n.connected, padding = 18.dp, onClick = { if (!n.connected) picker.joining = n }) {
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
}

@Composable
fun WifiJoinDialog(picker: WifiPicker) {
    picker.joining?.let { n ->
        WifiPasswordDialog(n, onCancel = { picker.joining = null }) { pw ->
            picker.scanner.connect(n.ssid, pw)
            picker.joining = null
        }
    }
}

/** Frame settings → Wi-Fi: the picker full screen, with a back arrow to settings. */
@Composable
fun WifiSettingsScreen(onBack: () -> Unit) {
    val picker = rememberWifiPicker()
    Box(Modifier.fillMaxSize().background(Kin.colors.paper)) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                KinIcon(KinIcons.ChevronLeft, stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.about_wifi), style = Kin.type.headline, modifier = Modifier.padding(start = 8.dp))
        }
        WifiContent(picker, Modifier.fillMaxSize().padding(start = 88.dp, end = 88.dp, top = 8.dp, bottom = 24.dp)) {}
    }
    WifiJoinDialog(picker)
    }
}

/**
 * Android's Wi-Fi settings: the Wi-Fi panel on Android 10+ (a sheet with its own Done), else the full settings page
 * with the floating "Back to kinframe" button, the only way back on frames without a navigation bar.
 */
fun openSystemWifi(context: Context) {
    val panel = Build.VERSION.SDK_INT >= 29
    val i = if (panel) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onSuccess { if (!panel) ReturnButton.show(context) }
}

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
