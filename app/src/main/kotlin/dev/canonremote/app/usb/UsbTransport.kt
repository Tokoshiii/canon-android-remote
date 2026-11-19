package dev.canonremote.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.canonremote.core.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : Transport {
    override val name: String = "USB"

    private var buffer = ByteArray(RECEIVE_BUFFER_SIZE)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(SEND_CHUNK_SIZE, bytes.size - offset)
            val n = connection.bulkTransfer(bulkOut, bytes, offset, chunk, TIMEOUT_MS)
            if (n < 0) throw IOException("USB: transfert échoué")
            offset += n
        }
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        var n = connection.bulkTransfer(bulkIn, buffer, 0, buffer.size, TIMEOUT_MS)
        if (n < 0) throw IOException("USB: transfert échoué")
        while (n < 4) {
            val m = connection.bulkTransfer(bulkIn, buffer, n, buffer.size - n, TIMEOUT_MS)
            if (m < 0) throw IOException("USB: transfert échoué")
            n += m
        }
        val total = ((buffer[0].toLong() and 0xFF) or
            ((buffer[1].toLong() and 0xFF) shl 8) or
            ((buffer[2].toLong() and 0xFF) shl 16) or
            ((buffer[3].toLong() and 0xFF) shl 24)).toInt()
        if (total < 12 || total > MAX_CONTAINER_SIZE) throw IOException("USB: longueur de conteneur invalide ($total)")
        if (total > buffer.size) buffer = buffer.copyOf(total)
        while (n < total) {
            val m = connection.bulkTransfer(bulkIn, buffer, n, buffer.size - n, TIMEOUT_MS)
            if (m < 0) throw IOException("USB: transfert échoué")
            n += m
        }
        buffer.copyOf(total)
    }

    override fun close() {
        try {
            connection.releaseInterface(iface)
        } finally {
            connection.close()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        private const val SEND_CHUNK_SIZE = 16 * 1024
        private const val RECEIVE_BUFFER_SIZE = 1024 * 1024
        private const val MAX_CONTAINER_SIZE = 64 * 1024 * 1024
        private const val USB_CLASS_STILL_IMAGE = 6

        fun open(manager: UsbManager, device: UsbDevice): UsbTransport? {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val iface = interfaces.firstOrNull { it.interfaceClass == USB_CLASS_STILL_IMAGE && bulkEndpoints(it) != null }
                ?: interfaces.firstOrNull { bulkEndpoints(it) != null }
                ?: return null
            val (bulkIn, bulkOut) = bulkEndpoints(iface) ?: return null
            val connection = manager.openDevice(device) ?: return null
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return null
            }
            return UsbTransport(connection, iface, bulkIn, bulkOut)
        }

        private fun bulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            val bulk = (0 until iface.endpointCount)
                .map { iface.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
            val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
            return input to output
        }
    }
}
