package dev.canonremote.ptp

sealed interface CanonEvent {
    data class PropValueChanged(val prop: Int, val value: Int) : CanonEvent
    data class AvailableValuesChanged(val prop: Int, val values: List<Int>) : CanonEvent
    data class Unknown(val type: Int, val size: Int) : CanonEvent
    data class Malformed(val offset: Int, val reason: String) : CanonEvent
}

private const val RECORD_HEADER = 8

private const val DATA_TYPE_U32_LIST = 3

/**
 * Parses a Canon GetEvent payload: records of `size u32, type u32, body (size-8)`.
 * Ends at a type-0 record or end of data. Never throws. A record whose size is invalid
 * (< 8 or beyond the data) yields a [CanonEvent.Malformed] and stops parsing; a record whose
 * size is valid but whose body cannot be decoded yields a [CanonEvent.Malformed] (offset =
 * start of that record) and parsing continues with the next record.
 */
fun parseCanonEvents(data: ByteArray): List<CanonEvent> {
    val events = mutableListOf<CanonEvent>()
    var offset = 0
    while (data.size - offset >= RECORD_HEADER) {
        val header = ByteReader(data, offset)
        val size = header.u32()
        val type = header.u32()
        if (type == 0) break
        if (size < RECORD_HEADER || size > data.size - offset) {
            events += CanonEvent.Malformed(offset, "invalid record size $size (${data.size - offset} bytes left)")
            return events
        }
        val body = ByteReader(data.copyOfRange(offset + RECORD_HEADER, offset + size))
        events += try {
            decodeRecord(type, size, body)
        } catch (e: PtpFormatException) {
            CanonEvent.Malformed(offset, e.message ?: "malformed record")
        }
        offset += size
    }
    if (data.size - offset in 1 until RECORD_HEADER) {
        events += CanonEvent.Malformed(offset, "trailing ${data.size - offset} bytes shorter than a header")
    }
    return events
}

/** True when a GetEvent payload holds at least one record (not just the 8-byte terminator). */
fun hasCanonEventRecords(data: ByteArray?): Boolean =
    data != null && data.size >= RECORD_HEADER && ByteReader(data, 4).u32() != 0

private fun decodeRecord(type: Int, size: Int, body: ByteReader): CanonEvent = when (type) {
    CanonEventCode.PROP_VALUE_CHANGED -> {
        val prop = body.u32()
        if (body.remaining < 4) throw PtpFormatException("property 0x%04X: no 4-byte value".format(prop))
        CanonEvent.PropValueChanged(prop, body.u32())
    }
    CanonEventCode.AVAILABLE_LIST_CHANGED -> {
        val prop = body.u32()
        val dataType = body.u32()
        if (dataType != DATA_TYPE_U32_LIST) {
            CanonEvent.Unknown(type, size)
        } else {
            val count = body.u32()
            if (count < 0 || count > body.remaining / 4) {
                throw PtpFormatException("count $count does not fit in record body")
            }
            CanonEvent.AvailableValuesChanged(prop, List(count) { body.u32() })
        }
    }
    else -> CanonEvent.Unknown(type, size)
}
