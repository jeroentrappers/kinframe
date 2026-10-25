package app.kinframe.frame.plugins.homewizard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet6Address

/** A HomeWizard device seen on the network. [apiEnabled] is false when "Local API" is still off in its app. */
data class FoundMeter(val name: String, val host: String, val port: Int, val product: String?, val apiEnabled: Boolean?) {
    /** What to store as the `host` setting. */
    val address: String get() = if (port == 80 || port <= 0) host else "$host:$port"
}

/**
 * Finds HomeWizard Energy devices with mDNS (`_hwenergy._tcp`, docs/plugins.md §6.1) through Android's NsdManager.
 * The frame browses, not the app, because only the frame is guaranteed to be on the meter's network. Android 6 can
 * resolve only one service at a time, so results are resolved one after another.
 */
class HomeWizardDiscovery(private val context: Context) {
    fun browse(): Flow<List<FoundMeter>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { trySend(emptyList()); close(); return@callbackFlow }
        val found = LinkedHashMap<String, FoundMeter>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        // NSD callbacks arrive on binder threads: all state is touched under this lock.
        val lock = Any()

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving) return
                val n = queue.removeFirstOrNull() ?: return
                resolving = true
                n
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(lock) { resolving = false }
                    @Suppress("DEPRECATION")
                    val addr = info.host
                    val hostAddress = addr?.hostAddress
                    if (addr != null && addr !is Inet6Address && hostAddress != null) synchronized(lock) {
                        val attrs = runCatching { info.attributes }.getOrNull().orEmpty()
                        fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                        found[info.serviceName] = FoundMeter(
                            name = info.serviceName,
                            host = hostAddress,
                            port = info.port,
                            product = attr("product_type") ?: attr("product_name"),
                            apiEnabled = attr("api_enabled")?.let { it == "1" },
                        )
                        trySend(found.values.toList())
                    }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed: $errorCode"); trySend(emptyList()) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) { synchronized(lock) { trySend(found.values.toList()) } }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { queue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { if (found.remove(info.serviceName) != null) trySend(found.values.toList()) }
            }
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { trySend(emptyList()) }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    companion object {
        const val SERVICE = "_hwenergy._tcp"
        private const val TAG = "KinFramePlugins"
    }
}
