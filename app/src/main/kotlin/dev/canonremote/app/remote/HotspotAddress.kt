package dev.canonremote.app.remote

/** An IPv4 address found on a local network interface. */
internal data class LocalIpv4(val interfaceName: String, val ipv4: String, val siteLocal: Boolean)

private val HOTSPOT_INTERFACE_PREFIXES = listOf("swlan", "wlan", "ap")

/** Cellular, CLAT, dummy and loopback interfaces: never the hotspot. */
private val EXCLUDED_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "v4-", "dummy", "lo")

/**
 * Picks the relay's address on its own LocalOnlyHotspot: the first IPv4 of an interface named
 * `swlan*` / `wlan*` / `ap*` that is not the station Wi-Fi; otherwise any site-local IPv4 other
 * than the station Wi-Fi address, excluding cellular / CLAT / dummy / loopback interfaces. Null when nothing fits (interface not up yet).
 */
internal fun pickHotspotAddress(
    candidates: List<LocalIpv4>,
    stationInterfaces: Set<String>,
    stationAddresses: Set<String>,
): String? {
    val notStation = candidates.filter { it.interfaceName !in stationInterfaces && it.ipv4 !in stationAddresses }
    return notStation.firstOrNull { c -> HOTSPOT_INTERFACE_PREFIXES.any { c.interfaceName.startsWith(it) } }?.ipv4
        ?: candidates.firstOrNull { c ->
            c.siteLocal && c.ipv4 !in stationAddresses &&
                EXCLUDED_INTERFACE_PREFIXES.none { c.interfaceName.startsWith(it) }
        }?.ipv4
}
