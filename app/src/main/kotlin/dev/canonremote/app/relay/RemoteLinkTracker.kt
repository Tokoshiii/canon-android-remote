package dev.canonremote.app.relay

import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.LinkState
import dev.canonremote.core.remote.RelayServer
import dev.canonremote.core.remote.RemoteCamera

const val LINK_LOST = RemoteCamera.LINK_LOST

/** The link is over until the user acts (« Se reconnecter » or a new connection). */
private val LinkState.isFinal: Boolean
    get() = this == LinkState.GaveUp || this == LinkState.Unreachable || this is LinkState.Rejected

/**
 * Follows the [RemoteCamera.linkState] of the current remote (never the relay camera's status) and
 * remembers a failure outside it (the relay's access point could not be joined).
 */
class RemoteLinkTracker {
    var joinFailure: String? = null
        private set
    private var last: LinkState = LinkState.Idle

    /** Returns true once when the link has just ended for good (time to leave the access point). */
    fun onLink(state: LinkState): Boolean {
        val ended = state.isFinal && !last.isFinal
        last = state
        if (state == LinkState.Connected) joinFailure = null
        return ended
    }

    fun onJoinFailure(message: String) {
        joinFailure = message
    }

    /** New connection or explicit disconnection: forget the previous link. */
    fun reset() {
        joinFailure = null
        last = LinkState.Idle
    }
}

/** Link status card of the Télécommande mode. [asksCode]: « Se reconnecter » must ask for the code again. */
data class RemoteLinkUi(
    val title: String,
    val detail: String?,
    val isError: Boolean,
    val canReconnect: Boolean,
    val canDisconnect: Boolean,
    val asksCode: Boolean = false,
)

/** [camera] is the relay camera's status as mirrored by the remote; it only matters once linked. */
fun remoteLinkUi(link: LinkState, camera: ConnectionStatus, kind: LinkKind, joinFailure: String?): RemoteLinkUi {
    if (joinFailure != null && link != LinkState.Connected) {
        return RemoteLinkUi(joinFailure, null, isError = true, canReconnect = true, canDisconnect = true)
    }
    val via = if (kind == LinkKind.BLUETOOTH) "Bluetooth" else "Wi-Fi"
    return when (link) {
        LinkState.Idle -> RemoteLinkUi("Non connecté", null, isError = false, canReconnect = false, canDisconnect = false)
        LinkState.Connecting ->
            RemoteLinkUi("Connexion au relais…", null, isError = false, canReconnect = false, canDisconnect = true)
        LinkState.Connected -> when (camera) {
            ConnectionStatus.CONNECTED ->
                RemoteLinkUi("Connecté via $via", null, isError = false, canReconnect = false, canDisconnect = true)
            ConnectionStatus.CONNECTING -> RemoteLinkUi(
                "Relais connecté — connexion à l'appareil sur le relais…",
                "Via $via",
                isError = false,
                canReconnect = false,
                canDisconnect = true,
            )
            ConnectionStatus.DISCONNECTED -> RemoteLinkUi(
                "Relais connecté — appareil non branché sur le relais",
                "Via $via",
                isError = false,
                canReconnect = false,
                canDisconnect = true,
            )
        }
        LinkState.Reconnecting ->
            RemoteLinkUi(LINK_LOST, "Reconnexion automatique…", isError = true, canReconnect = false, canDisconnect = true)
        LinkState.GaveUp -> RemoteLinkUi(LINK_LOST, null, isError = true, canReconnect = true, canDisconnect = true)
        LinkState.Unreachable ->
            RemoteLinkUi(RemoteCamera.UNREACHABLE, null, isError = true, canReconnect = true, canDisconnect = true)
        is LinkState.Rejected -> {
            val wrongCode = link.reason == RelayServer.WRONG_CODE
            RemoteLinkUi(
                link.reason,
                null,
                isError = true,
                canReconnect = !wrongCode,
                canDisconnect = true,
                asksCode = wrongCode,
            )
        }
    }
}
