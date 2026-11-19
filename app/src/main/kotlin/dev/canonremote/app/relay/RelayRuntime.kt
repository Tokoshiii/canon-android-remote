package dev.canonremote.app.relay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dev.canonremote.app.CameraHub
import dev.canonremote.app.remote.BluetoothRelayServer
import dev.canonremote.app.remote.RELAY_WS_PORT
import dev.canonremote.app.remote.RelayNsd
import dev.canonremote.app.remote.RelayWebSocketServer
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.RelayServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Starting/stopping link servers blocks (WebSocket stop ~1.2 s) and must not overlap, even across
 * runtimes: a relay restarted at once waits until the previous one has released the port.
 */
private val linkLock = Mutex()

/** Scope for the release of a runtime, which outlives the service. */
private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * One run of the relay (one pairing code), owned by [RelayService]: the [RelayServer] serving the
 * USB camera of [hub], and the link servers switched on in [CameraHub.relayState] (Wi-Fi local:
 * WebSocket + NSD; Point d'accès: the same WebSocket server, the access point itself being held by
 * the hub; Bluetooth: RFCOMM server).
 */
internal class RelayRuntime(
    context: Context,
    private val hub: CameraHub,
    val pairingCode: String,
    parent: CoroutineScope,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
    private val camera = FollowingCamera(hub.usbCamera, scope)
    private val server = RelayServer(camera = camera, log = hub.log, scope = scope, pairingCode = pairingCode)
    private val webSocket = RelayWebSocketServer(port = RELAY_WS_PORT) { channel -> server.accept(channel, LinkKind.WIFI) }
    private val nsd = RelayNsd(context)
    private val bluetooth: BluetoothRelayServer? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.let { adapter ->
            BluetoothRelayServer(
                adapter = adapter,
                onStopped = { error ->
                    hub.log.warn("Relais Bluetooth arrêté : ${error?.message ?: "raison inconnue"}")
                    hub.reportLinkError(RelayLink.BLUETOOTH, "Bluetooth arrêté : ${error?.message ?: "raison inconnue"}")
                },
                onClient = { channel -> server.accept(channel, LinkKind.BLUETOOTH) },
            )
        }

    // Touched only under linkLock.
    private var released = false
    private var webSocketOn = false
    private var nsdOn = false
    private var bluetoothWanted = false
    private var bluetoothGeneration = -1

    init {
        scope.launch { server.clients.collect { hub.reportClient(it) } }
        scope.launch {
            combine(
                hub.relayState.map { it.links }.distinctUntilChanged(),
                hub.bluetoothRestarts,
            ) { links, restarts -> links to restarts }
                .conflate()
                .collect { (links, restarts) ->
                    withContext(Dispatchers.IO + NonCancellable) { linkLock.withLock { reconcile(links, restarts) } }
                }
        }
    }

    private fun reconcile(links: Set<RelayLink>, restarts: Int) {
        if (released) return
        val wantWebSocket = RelayLink.WIFI in links || RelayLink.HOTSPOT in links
        if (wantWebSocket && !webSocketOn) {
            try {
                webSocket.start()
                webSocketOn = true
                hub.log.info("Relais : serveur Wi-Fi à l'écoute (port $RELAY_WS_PORT)")
                hub.reportLinkError(RelayLink.WIFI, null)
            } catch (e: Exception) {
                val message = "Serveur Wi-Fi impossible : ${e.message ?: e::class.simpleName}"
                hub.log.error(message, e)
                if (RelayLink.WIFI in links) hub.reportLinkError(RelayLink.WIFI, message)
                if (RelayLink.HOTSPOT in links) hub.reportLinkError(RelayLink.HOTSPOT, message)
            }
        }
        val wantNsd = RelayLink.WIFI in links && webSocketOn
        if (wantNsd && !nsdOn) {
            try {
                nsd.register(port = RELAY_WS_PORT, name = "Canon Remote (${Build.MODEL})")
                nsdOn = true
            } catch (e: Exception) {
                hub.log.error("Annonce du relais sur le Wi-Fi impossible", e)
                hub.reportLinkError(RelayLink.WIFI, "Annonce sur le Wi-Fi impossible : ${e.message ?: e::class.simpleName}")
            }
        } else if (!wantNsd && nsdOn) {
            runCatching { nsd.unregister() }
            nsdOn = false
        }
        if (!wantWebSocket && webSocketOn) {
            runCatching { webSocket.stop() }
            webSocketOn = false
        }

        val wantBluetooth = RelayLink.BLUETOOTH in links
        if (wantBluetooth && (!bluetoothWanted || restarts != bluetoothGeneration)) {
            bluetoothGeneration = restarts
            startBluetooth()
        } else if (!wantBluetooth && bluetoothWanted) {
            bluetooth?.stop()
        }
        bluetoothWanted = wantBluetooth
    }

    private fun startBluetooth() {
        val bt = bluetooth
        if (bt == null) {
            hub.reportLinkError(RelayLink.BLUETOOTH, "Bluetooth indisponible sur cet appareil")
            return
        }
        try {
            bt.start()
            hub.log.info("Relais : Bluetooth à l'écoute")
            hub.reportLinkError(RelayLink.BLUETOOTH, null)
        } catch (e: SecurityException) {
            hub.reportLinkError(RelayLink.BLUETOOTH, "Autorisation Bluetooth requise")
        } catch (e: Exception) {
            hub.log.warn("Relais Bluetooth impossible : ${e.message}")
            hub.reportLinkError(RelayLink.BLUETOOTH, "Bluetooth inactif : active-le puis touche Relancer")
        }
    }

    /** Ends the sessions at once, then stops the link servers off the main thread. */
    fun release() {
        scope.cancel()
        hub.reportClient(null)
        releaseScope.launch {
            linkLock.withLock {
                released = true
                if (nsdOn) runCatching { nsd.unregister() }
                if (webSocketOn) runCatching { webSocket.stop() }
                bluetooth?.release()
                nsdOn = false
                webSocketOn = false
            }
        }
    }
}
