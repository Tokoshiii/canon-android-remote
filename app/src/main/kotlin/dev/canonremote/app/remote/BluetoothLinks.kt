package dev.canonremote.app.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import dev.canonremote.core.remote.MessageChannel
import dev.canonremote.core.remote.StreamMessageChannel
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fixed RFCOMM service UUID of the app's relay. */
val RELAY_UUID: UUID = UUID.fromString("6f1c2a8e-3b5d-4c7e-9a1f-2d8b0c4e5f61")

/** SDP service name of the relay. */
const val RELAY_BT_SERVICE_NAME = "Canon Remote"

private fun BluetoothSocket.toChannel(): MessageChannel = StreamMessageChannel(
    input = inputStream,
    output = outputStream,
    onClose = { runCatching { close() } },
    io = Dispatchers.IO,
)

/**
 * Relay-side RFCOMM server. [start] listens with [RELAY_UUID] / [RELAY_BT_SERVICE_NAME] and accepts
 * clients on [Dispatchers.IO]; each socket is wrapped in a [StreamMessageChannel] and handed to
 * [onClient] (in its own coroutine). [stop] closes the server socket (already accepted channels
 * stay with their owner).
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BluetoothRelayServer(
    private val adapter: BluetoothAdapter,
    private val onClient: suspend (MessageChannel) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    /** Throws [IOException] when Bluetooth is off or the service can't be registered. */
    @Synchronized
    fun start() {
        if (acceptJob != null) return
        val ss = adapter.listenUsingRfcommWithServiceRecord(RELAY_BT_SERVICE_NAME, RELAY_UUID)
        serverSocket = ss
        acceptJob = scope.launch {
            while (isActive) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break // server socket closed (stop) or Bluetooth turned off
                }
                val channel = socket.toChannel()
                launch {
                    try {
                        onClient(channel)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        channel.close()
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    /** Stops and releases the server's coroutine scope; the instance can't be restarted. */
    fun release() {
        stop()
        scope.cancel()
    }
}

/**
 * Connects to a relay over RFCOMM ([RELAY_UUID]). Cancellable: cancelling closes the socket, which
 * aborts the blocking connect. Throws [IOException] with a French message on failure.
 *
 * Callers must hold BLUETOOTH_CONNECT (and BLUETOOTH_SCAN for cancelling an ongoing discovery).
 */
@SuppressLint("MissingPermission")
suspend fun connectBluetooth(device: BluetoothDevice): MessageChannel {
    @Suppress("DEPRECATION")
    runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } // discovery slows connect
    val socket = device.createRfcommSocketToServiceRecord(RELAY_UUID)
    try {
        // The blocking connect runs outside structured concurrency so that cancellation returns
        // at once; closing the socket aborts connect(), and a late result is ignored.
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { runCatching { socket.close() } }
            Dispatchers.IO.asExecutor().execute {
                try {
                    socket.connect()
                    cont.resume(Unit)
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                }
            }
        }
        return socket.toChannel()
    } catch (e: CancellationException) {
        runCatching { socket.close() }
        throw e
    } catch (e: IOException) {
        runCatching { socket.close() }
        throw IOException("Connexion Bluetooth impossible : ${e.message ?: "erreur inconnue"}", e)
    } catch (e: Throwable) {
        runCatching { socket.close() }
        throw e
    }
}

/** Paired devices (candidates to be the relay). Callers must hold BLUETOOTH_CONNECT. */
@SuppressLint("MissingPermission")
fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
    runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        .sortedBy { it.name ?: it.address }
