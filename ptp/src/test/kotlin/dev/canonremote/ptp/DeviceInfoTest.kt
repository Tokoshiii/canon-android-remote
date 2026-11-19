package dev.canonremote.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceInfoTest {
    private fun ByteWriter.ptpString(s: String) {
        if (s.isEmpty()) {
            u8(0)
            return
        }
        u8(s.length + 1)
        s.forEach { u16(it.code) }
        u16(0)
    }

    private fun ByteWriter.u16Array(values: List<Int>) {
        u32(values.size)
        values.forEach { u16(it) }
    }

    private fun head(w: ByteWriter, ops: List<Int>) {
        w.u16(100)
        w.u32(0x0000000B)
        w.u16(100)
        w.ptpString("canon.com: 1.0")
        w.u16(0)
        w.u16Array(ops)
        w.u16Array(listOf(0x4002))
        w.u16Array(listOf(0xD101, 0xD102))
        w.u16Array(emptyList())
        w.u16Array(listOf(0x3801))
        w.ptpString("Canon Inc.")
        w.ptpString("Canon EOS 90D")
    }

    @Test
    fun decodesCanonDeviceInfo() {
        val w = ByteWriter()
        head(w, listOf(0x1001, 0x9116))
        w.ptpString("3-1.0.0")
        w.ptpString("0123456789")
        val info = DeviceInfo.decode(w.toByteArray())
        assertEquals(100, info.standardVersion)
        assertEquals(0x0000000B, info.vendorExtensionId)
        assertEquals("canon.com: 1.0", info.vendorExtensionDesc)
        assertEquals(listOf(0x1001, 0x9116), info.operationsSupported)
        assertEquals(listOf(0x4002), info.eventsSupported)
        assertEquals(listOf(0xD101, 0xD102), info.devicePropertiesSupported)
        assertEquals("Canon Inc.", info.manufacturer)
        assertEquals("Canon EOS 90D", info.model)
        assertEquals("3-1.0.0", info.deviceVersion)
        assertEquals("0123456789", info.serialNumber)
    }

    @Test
    fun decodeTruncatedThrowsFormatException() {
        val w = ByteWriter()
        head(w, listOf(0x1001, 0x9116))
        assertThrows(PtpFormatException::class.java) { DeviceInfo.decode(w.toByteArray()) }
    }
}
