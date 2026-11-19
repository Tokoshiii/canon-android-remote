package dev.canonremote.core

import dev.canonremote.ptp.ByteWriter

val TERMINATOR = byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0)

private fun ByteWriter.ptpString(s: String) {
    u8(s.length + 1)
    s.forEach { u16(it.code) }
    u16(0)
}

fun testDeviceInfo(): ByteArray {
    val w = ByteWriter()
    w.u16(100); w.u32(0xB); w.u16(100)
    w.ptpString("canon.com: 1.0")
    w.u16(0)
    repeat(4) { w.u32(0) }
    w.u32(0)
    w.ptpString("Canon Inc.")
    w.ptpString("Canon EOS 90D")
    w.ptpString("3-1.0.0")
    w.ptpString("0123456789")
    return w.toByteArray()
}

fun testRecord(type: Int, vararg body: Int): ByteArray {
    val w = ByteWriter()
    w.u32(8 + body.size * 4)
    w.u32(type)
    body.forEach { w.u32(it) }
    return w.toByteArray()
}
