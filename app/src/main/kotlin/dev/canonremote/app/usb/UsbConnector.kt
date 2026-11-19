package dev.canonremote.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.canonremote.core.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UsbStatus {
    object NoDevice : UsbStatus
    data class Detected(val name: String) : UsbStatus
    object PermissionDenied : UsbStatus
    data class Connected(val name: String) : UsbStatus
}

class UsbConnector(context: Context, private val log: LogBus) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _status = MutableStateFlow<UsbStatus>(UsbStatus.NoDevice)
    val status: StateFlow<UsbStatus> = _status.asStateFlow()

    private var pendingGranted: ((UsbDevice) -> Unit)? = null
    private var connectedName: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val callback = pendingGranted
                    pendingGranted = null
                    if (granted && device != null) {
                        log.info("Autorisation USB accordée")
                        callback?.invoke(device)
                    } else {
                        log.warn("Autorisation USB refusée")
                        _status.value = UsbStatus.PermissionDenied
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun findCanon(): UsbDevice? =
        manager.deviceList.values.firstOrNull { it.vendorId == CANON_VENDOR_ID }

    /** Marks the camera session as open (or closed) so [status] reflects it. */
    fun markConnected(connected: Boolean) {
        connectedName = if (connected) findCanon()?.let(::deviceName) else null
        refresh()
    }

    fun refresh() {
        val device = findCanon()
        _status.value = when {
            device == null -> UsbStatus.NoDevice
            connectedName != null -> UsbStatus.Connected(connectedName!!)
            _status.value is UsbStatus.PermissionDenied -> UsbStatus.PermissionDenied
            else -> UsbStatus.Detected(deviceName(device))
        }
    }

    fun requestPermission(onGranted: (UsbDevice) -> Unit) {
        val device = findCanon()
        if (device == null) {
            refresh()
            return
        }
        if (manager.hasPermission(device)) {
            onGranted(device)
            return
        }
        pendingGranted = onGranted
        // Android 14: the intent must be explicit, and the PendingIntent mutable so the system can add extras.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        manager.requestPermission(device, pending)
    }

    fun open(device: UsbDevice): UsbTransport? = UsbTransport.open(manager, device)

    fun close() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun deviceName(device: UsbDevice): String = device.productName ?: device.deviceName

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
        private const val ACTION_USB_PERMISSION = "dev.canonremote.USB_PERMISSION"
    }
}
