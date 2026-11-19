package app.kinframe.frame.setup

import app.kinframe.frame.setup.WifiScanner.Companion.stableOrder
import app.kinframe.frame.setup.WifiScanner.Network
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiOrderTest {
    private fun net(ssid: String, level: Int, connected: Boolean = false) = Network(ssid, level, secured = true, connected = connected)
    private fun ssids(l: List<Network>) = l.map { it.ssid }

    @Test fun firstScanIsSortedConnectedThenSignalThenName() {
        val (list, _) = stableOrder(emptyList(), listOf(net("b", 2), net("a", 2), net("home", 1, connected = true), net("c", 4)), emptyMap())
        assertEquals(listOf("home", "c", "a", "b"), ssids(list))
    }

    @Test fun signalChangesDoNotReorderShownNetworks() {
        val (shown, m) = stableOrder(emptyList(), listOf(net("Home-5G", 4), net("Home-IoT", 3)), emptyMap())
        val (next, _) = stableOrder(shown, listOf(net("Home-IoT", 4), net("Home-5G", 1), net("Neighbour", 4)), m)
        assertEquals(listOf("Home-5G", "Home-IoT", "Neighbour"), ssids(next))
        assertEquals(1, next.first().level) // fresh values, old place
    }

    @Test fun missingNetworkStaysForAFewScans() {
        var (list, missed) = stableOrder(emptyList(), listOf(net("a", 4), net("b", 3), net("c", 2)), emptyMap())
        repeat(WifiScanner.KEEP_MISSING_SCANS - 1) {
            stableOrder(list, listOf(net("a", 4), net("c", 2)), missed).also { list = it.first; missed = it.second }
            assertEquals(listOf("a", "b", "c"), ssids(list))
        }
        stableOrder(list, listOf(net("a", 4), net("c", 2)), missed).also { list = it.first; missed = it.second }
        assertEquals(listOf("a", "c"), ssids(list))
    }

    @Test fun returningNetworkResetsItsMissCount() {
        var (list, missed) = stableOrder(emptyList(), listOf(net("a", 4), net("b", 3)), emptyMap())
        stableOrder(list, listOf(net("a", 4)), missed).also { list = it.first; missed = it.second }
        stableOrder(list, listOf(net("a", 4), net("b", 3)), missed).also { list = it.first; missed = it.second }
        assertEquals(emptyMap<String, Int>(), missed)
        assertEquals(listOf("a", "b"), ssids(list))
    }
}
