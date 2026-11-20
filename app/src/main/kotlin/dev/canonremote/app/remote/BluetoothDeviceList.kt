package dev.canonremote.app.remote

/** A Bluetooth device as listed on the remote (no Android type, so it is testable on the JVM). */
data class BtDevice(val address: String, val name: String?) {
    /** Text shown for the device: its name, or its address when it has none. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

private val byLabel = compareBy<BtDevice, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.address }

/**
 * Remote-side lists of candidate relays: [paired] (bonded in Android) and [nearby] (found by a
 * discovery, not bonded). Devices are deduplicated by address, a bonded device never appears in
 * [nearby], unnamed discovered devices are ignored, and each list is sorted by name.
 */
data class BluetoothDeviceList(
    val paired: List<BtDevice> = emptyList(),
    val nearby: List<BtDevice> = emptyList(),
) {
    /** Bonded first, then nearby. */
    val all: List<BtDevice> get() = paired + nearby

    /** Replaces the paired list; devices now bonded leave [nearby]. */
    fun withBonded(devices: List<BtDevice>): BluetoothDeviceList {
        val bonded = devices.distinctBy { it.address }.sortedWith(byLabel)
        val bondedAddresses = bonded.mapTo(HashSet()) { it.address }
        return copy(paired = bonded, nearby = nearby.filterNot { it.address in bondedAddresses })
    }

    /** Adds (or renames) a device found by a discovery; ignored when unnamed or already bonded. */
    fun withFound(device: BtDevice): BluetoothDeviceList {
        if (device.name.isNullOrBlank()) return this
        if (paired.any { it.address == device.address }) return this
        val others = nearby.filterNot { it.address == device.address }
        return copy(nearby = (others + device).sortedWith(byLabel))
    }

    fun clearNearby(): BluetoothDeviceList = copy(nearby = emptyList())
}
