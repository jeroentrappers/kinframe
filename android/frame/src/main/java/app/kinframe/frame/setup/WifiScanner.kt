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
        val fresh = results
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
        val (list, missed) = stableOrder(_networks.value, fresh, missedScans)
        missedScans = missed
        _networks.value = list
    }

    /** Per SSID still listed but absent from the latest scans: how many scans in a row it was missing. */
    private var missedScans: Map<String, Int> = emptyMap()

    companion object {
        /** Scans a network may be missing (one is often skipped) before it leaves the list. */
        const val KEEP_MISSING_SCANS = 3
        const val MAX_SHOWN = 30

        /**
         * Merges a scan into the list on screen without moving anything: networks already shown keep their place
         * (with fresh signal and state), new ones are appended (connected first, then by signal and name), and a
         * network missing from the scan stays until it was absent [KEEP_MISSING_SCANS] times in a row. Re-sorting
         * on every scan made rows jump under the user's finger, so a tap opened another network than the one aimed at.
         */
        fun stableOrder(shown: List<Network>, fresh: List<Network>, missed: Map<String, Int>): Pair<List<Network>, Map<String, Int>> {
            val bySsid = fresh.associateBy { it.ssid }
            val nextMissed = mutableMapOf<String, Int>()
            val kept = shown.mapNotNull { old ->
                bySsid[old.ssid] ?: run {
                    val n = (missed[old.ssid] ?: 0) + 1
                    if (n >= KEEP_MISSING_SCANS) null else old.copy(connected = false).also { nextMissed[old.ssid] = n }
                }
            }
            val known = shown.mapTo(HashSet()) { it.ssid }
            val added = fresh.filter { it.ssid !in known }
                .sortedWith(compareByDescending<Network> { it.connected }.thenByDescending { it.level }.thenBy { it.ssid.lowercase() })
            return (kept + added).take(MAX_SHOWN) to nextMissed
        }
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
