package dev.canonremote.core

interface Transport {
    val name: String

    suspend fun send(bytes: ByteArray)

    /** Returns exactly one complete PTP container. */
    suspend fun receive(): ByteArray

    fun close()
}
