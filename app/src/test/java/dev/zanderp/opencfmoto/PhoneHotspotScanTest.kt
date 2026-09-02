package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class PhoneHotspotScanTest {
    @Test
    fun prefersApPrefixedInterfaces() {
        val ap = iface("ap0", "192.168.43.1", 24)
        val wlan = iface("wlan0", "192.168.1.10", 24)
        val subnets = PhoneHotspotScan.tetheringSubnets(listOf(wlan, ap))
        assertEquals(2, subnets.size)
        assertEquals("ap0", subnets.first().interfaceName)
    }

    @Test
    fun candidateHostsPreferNearPhone() {
        val subnet = PhoneHotspotScan.Subnet(
            localAddress = InetAddress.getByName("192.168.43.1") as Inet4Address,
            prefixLength = 24,
            interfaceName = "ap0",
        )
        val hosts = PhoneHotspotScan.candidateHosts(subnet, limit = 5)
        assertEquals(5, hosts.size)
        assertEquals("192.168.43.2", hosts[0].hostAddress)
        assertTrue(hosts.none { it.hostAddress == "192.168.43.1" })
    }

    private fun iface(name: String, ip: String, prefix: Int) =
        PhoneHotspotScan.InterfaceSnapshot(
            name = name,
            isUp = true,
            isLoopback = false,
            isPointToPoint = false,
            addresses = listOf(InetAddress.getByName(ip) to prefix),
        )
}
