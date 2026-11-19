package dev.canonremote.core

import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpContainer
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PtpSessionTest {
    private val t = FakeTransport()
    private val log = LogBus()
    private val s = PtpSession(t, log)

    @Test fun sendsCommandAndReadsResponse() = runBlocking {
        val r = s.execute(PtpOp.OPEN_SESSION, listOf(1))
        assertEquals(PtpContainer(ContainerType.COMMAND, 0x1002, 0, listOf(1)), t.sent[0])
        assertTrue(r.isOk)
    }

    @Test fun transactionIdsIncrement() = runBlocking {
        s.execute(PtpOp.OPEN_SESSION, listOf(1))
        s.execute(0x9116)
        s.execute(0x9116)
        assertEquals(listOf(0, 1, 2), t.sent.map { it.transactionId })
    }

    @Test fun readsDataPhase() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        t.handler = { t.respond(it, data = payload) }
        val r = s.execute(PtpOp.GET_DEVICE_INFO)
        assertArrayEquals(payload, r.data)
        assertTrue(r.isOk)
    }

    @Test fun sendsDataPhaseAfterCommand() = runBlocking {
        s.execute(0x9110, listOf(1), dataOut = byteArrayOf(9, 8))
        assertEquals(listOf(ContainerType.COMMAND, ContainerType.DATA), t.sent.map { it.type })
        assertEquals(t.sent[0].transactionId, t.sent[1].transactionId)
        assertEquals(t.sent[0].code, t.sent[1].code)
        assertArrayEquals(byteArrayOf(9, 8), t.sent[1].payload)
    }

    @Test fun executeOkThrowsOnBusy() = runBlocking {
        t.handler = { t.respond(it, code = PtpResponse.DEVICE_BUSY) }
        try {
            s.executeOk(0x9116)
            fail("expected PtpException")
        } catch (e: PtpException) {
            assertEquals(0x2019, e.responseCode)
        }
    }

    @Test fun skipsInterleavedEvent() = runBlocking {
        t.handler = {
            listOf(PtpContainer(ContainerType.EVENT, 0xC189, 0)) + t.respond(it, data = byteArrayOf(5))
        }
        val r = s.execute(0x9116)
        assertTrue(r.isOk)
        assertArrayEquals(byteArrayOf(5), r.data)
    }

    @Test fun concurrentCallsAreSerialized() = runBlocking {
        t.handler = { delay(20); t.respond(it) }
        listOf(async { s.execute(0x9116) }, async { s.execute(0x9117) }).awaitAll()
        assertEquals(2, t.sent.size)
        assertEquals(listOf(1, 2), t.sent.map { it.transactionId }.sorted())
        assertTrue(t.sent.all { it.type == ContainerType.COMMAND })
    }

    @Test fun logsEachPacket() = runBlocking {
        t.handler = { t.respond(it, data = byteArrayOf(1)) }
        s.execute(0x9116, listOf(1))
        val ptp = log.entries.value.filter { it.level == LogLevel.PTP }.map { it.message }
        assertTrue(ptp.any { it.startsWith("→") })
        assertTrue(ptp.any { it.startsWith("← DATA 1 o: 01") })
        assertTrue(ptp.any { it == "← 0x2001" })
    }

    @Test fun logDataFalseOmitsHex() = runBlocking {
        t.handler = { t.respond(it, data = byteArrayOf(1)) }
        s.execute(0x9116, logData = false)
        assertTrue(log.entries.value.any { it.message == "← DATA 1 o" })
    }
}
