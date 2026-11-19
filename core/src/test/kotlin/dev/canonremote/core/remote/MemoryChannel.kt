package dev.canonremote.core.remote

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Test-only in-memory [MessageChannel] pair. */
class MemoryChannel private constructor(
    private val inbox: Channel<Incoming>,
    private val cutFlag: AtomicBoolean,
) : MessageChannel {
    lateinit var peer: MemoryChannel
        private set

    val sentMessages: MutableList<Message> = CopyOnWriteArrayList()
    val sentJpegs: MutableList<ByteArray> = CopyOnWriteArrayList()

    override val incoming: Flow<Incoming> = inbox.receiveAsFlow()

    override suspend fun send(message: Message) {
        sentMessages += message
        if (!cutFlag.get()) peer.inbox.trySend(Incoming.Msg(message))
    }

    override suspend fun sendJpeg(bytes: ByteArray) {
        sentJpegs += bytes
        if (!cutFlag.get()) peer.inbox.trySend(Incoming.Jpeg(bytes))
    }

    /** From now on, items sent in both directions are silently dropped. */
    fun cut() { cutFlag.set(true) }

    /** Both incoming flows throw [e] (after any buffered items). */
    fun fail(e: Throwable) { inbox.close(e); peer.inbox.close(e) }

    /** Both incoming flows complete normally. */
    override fun close() { inbox.close(); peer.inbox.close() }

    companion object {
        fun pair(): Pair<MemoryChannel, MemoryChannel> {
            val cut = AtomicBoolean(false)
            val a = MemoryChannel(Channel(Channel.UNLIMITED), cut)
            val b = MemoryChannel(Channel(Channel.UNLIMITED), cut)
            a.peer = b; b.peer = a
            return a to b
        }
    }
}
