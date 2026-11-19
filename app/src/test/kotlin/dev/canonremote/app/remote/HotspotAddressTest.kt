package dev.canonremote.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAddressTest {
    private fun a(iface: String, ip: String, siteLocal: Boolean = true) = LocalIpv4(iface, ip, siteLocal)

    @Test
    fun skipsStationWifiAndPicksHotspotInterface() {
        val ip = pickHotspotAddress(
            candidates = listOf(a("lo", "127.0.0.1", false), a("wlan0", "192.168.1.20"), a("rmnet0", "10.1.2.3"), a("swlan0", "192.168.43.1")),
            stationInterfaces = setOf("wlan0"),
            stationAddresses = setOf("192.168.1.20"),
        )
        assertEquals("192.168.43.1", ip)
    }

    @Test
    fun acceptsWlanAndApPrefixes() {
        assertEquals("192.168.49.1", pickHotspotAddress(listOf(a("wlan0", "192.168.1.20"), a("wlan1", "192.168.49.1")), setOf("wlan0"), setOf("192.168.1.20")))
        assertEquals("10.0.0.1", pickHotspotAddress(listOf(a("ap0", "10.0.0.1")), emptySet(), emptySet()))
    }

    @Test
    fun stationAddressExcludedEvenWhenInterfaceUnknown() {
        assertEquals("192.168.43.1", pickHotspotAddress(listOf(a("wlan0", "192.168.1.20"), a("wlan2", "192.168.43.1")), emptySet(), setOf("192.168.1.20")))
    }

    @Test
    fun fallsBackToSiteLocalAddressOtherThanStation() {
        val ip = pickHotspotAddress(
            candidates = listOf(a("wlan0", "192.168.1.20"), a("eth7", "8.8.8.8", false), a("v4-rndis", "172.20.10.2")),
            stationInterfaces = setOf("wlan0"),
            stationAddresses = setOf("192.168.1.20"),
        )
        assertEquals("172.20.10.2", ip)
    }

    @Test
    fun nullWhenOnlyStationOrPublic() {
        assertNull(pickHotspotAddress(listOf(a("wlan0", "192.168.1.20"), a("rmnet0", "100.70.1.1", false)), setOf("wlan0"), setOf("192.168.1.20")))
    }
}
