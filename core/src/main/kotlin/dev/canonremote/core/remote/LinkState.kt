package dev.canonremote.core.remote

/**
 * State of a [RemoteCamera]'s link to the relay, independent of the relay camera's status: a relay
 * whose USB camera is unplugged or off is still [Connected].
 */
sealed interface LinkState {
    /** Never connected, or disconnected on purpose. */
    data object Idle : LinkState

    /** First connection in progress. */
    data object Connecting : LinkState

    /** Welcomed by the relay. */
    data object Connected : LinkState

    /** Link lost: reconnecting automatically during the reconnection window. */
    data object Reconnecting : LinkState

    /** Link lost and not recovered within the reconnection window. */
    data object GaveUp : LinkState

    /** First connection failed: relay unreachable or silent. */
    data object Unreachable : LinkState

    /** Refused by the relay ([reason] as sent by it, e.g. « Code d'appairage incorrect »). */
    data class Rejected(val reason: String) : LinkState
}
