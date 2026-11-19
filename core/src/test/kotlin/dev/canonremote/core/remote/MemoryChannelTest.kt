package dev.canonremote.core.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryChannelTest {
    @Test fun deliversBothWaysAndRecords() = runBlocking {
        val (a, b) = MemoryChannel.pair()
        a.send(Message.Ping)
        assertEquals(Message.Ping, (b.incoming.first() as Incoming.Msg).message)
        b.send(Message.Pong)
        assertEquals(Message.Pong, (a.incoming.first() as Incoming.Msg).message)
        assertEquals(listOf<Message>(Message.Ping), a.sentMessages)
    }

    @Test fun cutDropsSilently() = runBlocking {
        val (a, b) = MemoryChannel.pair()
        a.cut()
        a.send(Message.Ping)
        b.send(Message.Pong)
        assertNull(withTimeoutOrNull(100) { b.incoming.first() })
        assertNull(withTimeoutOrNull(100) { a.incoming.first() })
        assertEquals(listOf<Message>(Message.Ping), a.sentMessages)
    }

    @Test fun failThrowsOnBothSides() = runBlocking {
        val (a, b) = MemoryChannel.pair()
        a.fail(java.io.IOException("boom"))
        for (c in listOf(a, b)) {
            try { withTimeout(1000) { c.incoming.toList() }; fail() } catch (e: java.io.IOException) { assertEquals("boom", e.message) }
        }
    }

    @Test fun closeCompletesBothSides() = runBlocking {
        val (a, b) = MemoryChannel.pair()
        a.sendJpeg(byteArrayOf(1))
        b.close()
        assertTrue(withTimeout(1000) { a.incoming.toList() }.isEmpty())
        assertEquals(1, withTimeout(1000) { b.incoming.toList() }.size)
    }
}
