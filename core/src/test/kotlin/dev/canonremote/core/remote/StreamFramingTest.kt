package dev.canonremote.core.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamFramingTest {
    @Test fun frameRoundTrip() {
        val out = ByteArrayOutputStream()
        StreamFraming.writeFrame(out, StreamFraming.TYPE_JSON, "hé".toByteArray())
        StreamFraming.writeFrame(out, StreamFraming.TYPE_JPEG, byteArrayOf(1, 2, 3))
        val bytes = out.toByteArray()
        assertEquals(0, bytes[0].toInt())
        assertEquals(3, bytes[1].toInt()) // "hé" = 3 bytes, length little endian
        val input = ByteArrayInputStream(bytes)
        val a = StreamFraming.readFrame(input)!!
        assertEquals(0, a.first); assertEquals("hé", String(a.second))
        val b = StreamFraming.readFrame(input)!!
        assertEquals(1, b.first); assertArrayEquals(byteArrayOf(1, 2, 3), b.second)
        assertNull(StreamFraming.readFrame(input))
    }

    @Test fun emptyPayloadAllowed() {
        val out = ByteArrayOutputStream()
        StreamFraming.writeFrame(out, 0, ByteArray(0))
        val f = StreamFraming.readFrame(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(0, f.second.size)
    }

    @Test fun eofIsNull() {
        assertNull(StreamFraming.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun oversizedRejected() {
        val len = StreamFraming.MAX_PAYLOAD + 1
        val bytes = byteArrayOf(0, len.toByte(), (len shr 8).toByte(), (len shr 16).toByte(), (len shr 24).toByte())
        try { StreamFraming.readFrame(ByteArrayInputStream(bytes)); fail() } catch (_: ProtocolException) {}
        // length with high bit set (>= 2^31) must also be rejected, not treated as negative
        val huge = byteArrayOf(0, -1, -1, -1, -1)
        try { StreamFraming.readFrame(ByteArrayInputStream(huge)); fail() } catch (_: ProtocolException) {}
    }

    @Test fun truncatedRejected() {
        try { StreamFraming.readFrame(ByteArrayInputStream(byteArrayOf(0, 5))); fail() } catch (_: ProtocolException) {}
        try { StreamFraming.readFrame(ByteArrayInputStream(byteArrayOf(0, 5, 0, 0, 0, 1, 2))); fail() } catch (_: ProtocolException) {}
    }

    @Test fun unknownTypeRejectedByChannel() = runBlocking {
        val out = ByteArrayOutputStream()
        StreamFraming.writeFrame(out, 7, byteArrayOf(1))
        val ch = StreamMessageChannel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), {}, Dispatchers.IO)
        try { ch.incoming.toList(); fail() } catch (_: ProtocolException) {}
    }

    @Test fun cleanEofCompletesIncoming() = runBlocking {
        val ch = StreamMessageChannel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), {}, Dispatchers.IO)
        assertTrue(ch.incoming.toList().isEmpty())
    }

    @Test fun pairOverPipedStreamsExchangesMessageAndJpeg() = runBlocking {
        val aToB = PipedOutputStream(); val bIn = PipedInputStream(aToB, 1 shl 16)
        val bToA = PipedOutputStream(); val aIn = PipedInputStream(bToA, 1 shl 16)
        var closed = 0
        val a = StreamMessageChannel(aIn, aToB, { closed++ }, Dispatchers.IO)
        val b = StreamMessageChannel(bIn, bToA, {}, Dispatchers.IO)
        withTimeout(5000) {
            a.send(Message.Ping)
            a.sendJpeg(byteArrayOf(9, 8, 7))
            val items = b.incoming.let { f ->
                val got = mutableListOf<Incoming>()
                f.first { got += it; got.size == 2 }
                got
            }
            assertEquals(Message.Ping, (items[0] as Incoming.Msg).message)
            assertArrayEquals(byteArrayOf(9, 8, 7), (items[1] as Incoming.Jpeg).bytes)
            b.send(Message.Pong)
            assertEquals(Message.Pong, (a.incoming.first() as Incoming.Msg).message)
        }
        a.close(); a.close()
        assertEquals(1, closed)
    }
}
