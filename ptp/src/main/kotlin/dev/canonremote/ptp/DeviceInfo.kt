package dev.canonremote.ptp

data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionDesc: String,
    val operationsSupported: List<Int>,
    val eventsSupported: List<Int>,
    val devicePropertiesSupported: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    companion object {
        fun decode(data: ByteArray): DeviceInfo {
            val r = ByteReader(data)
            val standardVersion = r.u16()
            val vendorExtensionId = r.u32()
            r.u16() // vendor extension version
            val vendorExtensionDesc = r.ptpString()
            r.u16() // functional mode
            val ops = r.u16Array()
            val events = r.u16Array()
            val props = r.u16Array()
            r.u16Array() // capture formats
            r.u16Array() // image formats
            return DeviceInfo(
                standardVersion = standardVersion,
                vendorExtensionId = vendorExtensionId,
                vendorExtensionDesc = vendorExtensionDesc,
                operationsSupported = ops,
                eventsSupported = events,
                devicePropertiesSupported = props,
                manufacturer = r.ptpString(),
                model = r.ptpString(),
                deviceVersion = r.ptpString(),
                serialNumber = r.ptpString(),
            )
        }
    }
}
