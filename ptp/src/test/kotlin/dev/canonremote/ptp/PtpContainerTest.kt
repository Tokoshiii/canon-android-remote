package dev.canonremote.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PtpContainerTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesOpenSessionCommand() {
        val encoded = PtpContainer(ContainerType.COMMAND, 0x1002, 0, listOf(1)).encode()
        assertArrayEquals(
            bytes(0x10, 0, 0, 0, 0x01, 0, 0x02, 0x10, 0, 0, 0, 0, 0x01, 0, 0, 0),
            encoded,
        )
    }

    @Test
    fun decodesOkResponse() {
        val c = PtpContainer.decode(bytes(0x0C, 0, 0, 0, 0x03, 0, 0x01, 0x20, 0x05, 0, 0, 0))
        assertEquals(ContainerType.RESPONSE, c.type)
        assertEquals(0x2001, c.code)
        assertEquals(5, c.transactionId)
        assertTrue(c.params.isEmpty())
    }

    @Test
    fun roundTripsDataContainer() {
        val original = PtpContainer(
            ContainerType.DATA, 0x9110, 7,
            payload = bytes(0x0C, 0, 0, 0, 0x03, 0xD1, 0, 0, 0x48, 0, 0, 0),
        )
        val encoded = original.encode()
        assertEquals(24, encoded.size)
        assertEquals(original, PtpContainer.decode(encoded))
    }

    @Test
    fun decodeRejectsTruncatedHeader() {
        try {
            PtpContainer.decode(ByteArray(6))
            fail("expected PtpFormatException")
        } catch (_: PtpFormatException) {
        }
    }

    @Test
    fun decodeRejectsLengthMismatch() {
        try {
            PtpContainer.decode(bytes(0x14, 0, 0, 0, 0x03, 0, 0x01, 0x20, 0x05, 0, 0, 0))
            fail("expected PtpFormatException")
        } catch (_: PtpFormatException) {
        }
    }

    @Test
    fun readerReadsPtpString() {
        assertEquals("Hi", ByteReader(bytes(3, 'H'.code, 0, 'i'.code, 0, 0, 0)).ptpString())
        assertEquals("", ByteReader(bytes(0)).ptpString())
    }
}
