package dev.canonremote.app.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class HotspotInfo(val ssid: String, val password: String, val hostIp: String)

/**
 * The relay's own access point ([WifiManager.startLocalOnlyHotspot]). The caller must hold
 * NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (API 32 and below), and location must be
 * enabled on API 32 and below.
 */
class RelayHotspot(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Starts the hotspot (stopping a previous one) and returns its credentials and the relay's
     * IPv4 address on it. Throws [SecurityException] when the permission is missing and
     * [IllegalStateException] when the hotspot can't be started.
     */
    @SuppressLint("MissingPermission")
    suspend fun start(): HotspotInfo {
        stop()
        val res = suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation> { cont ->
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (cont.isActive) {
                        cont.resume(reservation) { _, value, _ -> value.close() }
                    } else {
                        reservation.close()
                    }
                }

                override fun onFailed(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(failureMessage(reason)))
                }
            }
            try {
                wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                cont.resumeWithException(SecurityException("Autorisation « Appareils à proximité » requise pour le point d'accès", e))
            } catch (e: IllegalStateException) {
                // e.g. the caller already holds an active LocalOnlyHotspot request
                cont.resumeWithException(IllegalStateException("Point d'accès déjà actif ou indisponible", e))
            }
        }
        synchronized(this) { reservation = res }
        try {
            val config = res.softApConfiguration
            val ssid = ssidOf(config) ?: throw IllegalStateException("Point d'accès démarré sans nom de réseau")
            val password = config.passphrase ?: throw IllegalStateException("Point d'accès démarré sans mot de passe")
            val hostIp = awaitHotspotAddress()
                ?: throw IllegalStateException("Adresse du relais sur le point d'accès introuvable")
            return HotspotInfo(ssid = ssid, password = password, hostIp = hostIp)
        } catch (e: Throwable) {
            stop()
            throw e
        }
    }

    /** Closes the reservation (turns the hotspot off). */
    fun stop() {
        val r = synchronized(this) { reservation.also { reservation = null } } ?: return
        runCatching { r.close() }
    }

    @Suppress("DEPRECATION")
    private fun ssidOf(config: SoftApConfiguration): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            config.wifiSsid?.toString()
        } else {
            config.ssid
        }
        return raw?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    }

    /** The hotspot interface may get its address shortly after onStarted: retry for a few seconds. */
    private suspend fun awaitHotspotAddress(): String? {
        repeat(15) {
            hotspotAddress()?.let { return it }
            delay(200)
        }
        return null
    }

    private fun hotspotAddress(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { addr ->
                    LocalIpv4(interfaceName = nif.name, ipv4 = addr.hostAddress.orEmpty(), siteLocal = addr.isSiteLocalAddress)
                }
            }
            .filter { it.ipv4.isNotEmpty() }
        val station = stationWifiLinks()
        return pickHotspotAddress(
            candidates = candidates,
            stationInterfaces = station.mapNotNull { it.interfaceName }.toSet(),
            stationAddresses = station.flatMap { lp ->
                lp.linkAddresses.mapNotNull { la -> (la.address as? Inet4Address)?.hostAddress }
            }.toSet(),
        )
    }

    /** Link properties of the networks the device is connected to as a Wi-Fi client. */
    private fun stationWifiLinks(): List<LinkProperties> {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return networks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            cm.getLinkProperties(n)
        }
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Point d'accès impossible : désactive le partage de connexion et réessaie"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Point d'accès interdit sur cet appareil"
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "Point d'accès impossible : aucun canal Wi-Fi disponible"
        else -> "Impossible de démarrer le point d'accès (code $reason)"
    }
}

private const val JOIN_TIMEOUT_MS = 30_000
private const val JOIN_FAILED = "Impossible de rejoindre le point d'accès du relais"

private val joinLock = Any()
private var joined: Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>? = null

/**
 * Joins the relay's hotspot with a [WifiNetworkSpecifier] (the system asks the user to confirm),
 * then binds the whole process to it with [ConnectivityManager.bindProcessToNetwork] so that
 * [connectWebSocket] (Ktor CIO) goes through it. Suspends until the network is available; throws
 * [IllegalStateException] after 30 s. Call [leaveHotspot] when done.
 */
suspend fun joinHotspot(context: Context, ssid: String, password: String): Network {
    leaveHotspot()
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    val specifier = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .setWpa2Passphrase(password)
        .build()
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(specifier)
        .build()
    val available = CompletableDeferred<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            available.complete(network)
        }

        override fun onUnavailable() {
            available.completeExceptionally(IllegalStateException(JOIN_FAILED))
        }
    }
    synchronized(joinLock) { joined = cm to callback }
    try {
        cm.requestNetwork(request, callback, JOIN_TIMEOUT_MS)
        val network = withTimeoutOrNull(JOIN_TIMEOUT_MS + 1_000L) { available.await() }
            ?: throw IllegalStateException(JOIN_FAILED)
        if (!cm.bindProcessToNetwork(network)) throw IllegalStateException(JOIN_FAILED)
        return network
    } catch (e: CancellationException) {
        leaveHotspot()
        throw e
    } catch (e: IllegalStateException) {
        leaveHotspot()
        throw if (e.message == JOIN_FAILED) e else IllegalStateException(JOIN_FAILED, e)
    } catch (e: Exception) {
        leaveHotspot()
        throw IllegalStateException(JOIN_FAILED, e)
    }
}

/** Unbinds the process from the relay's hotspot and releases the network request. */
fun leaveHotspot() {
    val j = synchronized(joinLock) { joined.also { joined = null } } ?: return
    val (cm, callback) = j
    runCatching { cm.bindProcessToNetwork(null) }
    runCatching { cm.unregisterNetworkCallback(callback) }
}
