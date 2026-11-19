package dev.canonremote.ptp

import java.io.ByteArrayOutputStream

class PtpFormatException(message: String) : Exception(message)

/** Little-endian writer. Values are truncated to the field width. */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) {
        out.write(v and 0xFF)
    }

    fun u16(v: Int) {
        u8(v)
        u8(v ushr 8)
    }

    fun u32(v: Int) {
        u16(v)
        u16(v ushr 16)
    }

    fun bytes(b: ByteArray) {
        out.write(b, 0, b.size)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Little-endian reader. [u32] returns the raw bits as a signed Int. */
class ByteReader(val data: ByteArray, var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) {
            throw PtpFormatException("need $n bytes at offset $pos, only $remaining remaining")
        }
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val lo = u8()
        return lo or (u8() shl 8)
    }

    fun u32(): Int {
        need(4)
        val lo = u16()
        return lo or (u16() shl 16)
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** PTP string: u8 count of UTF-16LE chars including the trailing NUL; 0 means empty. */
    fun ptpString(): String {
        val count = u8()
        if (count == 0) return ""
        need(count * 2)
        val sb = StringBuilder()
        repeat(count) { sb.append(u16().toChar()) }
        if (sb.isNotEmpty() && sb.last() == '\u0000') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    fun u16Array(): List<Int> = readArray { u16() }

    fun u32Array(): List<Int> = readArray { u32() }

    private inline fun readArray(read: () -> Int): List<Int> {
        val n = u32()
        if (n < 0 || n > remaining) throw PtpFormatException("array length $n exceeds remaining $remaining")
        return List(n) { read() }
    }
}
