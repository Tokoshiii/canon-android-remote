package dev.canonremote.app.relay

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkTrackerTest {
    private val tracker = RemoteLinkTracker()

    private fun ui(
        link: LinkState,
        camera: ConnectionStatus = ConnectionStatus.CONNECTED,
        kind: LinkKind = LinkKind.WIFI,
        joinFailure: String? = null,
    ) = remoteLinkUi(link = link, camera = camera, kind = kind, joinFailure = joinFailure)

    @Test
    fun connectedShowsLink() {
        val wifi = ui(LinkState.Connected)
        assertEquals("Connecté via Wi-Fi", wifi.title)
        assertNull(wifi.detail)
        assertFalse(wifi.isError)
        assertTrue(wifi.canDisconnect)
        assertFalse(wifi.canReconnect)
        assertEquals("Connecté via Bluetooth", ui(LinkState.Connected, kind = LinkKind.BLUETOOTH).title)
    }

    @Test
    fun relayWithoutCameraIsConnectedNotLost() {
        val noCamera = ui(LinkState.Connected, camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Relais connecté — appareil non branché sur le relais", noCamera.title)
        assertEquals("Via Wi-Fi", noCamera.detail)
        assertFalse(noCamera.isError)
        assertFalse(noCamera.canReconnect)
        assertTrue(noCamera.canDisconnect)
        val opening = ui(LinkState.Connected, camera = ConnectionStatus.CONNECTING, kind = LinkKind.BLUETOOTH)
        assertEquals("Relais connecté — connexion à l'appareil sur le relais…", opening.title)
        assertEquals("Via Bluetooth", opening.detail)
        assertFalse(opening.isError)
    }

    @Test
    fun relayCameraUnpluggedDoesNotEndTheLink() {
        assertFalse(tracker.onLink(LinkState.Connecting))
        assertFalse(tracker.onLink(LinkState.Connected))
        // the relay camera going DISCONNECTED does not change the link state at all
        assertFalse(tracker.onLink(LinkState.Connected))
    }

    @Test
    fun lostLinkWhileReconnecting() {
        val ui = ui(LinkState.Reconnecting, camera = ConnectionStatus.CONNECTING)
        assertEquals("Connexion au relais perdue", ui.title)
        assertEquals("Reconnexion automatique…", ui.detail)
        assertTrue(ui.isError)
        assertFalse(ui.canReconnect)
        assertTrue(ui.canDisconnect)
    }

    @Test
    fun givingUpOffersReconnect() {
        val ui = ui(LinkState.GaveUp, camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Connexion au relais perdue", ui.title)
        assertNull(ui.detail)
        assertTrue(ui.isError)
        assertTrue(ui.canReconnect)
        assertFalse(ui.asksCode)
        assertTrue(ui.canDisconnect)
    }

    @Test
    fun linkEndsOnceOnGiveUpRejectionOrUnreachable() {
        tracker.onLink(LinkState.Connected)
        assertFalse(tracker.onLink(LinkState.Reconnecting))
        assertTrue(tracker.onLink(LinkState.GaveUp))
        assertFalse("reported once", tracker.onLink(LinkState.GaveUp))
        tracker.reset()
        assertTrue("conflated Connecting", tracker.onLink(LinkState.Unreachable))
        tracker.reset()
        tracker.onLink(LinkState.Connecting)
        assertTrue(tracker.onLink(LinkState.Rejected("Relais occupé")))
        tracker.reset()
        assertFalse(tracker.onLink(LinkState.Idle))
    }

    @Test
    fun wrongCodeAsksForTheCodeAgain() {
        val ui = ui(LinkState.Rejected("Code d'appairage incorrect"), camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Code d'appairage incorrect", ui.title)
        assertTrue(ui.isError)
        assertFalse("never replays the wrong code", ui.canReconnect)
        assertTrue(ui.asksCode)
        assertTrue(ui.canDisconnect)
    }

    @Test
    fun otherRejectionsAndUnreachableOfferReconnect() {
        val busy = ui(LinkState.Rejected("Relais occupé"), camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Relais occupé", busy.title)
        assertTrue(busy.canReconnect)
        assertFalse(busy.asksCode)
        val unreachable = ui(LinkState.Unreachable, camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Relais injoignable", unreachable.title)
        assertTrue(unreachable.isError)
        assertTrue(unreachable.canReconnect)
    }

    @Test
    fun idleAndConnecting() {
        val idle = ui(LinkState.Idle, camera = ConnectionStatus.DISCONNECTED)
        assertEquals("Non connecté", idle.title)
        assertFalse(idle.isError)
        assertFalse(idle.canDisconnect)
        assertFalse(idle.canReconnect)
        val connecting = ui(LinkState.Connecting, camera = ConnectionStatus.CONNECTING, kind = LinkKind.BLUETOOTH)
        assertEquals("Connexion au relais…", connecting.title)
        assertFalse(connecting.isError)
        assertTrue(connecting.canDisconnect)
    }

    @Test
    fun joinFailureIsShownUntilConnected() {
        tracker.onJoinFailure("Impossible de rejoindre le point d'accès du relais")
        assertEquals("Impossible de rejoindre le point d'accès du relais", tracker.joinFailure)
        val failed = ui(LinkState.Idle, camera = ConnectionStatus.DISCONNECTED, joinFailure = tracker.joinFailure)
        assertEquals("Impossible de rejoindre le point d'accès du relais", failed.title)
        assertTrue(failed.isError)
        assertTrue(failed.canReconnect)
        assertTrue(failed.canDisconnect)
        tracker.onLink(LinkState.Connected)
        assertNull(tracker.joinFailure)
        tracker.onJoinFailure("x")
        tracker.reset()
        assertNull(tracker.joinFailure)
    }
}
