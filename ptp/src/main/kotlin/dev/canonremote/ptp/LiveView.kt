package dev.canonremote.ptp

private const val BLOCK_HEADER_SIZE = 8
private const val BLOCK_TYPE_JPEG = 1L
private const val SOI_PREFIX = 0xFF
private const val SOI = 0xD8
private const val EOI = 0xD9

/**
 * Extracts the JPEG frame from a live view payload.
 *
 * The payload is a sequence of blocks: size u32 (header included), type u32, data (size - 8 bytes).
 * Returns the data of the first type-1 block starting with FF D8. If the block structure is invalid
 * or no such block exists, falls back to the first FF D8 through the last FF D9 after it (inclusive).
 * Returns null if neither finds a JPEG. Never throws.
 */
fun extractLiveViewJpeg(data: ByteArray): ByteArray? =
    findJpegBlock(data) ?: markerScan(data)

/** Returns the payload of the first type-1 block starting with FF D8; null if none or structure invalid. */
private fun findJpegBlock(data: ByteArray): ByteArray? {
    var pos = 0
    while (pos < data.size) {
        if (data.size - pos < BLOCK_HEADER_SIZE) return null
        val size = readU32(data, pos)
        val type = readU32(data, pos + 4)
        if (size < BLOCK_HEADER_SIZE || size > (data.size - pos).toLong()) return null
        val end = pos + size.toInt()
        if (type == BLOCK_TYPE_JPEG && startsWithSoi(data, pos + BLOCK_HEADER_SIZE, end)) {
            return data.copyOfRange(pos + BLOCK_HEADER_SIZE, end)
        }
        pos = end
    }
    return null
}

/** Returns the bytes from the first FF D8 through the last FF D9 after it, inclusive; null if absent. */
private fun markerScan(data: ByteArray): ByteArray? {
    val start = (0 until data.size - 1).firstOrNull { isMarker(data, it, SOI) } ?: return null
    val end = (data.size - 2 downTo start + 2).firstOrNull { isMarker(data, it, EOI) } ?: return null
    return data.copyOfRange(start, end + 2)
}

private fun startsWithSoi(data: ByteArray, from: Int, end: Int): Boolean =
    end - from >= 2 && isMarker(data, from, SOI)

private fun isMarker(data: ByteArray, at: Int, code: Int): Boolean =
    unsignedByte(data, at) == SOI_PREFIX && unsignedByte(data, at + 1) == code

private fun unsignedByte(data: ByteArray, at: Int): Int = data[at].toInt() and 0xFF

/** Little-endian u32 as an unsigned Long. Caller guarantees 4 bytes are available. */
private fun readU32(data: ByteArray, at: Int): Long =
    (unsignedByte(data, at).toLong()) or
        (unsignedByte(data, at + 1).toLong() shl 8) or
        (unsignedByte(data, at + 2).toLong() shl 16) or
        (unsignedByte(data, at + 3).toLong() shl 24)
