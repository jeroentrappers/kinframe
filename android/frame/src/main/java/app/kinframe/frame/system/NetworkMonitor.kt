package app.kinframe.frame.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Wi-Fi and internet status for the status pill, the quick menu, setup and the heartbeat. */
class NetworkMonitor(private val context: Context) {
    data class Status(
        val connected: Boolean = false,
        val validated: Boolean = false,
        val wifi: Boolean = false,
        val ssid: String? = null,
        val rssi: Int? = null,
        val linkMbps: Int? = null,
        /** 0..4 */
        val level: Int = 0,
        /** Android's own probe found a captive portal (sign-in page) on this network. */
        val captivePortal: Boolean = false,
    )

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private val listeners = mutableListOf<() -> Unit>()

    fun onAvailable(l: () -> Unit) { synchronized(listeners) { listeners += l } }

    fun start() {
        refresh()
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
                synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
            }
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        })
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val rssi = info?.rssi?.takeIf { isWifi && it > -127 && it < 0 }
        val ssid = info?.ssid?.trim('"')?.takeIf { isWifi && it.isNotBlank() && it != "<unknown ssid>" }
        _status.value = Status(
            connected = connected,
            validated = validated,
            wifi = isWifi,
            ssid = ssid,
            rssi = rssi,
            linkMbps = info?.linkSpeed?.takeIf { isWifi && it > 0 },
            level = rssi?.let { WifiManager.calculateSignalLevel(it, 5) } ?: 0,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }
}
