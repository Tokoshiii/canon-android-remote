package dev.canonremote.app.dev

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevModeTest {
    @Test
    fun offWhenDevModeOff() {
        assertFalse(devCameraWanted(devMode = false, remoteMode = false, usb = ConnectionStatus.DISCONNECTED, remoteLink = null))
        assertFalse(devCameraWanted(devMode = false, remoteMode = true, usb = ConnectionStatus.DISCONNECTED, remoteLink = null))
    }

    @Test
    fun directModeYieldsToUsbCameraConnectingOrConnected() {
        assertTrue(devCameraWanted(devMode = true, remoteMode = false, usb = ConnectionStatus.DISCONNECTED, remoteLink = null))
        assertFalse(devCameraWanted(devMode = true, remoteMode = false, usb = ConnectionStatus.CONNECTING, remoteLink = null))
        assertFalse(devCameraWanted(devMode = true, remoteMode = false, usb = ConnectionStatus.CONNECTED, remoteLink = null))
    }

    @Test
    fun remoteModeYieldsToActiveRelayLink() {
        listOf(LinkState.Connecting, LinkState.Connected, LinkState.Reconnecting).forEach { link ->
            assertFalse("$link", devCameraWanted(devMode = true, remoteMode = true, usb = ConnectionStatus.DISCONNECTED, remoteLink = link))
        }
        listOf(null, LinkState.Idle, LinkState.GaveUp, LinkState.Unreachable, LinkState.Rejected("x")).forEach { link ->
            assertTrue("$link", devCameraWanted(devMode = true, remoteMode = true, usb = ConnectionStatus.DISCONNECTED, remoteLink = link))
        }
    }
}
