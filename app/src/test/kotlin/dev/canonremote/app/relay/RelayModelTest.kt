package dev.canonremote.app.relay

import dev.canonremote.app.remote.HotspotInfo
import dev.canonremote.app.remote.QrPayload
import dev.canonremote.app.settings.AppMode
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.RelayClient
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayModelTest {
    @Test
    fun pairingCodeHasSixDigitsIncludingLeadingZeros() {
        repeat(500) { seed ->
            val code = PairingCode.generate(Random(seed))
            assertTrue(code, code.length == 6 && code.all { it.isDigit() })
        }
        val zeros = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        assertEquals("000000", PairingCode.generate(zeros))
    }

    @Test
    fun pairingCodeVaries() {
        val codes = (0 until 50).map { PairingCode.generate(Random(it)) }.toSet()
        assertTrue(codes.size > 40)
    }

    @Test
    fun clientLabels() {
        assertEquals("En attente…", RelayLabels.client(null))
        assertEquals("Télécommande connectée : Pixel 7", RelayLabels.client(RelayClient("Pixel 7", LinkKind.WIFI)))
        assertEquals("En attente d'une télécommande", RelayLabels.notification(null))
        assertEquals("Télécommande : Pixel 7", RelayLabels.notification(RelayClient("Pixel 7", LinkKind.BLUETOOTH)))
    }

    @Test
    fun linkLabels() {
        assertEquals("Wi-Fi local", RelayLink.WIFI.label)
        assertEquals("Point d'accès", RelayLink.HOTSPOT.label)
        assertEquals("Bluetooth", RelayLink.BLUETOOTH.label)
    }

    @Test
    fun qrPayloadOnlyWithHotspotInfo() {
        val off = RelayUiState(running = true, links = setOf(RelayLink.WIFI), pairingCode = "123456")
        assertNull(off.qrPayload)
        val on = off.copy(
            links = setOf(RelayLink.HOTSPOT),
            hotspot = HotspotInfo(ssid = "AndroidShare_1", password = "pw", hostIp = "192.168.49.1"),
        )
        assertEquals(QrPayload("AndroidShare_1", "pw", "192.168.49.1", 8765, "123456"), on.qrPayload)
        assertNull(on.copy(running = false).qrPayload)
        assertNull(on.copy(links = setOf(RelayLink.WIFI)).qrPayload)
    }

    @Test
    fun statusChipShowsMode() {
        assertEquals("Canon EOS 90D", statusChipLabel(AppMode.DIRECT, "Canon EOS 90D"))
        assertEquals("Relais • Canon EOS 90D", statusChipLabel(AppMode.RELAY, "Canon EOS 90D"))
        assertEquals("Télécommande • Déconnecté", statusChipLabel(AppMode.REMOTE, "Déconnecté"))
    }

    @Test
    fun runtimePermissionsPerSdk() {
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), RuntimePermissions.wifi(33))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), RuntimePermissions.wifi(32))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), RuntimePermissions.notifications(34))
        assertEquals(emptyList<String>(), RuntimePermissions.notifications(32))
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"),
            RuntimePermissions.bluetoothRelay(31),
        )
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
            RuntimePermissions.bluetoothRemote(31),
        )
    }
}
