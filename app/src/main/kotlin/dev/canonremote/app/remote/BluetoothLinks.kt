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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * [onClient] in its own coroutine, independent of the accept loop: [stop] closes the server socket
 * only, already accepted channels stay with their owner.
 *
 * [running] is true while the accept loop listens. The loop also ends by itself (Bluetooth turned
 * off, server socket error): [running] becomes false, [onStopped] is called with the error, and
 * [start] can be called again.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BluetoothRelayServer(
    private val adapter: BluetoothAdapter,
    private val onStopped: (error: Throwable?) -> Unit = {},
    private val onClient: suspend (MessageChannel) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Throws [IOException] when Bluetooth is off or the service can't be registered. */
    @Synchronized
    fun start() {
        if (acceptJob?.isActive == true) return
        val ss = adapter.listenUsingRfcommWithServiceRecord(RELAY_BT_SERVICE_NAME, RELAY_UUID)
        serverSocket = ss
        _running.value = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var error: Throwable? = null
            try {
                while (isActive) {
                    val socket = ss.accept()
                    val channel = socket.toChannel()
                    scope.launch {
                        try {
                            onClient(channel)
                        } catch (e: CancellationException) {
                            channel.close()
                            throw e
                        } catch (e: Throwable) {
                            channel.close()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e // server socket closed (stop), Bluetooth turned off, ...
            } finally {
                val current = synchronized(this@BluetoothRelayServer) {
                    val mine = acceptJob === coroutineContext[Job]
                    if (mine) {
                        runCatching { ss.close() }
                        serverSocket = null
                        acceptJob = null
                        _running.value = false
                    }
                    mine
                }
                if (current) runCatching { onStopped(error) }
            }
        }
        acceptJob = job
        job.start()
    }

    /** Closes the server socket; [onStopped] is not called for an explicit stop. */
    @Synchronized
    fun stop() {
        val job = acceptJob
        acceptJob = null // the ending loop sees it is no longer current
        runCatching { serverSocket?.close() }
        serverSocket = null
        job?.cancel()
        _running.value = false
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
                } catch (e: Throwable) {
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

/** The device as listed on the remote. Callers must hold BLUETOOTH_CONNECT (for the name). */
@SuppressLint("MissingPermission")
fun BluetoothDevice.toBtDevice(fallbackName: String? = null): BtDevice =
    BtDevice(address = address, name = runCatching { name }.getOrNull() ?: fallbackName)
