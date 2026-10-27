package app.kinframe.frame.plugins

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet6Address

/** A service seen on the network: its mDNS name, IPv4 address, port and TXT attributes. */
data class FoundService(val name: String, val host: String, val port: Int, val attributes: Map<String, String>) {
    /** `http://host` or `http://host:port`, what a plugin's `url` setting takes (docs/plugins.md §6.2). */
    val url: String get() = if (port == 80 || port <= 0) "http://$host" else "http://$host:$port"
}

/**
 * Browses mDNS for one service type (`_hwenergy._tcp`, `_home-assistant._tcp`) through Android's NsdManager and emits
 * what it found so far. The frame browses, not the app, because only the frame is sure to be on that network. Android 6
 * can resolve only one service at a time, so results are resolved one after another.
 */
class ServiceBrowser(private val context: Context) {
    fun browse(type: String): Flow<List<FoundService>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { trySend(emptyList()); close(); return@callbackFlow }
        val found = LinkedHashMap<String, FoundService>()
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
                            .mapNotNull { (k, v) -> v?.let { k to String(it, Charsets.UTF_8) } }.toMap()
                        found[info.serviceName] = FoundService(info.serviceName, hostAddress, info.port, attrs)
                        trySend(found.values.toList())
                    }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery of $type failed: $errorCode"); trySend(emptyList()) }
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
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { trySend(emptyList()) }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private companion object { const val TAG = "KinFramePlugins" }
}
