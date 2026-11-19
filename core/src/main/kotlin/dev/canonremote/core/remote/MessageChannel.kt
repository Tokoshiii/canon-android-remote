package dev.canonremote.core.remote

import kotlinx.coroutines.flow.Flow

sealed interface Incoming {
    data class Msg(val message: Message) : Incoming
    class Jpeg(val bytes: ByteArray) : Incoming
}

/**
 * A bidirectional link carrying [Message]s and raw JPEG frames.
 */
interface MessageChannel {
    /**
     * Emits each received item. Completes normally on clean close (EOF) and throws on
     * read/decoding errors. Must be collected at most once.
     */
    val incoming: Flow<Incoming>

    suspend fun send(message: Message)
    suspend fun sendJpeg(bytes: ByteArray)
    fun close()
}
