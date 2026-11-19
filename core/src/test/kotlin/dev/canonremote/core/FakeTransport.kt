package dev.canonremote.core

import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpContainer
import dev.canonremote.ptp.PtpResponse

/**
 * Test transport. [handler] is invoked once per COMMAND, lazily on the first receive()
 * after it was sent (so an optional DATA phase is already in [sent]); the returned
 * containers are queued and served by receive().
 */
class FakeTransport : Transport {
    override val name = "fake"
    val sent = mutableListOf<PtpContainer>()
    var handler: suspend (PtpContainer) -> List<PtpContainer> = { respond(it) }
    var failNextReceive: Throwable? = null
    var closed = false
        private set

    private val queue = ArrayDeque<PtpContainer>()
    private var pending: PtpContainer? = null

    override suspend fun send(bytes: ByteArray) {
        val c = PtpContainer.decode(bytes)
        sent += c
        if (c.type == ContainerType.COMMAND) pending = c
    }

    override suspend fun receive(): ByteArray {
        failNextReceive?.let { failNextReceive = null; throw it }
        if (queue.isEmpty()) {
            val cmd = pending ?: error("FakeTransport: receive() with nothing pending")
            pending = null
            queue.addAll(handler(cmd))
        }
        return queue.removeFirst().encode()
    }

    override fun close() {
        closed = true
    }

    fun respond(cmd: PtpContainer, code: Int = PtpResponse.OK, data: ByteArray? = null): List<PtpContainer> =
        buildList {
            if (data != null) add(PtpContainer(ContainerType.DATA, cmd.code, cmd.transactionId, payload = data))
            add(PtpContainer(ContainerType.RESPONSE, code, cmd.transactionId))
        }
}
