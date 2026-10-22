package app.kinframe.frame.setup

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app Wi-Fi list for the setup wizard. Scanning and connecting work on Android 6–9 (the frames we target);
 * newer Android versions fall back to the system Wi-Fi panel.
 */
class WifiScanner(private val context: Context) {
    data class Network(val ssid: String, val level: Int, val secured: Boolean, val connected: Boolean)

    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _networks = MutableStateFlow<List<Network>>(emptyList())
    val networks: StateFlow<List<Network>> = _networks
    private var registered = false

    val inAppSupported: Boolean get() = Build.VERSION.SDK_INT < 29

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = publish()
    }

    fun start() {
        if (!inAppSupported || registered) return
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        registered = true
        scan()
    }

    @Suppress("DEPRECATION")
    fun scan() {
        if (!inAppSupported) return
        runCatching { wm.startScan() }
        publish()
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by the caller; SecurityException is caught
    @Suppress("DEPRECATION")
    private fun publish() {
        val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        _networks.value = results
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxBy { it.level }
                Network(
                    ssid = ssid,
                    level = WifiManager.calculateSignalLevel(best.level, 5),
                    secured = best.capabilities.contains("WPA") || best.capabilities.contains("WEP") || best.capabilities.contains("SAE"),
                    connected = ssid == current,
                )
            }
            .sortedWith(compareByDescending<Network> { it.connected }.thenByDescending { it.level }.thenBy { it.ssid.lowercase() })
            .take(30)
    }

    /** Joins a network (Android ≤ 9). Returns false if the system refused the configuration. */
    @android.annotation.SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connect(ssid: String, password: String?): Boolean {
        val conf = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isNullOrEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            else preSharedKey = "\"$password\""
        }
        val existing = runCatching { wm.configuredNetworks }.getOrNull()?.firstOrNull { it.SSID == conf.SSID }
        val id = existing?.let { wm.updateNetwork(conf.apply { networkId = it.networkId }) }?.takeIf { it >= 0 }
            ?: wm.addNetwork(conf)
        if (id < 0) return false
        wm.disconnect()
        val ok = wm.enableNetwork(id, true)
        wm.reconnect()
        wm.saveConfiguration()
        return ok
    }
}
