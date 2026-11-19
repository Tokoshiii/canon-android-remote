package dev.canonremote.app.relay

import dev.canonremote.app.remote.HotspotInfo
import dev.canonremote.app.remote.QrPayload
import dev.canonremote.app.remote.RELAY_WS_PORT
import dev.canonremote.app.settings.AppMode
import dev.canonremote.core.remote.RelayClient
import kotlin.random.Random

/** Links a relay can offer to a remote. */
enum class RelayLink(val label: String) {
    WIFI("Wi-Fi local"),
    HOTSPOT("Point d'accès"),
    BLUETOOTH("Bluetooth"),
}

/**
 * What the Relais card shows. [links] are the links the user switched on; [hotspot] is set once
 * the access point is up; [linkErrors] holds the last failure of each link (shown under it).
 */
data class RelayUiState(
    val running: Boolean = false,
    val links: Set<RelayLink> = setOf(RelayLink.WIFI),
    val pairingCode: String? = null,
    val hotspot: HotspotInfo? = null,
    val hotspotStarting: Boolean = false,
    val client: RelayClient? = null,
    val linkErrors: Map<RelayLink, String> = emptyMap(),
) {
    /** QR code content: only while the relay runs with its access point up. */
    val qrPayload: QrPayload?
        get() {
            val h = hotspot ?: return null
            val code = pairingCode ?: return null
            if (!running || RelayLink.HOTSPOT !in links) return null
            return QrPayload(ssid = h.ssid, password = h.password, host = h.hostIp, port = RELAY_WS_PORT, code = code)
        }
}

object PairingCode {
    /** Random 6-digit code, leading zeros kept. */
    fun generate(random: Random = Random.Default): String =
        random.nextInt(0, 1_000_000).toString().padStart(6, '0')
}

object RelayLabels {
    fun client(client: RelayClient?): String =
        client?.let { "Télécommande connectée : ${it.name}" } ?: "En attente…"

    fun notification(client: RelayClient?): String =
        client?.let { "Télécommande : ${it.name}" } ?: "En attente d'une télécommande"
}

/** Label of the status chip on the Appareil screen, e.g. « Télécommande • Canon EOS 90D ». */
fun statusChipLabel(mode: AppMode, statusLabel: String): String = when (mode) {
    AppMode.DIRECT -> statusLabel
    AppMode.RELAY, AppMode.REMOTE -> "${mode.label} • $statusLabel"
}

/** Runtime permissions needed before each feature, for a given API level. */
object RuntimePermissions {
    private const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"

    /** Access point (relay) and joining it (remote). */
    fun wifi(sdk: Int): List<String> = listOf(if (sdk >= 33) NEARBY_WIFI_DEVICES else ACCESS_FINE_LOCATION)

    /** Relay notification (optional: the relay also runs without it). */
    fun notifications(sdk: Int): List<String> = if (sdk >= 33) listOf(POST_NOTIFICATIONS) else emptyList()

    fun bluetoothRelay(@Suppress("UNUSED_PARAMETER") sdk: Int): List<String> =
        listOf(BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)

    fun bluetoothRemote(@Suppress("UNUSED_PARAMETER") sdk: Int): List<String> =
        listOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
}
