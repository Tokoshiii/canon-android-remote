package dev.canonremote.app.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** DNS-SD service type announced by the relay on the local Wi-Fi. */
const val RELAY_SERVICE_TYPE = "_canonremote._tcp"

data class DiscoveredRelay(val name: String, val host: String, val port: Int)

/** NSD (mDNS / DNS-SD) announcement and discovery of relays. */
class RelayNsd(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    /** Announces the relay's WebSocket server ([port]) under [name]. Replaces a previous registration. */
    @Synchronized
    fun register(port: Int, name: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = RELAY_SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@RelayNsd) { if (registration === this) registration = null }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun unregister() {
        val listener = registration ?: return
        registration = null
        runCatching { nsd.unregisterService(listener) }
    }

    private sealed interface Event {
        data class Found(val info: NsdServiceInfo) : Event
        data class Lost(val name: String) : Event
    }

    /**
     * Emits the current list of resolved relays each time it changes (starting with an empty list).
     * Services are resolved one at a time (concurrent resolutions fail on API 31-33). Discovery
     * stops when the flow is cancelled. Fails with [IllegalStateException] if discovery can't start.
     */
    fun discover(): Flow<List<DiscoveredRelay>> = callbackFlow {
        val events = Channel<Event>(Channel.UNLIMITED)
        val relays = LinkedHashMap<String, DiscoveredRelay>()
        send(emptyList())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                events.trySend(Event.Found(serviceInfo))
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                events.trySend(Event.Lost(serviceInfo.serviceName))
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("Recherche des relais impossible (code $errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        // Single consumer: found/lost events and resolutions are handled strictly in order.
        launch {
            for (event in events) {
                when (event) {
                    is Event.Found -> {
                        val relay = resolve(event.info) ?: continue
                        relays[relay.name] = relay
                    }
                    is Event.Lost -> if (relays.remove(event.name) == null) continue
                }
                send(relays.values.toList())
            }
        }

        nsd.discoverServices(RELAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            events.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredRelay? =
        suspendCancellableCoroutine<DiscoveredRelay?> { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = hostOf(serviceInfo)
                    val relay = host?.let { DiscoveredRelay(name = serviceInfo.serviceName, host = it, port = serviceInfo.port) }
                    if (cont.isActive) cont.resume(relay)
                }
            }
            cont.invokeOnCancellation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { nsd.stopServiceResolution(listener) }
                }
            }
            nsd.resolveService(info, listener)
        }

    /** Prefers an IPv4 address (no scope id / brackets needed in the WebSocket URL). */
    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses
            else listOfNotNull(info.host)
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return chosen?.hostAddress
    }
}
