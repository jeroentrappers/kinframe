package app.kinframe.frame.plugins.homewizard

import android.content.Context
import app.kinframe.frame.plugins.ServiceBrowser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A HomeWizard device seen on the network. [apiEnabled] is false when "Local API" is still off in its app. */
data class FoundMeter(val name: String, val host: String, val port: Int, val product: String?, val apiEnabled: Boolean?) {
    /** What to store as the `host` setting. */
    val address: String get() = if (port == 80 || port <= 0) host else "$host:$port"
}

/** Finds HomeWizard Energy devices with mDNS (`_hwenergy._tcp`, docs/plugins.md §6.2), for the frame's own settings. */
class HomeWizardDiscovery(private val context: Context) {
    fun browse(): Flow<List<FoundMeter>> = ServiceBrowser(context).browse(SERVICE).map { list ->
        list.map { s ->
            FoundMeter(
                name = s.name, host = s.host, port = s.port,
                product = s.attributes["product_type"] ?: s.attributes["product_name"],
                apiEnabled = s.attributes["api_enabled"]?.let { it == "1" },
            )
        }
    }

    companion object {
        const val SERVICE = "_hwenergy._tcp"
    }
}
