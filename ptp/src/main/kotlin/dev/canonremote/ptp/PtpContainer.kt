package dev.canonremote.ptp

enum class ContainerType(val code: Int) {
    COMMAND(1), DATA(2), RESPONSE(3), EVENT(4);

    companion object {
        fun fromCode(code: Int): ContainerType =
            entries.firstOrNull { it.code == code } ?: throw PtpFormatException("unknown container type $code")
    }
}

/**
 * A PTP USB container. COMMAND/RESPONSE/EVENT carry [params] (0..5 u32);
 * DATA carries a raw [payload].
 */
data class PtpContainer(
    val type: ContainerType,
    val code: Int,
    val transactionId: Int,
    val params: List<Int> = emptyList(),
    val payload: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        val body = ByteWriter()
        if (type == ContainerType.DATA) body.bytes(payload) else params.forEach(body::u32)
        val bodyBytes = body.toByteArray()
        return ByteWriter().apply {
            u32(HEADER_SIZE + bodyBytes.size)
            u16(type.code)
            u16(code)
            u32(transactionId)
            bytes(bodyBytes)
        }.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is PtpContainer && type == other.type && code == other.code &&
            transactionId == other.transactionId && params == other.params &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var h = type.hashCode()
        h = 31 * h + code
        h = 31 * h + transactionId
        h = 31 * h + params.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        const val HEADER_SIZE = 12

        fun decode(bytes: ByteArray): PtpContainer {
            if (bytes.size < HEADER_SIZE) throw PtpFormatException("container too short: ${bytes.size} bytes")
            val r = ByteReader(bytes)
            val length = r.u32()
            if (length != bytes.size) throw PtpFormatException("length ${length.toUInt()} != received ${bytes.size}")
            val type = ContainerType.fromCode(r.u16())
            val code = r.u16()
            val tid = r.u32()
            if (type == ContainerType.DATA) {
                return PtpContainer(type, code, tid, payload = r.bytes(r.remaining))
            }
            if (r.remaining % 4 != 0 || r.remaining / 4 > 5) {
                throw PtpFormatException("invalid parameter block of ${r.remaining} bytes")
            }
            return PtpContainer(type, code, tid, params = List(r.remaining / 4) { r.u32() })
        }
    }
}
