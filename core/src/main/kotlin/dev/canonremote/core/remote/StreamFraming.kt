package dev.canonremote.core.remote

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Frame = type u8 (0 = JSON UTF-8, 1 = JPEG), length u32 LE, payload. */
object StreamFraming {
    const val TYPE_JSON = 0
    const val TYPE_JPEG = 1
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD) { "Payload too large: ${payload.size}" }
        val n = payload.size
        val header = byteArrayOf(
            type.toByte(),
            n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte(),
        )
        out.write(header)
        out.write(payload)
        out.flush()
    }

    /** Returns null on clean EOF (before any byte of a frame); throws [ProtocolException] otherwise. */
    fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val type = input.read()
        if (type < 0) return null
        val h = ByteArray(4)
        try { readFully(input, h) } catch (e: EOFException) { throw ProtocolException("Truncated frame header", e) }
        val len = (h[0].toLong() and 0xFF) or ((h[1].toLong() and 0xFF) shl 8) or
            ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 24)
        if (len > MAX_PAYLOAD) throw ProtocolException("Frame too large: $len")
        val payload = ByteArray(len.toInt())
        try { readFully(input, payload) } catch (e: EOFException) { throw ProtocolException("Truncated frame payload", e) }
        return type to payload
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }
}

/**
 * [MessageChannel] over a byte stream (Bluetooth RFCOMM in :app). A single reader runs on [io],
 * started lazily on first collect of [incoming]. Blocking reads are only interrupted by [close].
 *
 * [io] must allow at least 2 concurrent threads (e.g. Dispatchers.IO): the reader blocks one
 * while writes need another. A remote EOF or read error terminates [incoming] but does NOT call
 * [onClose]; the owner must call [close] when [incoming] terminates.
 */
class StreamMessageChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
    private val io: CoroutineDispatcher,
) : MessageChannel {
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)
    private val collected = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val items = Channel<Incoming>(Channel.UNLIMITED)

    // The single reader is started lazily on first collect and lives in its own scope, so that
    // cancelling the collector never waits on a blocking read; close() ends the reader.
    override val incoming: Flow<Incoming> = flow {
        check(collected.compareAndSet(false, true)) { "incoming can only be collected once" }
        scope.launch { readLoop() }
        emitAll(items.receiveAsFlow())
    }

    private fun readLoop() {
        try {
            while (true) {
                val frame = StreamFraming.readFrame(input) ?: break
                val item = when (frame.first) {
                    StreamFraming.TYPE_JSON ->
                        Incoming.Msg(MessageCodec.decode(String(frame.second, Charsets.UTF_8)))
                    StreamFraming.TYPE_JPEG -> Incoming.Jpeg(frame.second)
                    else -> throw ProtocolException("Unknown frame type ${frame.first}")
                }
                items.trySend(item)
            }
            items.close()
        } catch (e: Throwable) {
            // closed locally: complete normally rather than reporting the read failure
            items.close(if (closed.get() && e is IOException) null else e)
        }
    }

    override suspend fun send(message: Message) =
        write(StreamFraming.TYPE_JSON, MessageCodec.encode(message).toByteArray(Charsets.UTF_8))

    override suspend fun sendJpeg(bytes: ByteArray) = write(StreamFraming.TYPE_JPEG, bytes)

    private suspend fun write(type: Int, payload: ByteArray) {
        writeLock.withLock { withContext(io) { StreamFraming.writeFrame(output, type, payload) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        items.close()
        scope.cancel()
        onClose()
    }
}
