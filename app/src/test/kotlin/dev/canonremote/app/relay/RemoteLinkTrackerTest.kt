package dev.canonremote.app.relay

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkTrackerTest {
    private val tracker = RemoteLinkTracker()

    @Test
    fun connectedShowsLink() {
        tracker.onState(ConnectionStatus.CONNECTING, null)
        tracker.onState(ConnectionStatus.CONNECTED, null)
        val wifi = remoteLinkUi(ConnectionStatus.CONNECTED, LinkKind.WIFI, tracker.failure)
        assertEquals("Connecté via Wi-Fi", wifi.title)
        assertFalse(wifi.isError)
        assertTrue(wifi.canDisconnect)
        assertFalse(wifi.canReconnect)
        assertEquals("Connecté via Bluetooth", remoteLinkUi(ConnectionStatus.CONNECTED, LinkKind.BLUETOOTH, null).title)
    }

    @Test
    fun lostLinkWhileReconnecting() {
        tracker.onState(ConnectionStatus.CONNECTED, null)
        tracker.onState(ConnectionStatus.CONNECTING, "Connexion au relais perdue")
        tracker.onState(ConnectionStatus.CONNECTING, null) // error drained by the snackbar
        assertEquals("Connexion au relais perdue", tracker.failure)
        val ui = remoteLinkUi(ConnectionStatus.CONNECTING, LinkKind.WIFI, tracker.failure)
        assertEquals("Connexion au relais perdue", ui.title)
        assertEquals("Reconnexion automatique…", ui.detail)
        assertTrue(ui.isError)
        assertFalse(ui.canReconnect)
    }

    @Test
    fun lostLinkDetectedEvenIfErrorWasConflatedAway() {
        tracker.onState(ConnectionStatus.CONNECTED, null)
        tracker.onState(ConnectionStatus.CONNECTING, null)
        assertEquals("Connexion au relais perdue", tracker.failure)
    }

    @Test
    fun unconfirmedCommandCountsAsLinkLoss() {
        tracker.onState(ConnectionStatus.CONNECTED, null)
        tracker.onState(ConnectionStatus.CONNECTING, "Déclenchement non confirmé — vérifie l'appareil")
        assertEquals("Connexion au relais perdue", tracker.failure)
    }

    @Test
    fun givingUpOffersReconnect() {
        tracker.onState(ConnectionStatus.CONNECTED, null)
        tracker.onState(ConnectionStatus.CONNECTING, "Connexion au relais perdue")
        tracker.onState(ConnectionStatus.DISCONNECTED, "Connexion au relais perdue")
        tracker.onState(ConnectionStatus.DISCONNECTED, null)
        val ui = remoteLinkUi(ConnectionStatus.DISCONNECTED, LinkKind.WIFI, tracker.failure)
        assertEquals("Connexion au relais perdue", ui.title)
        assertNull(ui.detail)
        assertTrue(ui.isError)
        assertTrue(ui.canReconnect)
        assertTrue(ui.canDisconnect)
    }

    @Test
    fun rejectionIsShown() {
        tracker.onState(ConnectionStatus.CONNECTING, null)
        tracker.onState(ConnectionStatus.DISCONNECTED, "Code d'appairage incorrect")
        tracker.onState(ConnectionStatus.DISCONNECTED, null)
        assertEquals("Code d'appairage incorrect", tracker.failure)
    }

    @Test
    fun commandErrorsWhileConnectedAreNotLinkFailures() {
        tracker.onState(ConnectionStatus.CONNECTED, "Réglage refusé")
        assertNull(tracker.failure)
    }

    @Test
    fun reconnectionClearsFailure() {
        tracker.onState(ConnectionStatus.CONNECTED, null)
        tracker.onState(ConnectionStatus.CONNECTING, "Connexion au relais perdue")
        tracker.onState(ConnectionStatus.CONNECTED, null)
        assertNull(tracker.failure)
        tracker.onState(ConnectionStatus.DISCONNECTED, "Relais injoignable")
        tracker.reset()
        assertNull(tracker.failure)
    }

    @Test
    fun idleAndConnecting() {
        val idle = remoteLinkUi(ConnectionStatus.DISCONNECTED, LinkKind.WIFI, null)
        assertEquals("Non connecté", idle.title)
        assertFalse(idle.isError)
        assertFalse(idle.canDisconnect)
        val connecting = remoteLinkUi(ConnectionStatus.CONNECTING, LinkKind.BLUETOOTH, null)
        assertEquals("Connexion au relais…", connecting.title)
        assertTrue(connecting.canDisconnect)
    }

    @Test
    fun externalFailureIsKeptUntilConnected() {
        tracker.onFailure("Impossible de rejoindre le point d'accès du relais")
        tracker.onState(ConnectionStatus.DISCONNECTED, null)
        assertEquals("Impossible de rejoindre le point d'accès du relais", tracker.failure)
        tracker.onState(ConnectionStatus.CONNECTED, null)
        assertNull(tracker.failure)
    }
}
