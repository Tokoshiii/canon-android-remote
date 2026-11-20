package dev.canonremote.app.dev

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkState

/**
 * Whether the Appareil screen uses the [DevCameraController]: developer mode is on and no real
 * camera is there. A real camera takes over as soon as it is connecting: the USB camera in the
 * Direct and Relais modes, the relay link ([remoteLink], null without remote) in Télécommande.
 */
fun devCameraWanted(devMode: Boolean, remoteMode: Boolean, usb: ConnectionStatus, remoteLink: LinkState?): Boolean {
    if (!devMode) return false
    val realCamera = if (remoteMode) {
        remoteLink == LinkState.Connecting || remoteLink == LinkState.Connected || remoteLink == LinkState.Reconnecting
    } else {
        usb != ConnectionStatus.DISCONNECTED
    }
    return !realCamera
}
