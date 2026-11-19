package dev.canonremote.ptp

sealed interface CanonEvent {
    data class PropValueChanged(val prop: Int, val value: Int) : CanonEvent
    data class AvailableValuesChanged(val prop: Int, val values: List<Int>) : CanonEvent
    data class Unknown(val type: Int, val size: Int) : CanonEvent
    data class Malformed(val offset: Int, val reason: String) : CanonEvent
}

private const val RECORD_HEADER = 8

/**
 * Parses a Canon GetEvent payload: records of `size u32, type u32, body (size-8)`.
 * Ends at a type-0 record or end of data. Never throws: a bad record yields a
 * [CanonEvent.Malformed] (offset = start of that record) and parsing stops.
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
        try {
            events += when (type) {
                CanonEventCode.PROP_VALUE_CHANGED -> CanonEvent.PropValueChanged(body.u32(), body.u32())
                CanonEventCode.AVAILABLE_LIST_CHANGED -> {
                    val prop = body.u32()
                    body.u32() // data type
                    val count = body.u32()
                    if (count < 0 || count > body.remaining / 4) {
                        throw PtpFormatException("count $count does not fit in record body")
                    }
                    CanonEvent.AvailableValuesChanged(prop, List(count) { body.u32() })
                }
                else -> CanonEvent.Unknown(type, size)
            }
        } catch (e: PtpFormatException) {
            events += CanonEvent.Malformed(offset, e.message ?: "malformed record")
            return events
        }
        offset += size
    }
    if (data.size - offset in 1 until RECORD_HEADER) {
        events += CanonEvent.Malformed(offset, "trailing ${data.size - offset} bytes shorter than a header")
    }
    return events
}
