package dev.canonremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date

class LogBusTest {
    @Test
    fun keepsOnlyLastCapacityEntries() {
        val bus = LogBus(capacity = 3)
        (1..5).forEach { bus.info("m$it") }
        assertEquals(listOf("m3", "m4", "m5"), bus.entries.value.map { it.message })
    }

    @Test
    fun exportFormatsLines() {
        val bus = LogBus(clock = { 1_000_000L })
        bus.info("bonjour")
        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(1_000_000L))
        val lines = bus.export("HEADER").split("\n")
        assertEquals("HEADER", lines[0])
        assertEquals("", lines[1])
        assertEquals("$time INFO bonjour", lines[2])
    }

    @Test
    fun errorIncludesThrowable() {
        val bus = LogBus()
        bus.error("boum", IllegalStateException("oups"))
        assertEquals("boum: IllegalStateException: oups", bus.entries.value.single().message)
        assertEquals(LogLevel.ERROR, bus.entries.value.single().level)
    }

    @Test
    fun clearEmpties() {
        val bus = LogBus()
        bus.warn("a"); bus.ptp("b")
        bus.clear()
        assertTrue(bus.entries.value.isEmpty())
    }

    @Test
    fun hexDumpTruncates() {
        val s = ByteArray(70).toHexDump(64)
        assertTrue(s, s.endsWith("(+6 octets)"))
        assertEquals("01 ff", byteArrayOf(1, -1).toHexDump())
    }

    @Test
    fun exportKeepsHeaderAndLastLinesWhenTooLong() {
        val bus = LogBus()
        (1..100).forEach { bus.info("ligne %03d".format(it)) }
        val full = bus.export("HEADER")
        val cut = bus.export("HEADER", maxChars = 200)
        assertTrue(cut.length <= 200)
        val lines = cut.trimEnd('\n').split("\n")
        assertEquals("HEADER", lines[0])
        assertEquals("", lines[1])
        assertEquals("… (début tronqué)", lines[2])
        assertTrue(lines.last().endsWith("ligne 100"))
        assertTrue(full.endsWith(cut.substringAfter("… (début tronqué)\n")))
        assertEquals(full, bus.export("HEADER", maxChars = full.length))
    }
}
