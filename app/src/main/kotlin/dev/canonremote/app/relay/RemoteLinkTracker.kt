package dev.canonremote.app.relay

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkKind

const val LINK_LOST = "Connexion au relais perdue"
private const val UNCONFIRMED_SUFFIX = " non confirmé — vérifie l'appareil"

/**
 * Remembers why the link to the relay failed, so the Connexion card keeps showing it after the
 * snackbar has consumed (cleared) the camera's lastError. Fed with every remote state.
 */
class RemoteLinkTracker {
    var failure: String? = null
        private set
    private var lastStatus: ConnectionStatus? = null

    fun onState(status: ConnectionStatus, lastError: String?) {
        when (status) {
            ConnectionStatus.CONNECTED -> failure = null
            else -> {
                if (lastError != null) {
                    failure = if (lastError.endsWith(UNCONFIRMED_SUFFIX)) LINK_LOST else lastError
                } else if (lastStatus == ConnectionStatus.CONNECTED && failure == null) {
                    failure = LINK_LOST // the error itself may have been conflated away
                }
            }
        }
        lastStatus = status
    }

    /** A failure outside the camera state (e.g. the relay's access point could not be joined). */
    fun onFailure(message: String) {
        failure = message
    }

    /** New connection or explicit disconnection: forget the previous failure. */
    fun reset() {
        failure = null
        lastStatus = null
    }
}

/** Link status card of the Télécommande mode. */
data class RemoteLinkUi(
    val title: String,
    val detail: String?,
    val isError: Boolean,
    val canReconnect: Boolean,
    val canDisconnect: Boolean,
)

fun remoteLinkUi(status: ConnectionStatus, link: LinkKind, failure: String?): RemoteLinkUi = when (status) {
    ConnectionStatus.CONNECTED -> RemoteLinkUi(
        title = if (link == LinkKind.BLUETOOTH) "Connecté via Bluetooth" else "Connecté via Wi-Fi",
        detail = null,
        isError = false,
        canReconnect = false,
        canDisconnect = true,
    )
    ConnectionStatus.CONNECTING -> if (failure != null) {
        RemoteLinkUi(failure, "Reconnexion automatique…", isError = true, canReconnect = false, canDisconnect = true)
    } else {
        RemoteLinkUi("Connexion au relais…", null, isError = false, canReconnect = false, canDisconnect = true)
    }
    ConnectionStatus.DISCONNECTED -> if (failure != null) {
        RemoteLinkUi(failure, null, isError = true, canReconnect = true, canDisconnect = true)
    } else {
        RemoteLinkUi("Non connecté", null, isError = false, canReconnect = false, canDisconnect = false)
    }
}
