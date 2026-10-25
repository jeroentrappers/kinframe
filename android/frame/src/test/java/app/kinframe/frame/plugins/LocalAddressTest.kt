package app.kinframe.frame.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** `local_only` (docs/plugins.md §3.1): only the home network. Literals only, so no DNS is involved. */
class LocalAddressTest {
    private fun private(a: String) = LocalAddress.isPrivate(InetAddress.getByName(a))

    @Test fun rfc1918AndLinkLocalArePrivate() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.50", "169.254.10.20").forEach {
            assertTrue(it, private(it))
        }
    }

    @Test fun publicLoopbackAndCgnatAreNot() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "127.0.0.1", "100.64.0.1", "0.0.0.0", "224.0.0.251", "255.255.255.255", "11.0.0.1")
            .forEach { assertFalse(it, private(it)) }
    }

    @Test fun ipv6() {
        assertTrue(private("fd12:3456:789a::1"))
        assertTrue(private("fc00::1"))
        assertTrue(private("fe80::1c2b:3cff:fe4d:5e6f"))
        assertFalse(private("::1"))
        assertFalse(private("2a02:1810:4d02:b500::1"))
        assertFalse(private("ff02::fb"))
        // IPv4-mapped addresses are judged by their IPv4 part.
        assertTrue(LocalAddress.isPrivate(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -64, -88, 1, 2))))
        assertFalse(LocalAddress.isPrivate(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 8, 8, 8, 8))))
    }

    @Test fun typedAddresses() {
        assertEquals("http://192.168.1.50/", LocalAddress.baseUrl("192.168.1.50").toString())
        assertEquals("http://192.168.1.50:8080/", LocalAddress.baseUrl(" 192.168.1.50:8080/ ").toString())
        assertEquals("http://p1meter-abc123.local/", LocalAddress.baseUrl("p1meter-ABC123.local").toString())
        assertEquals("https://10.0.0.2/", LocalAddress.baseUrl("https://10.0.0.2/api/v1/data").toString())
        assertEquals("http://[fd00::5]/", LocalAddress.baseUrl("fd00::5").toString())
        assertNull(LocalAddress.baseUrl(""))
        assertNull(LocalAddress.baseUrl("two words"))
        assertNull(LocalAddress.baseUrl("ftp://192.168.1.1"))
    }

    @Test fun literalCheckWithoutDns() {
        assertEquals(true, LocalAddress.literalIsPrivate("192.168.1.50"))
        assertEquals(false, LocalAddress.literalIsPrivate("8.8.8.8"))
        assertEquals(true, LocalAddress.literalIsPrivate("[fd00::5]"))
        assertNull(LocalAddress.literalIsPrivate("p1meter.local"))
        assertNull(LocalAddress.literalIsPrivate("example.org"))
    }
}
