package dev.canonremote.ptp

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonEventsTest {
    private fun record(type: Int, vararg body: Int): ByteArray {
        val w = ByteWriter()
        w.u32(8 + body.size * 4)
        w.u32(type)
        body.forEach { w.u32(it) }
        return w.toByteArray()
    }

    private val terminator = byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0)
    private val iso = 0xD103

    private fun propChanged() = record(CanonEventCode.PROP_VALUE_CHANGED, iso, 0x58)

    @Test
    fun parsesPropChangedAndAvailableList() {
        val data = propChanged() +
            record(CanonEventCode.AVAILABLE_LIST_CHANGED, iso, 3, 3, 0x48, 0x50, 0x58) +
            terminator
        assertEquals(
            listOf(
                CanonEvent.PropValueChanged(iso, 0x58),
                CanonEvent.AvailableValuesChanged(iso, listOf(0x48, 0x50, 0x58)),
            ),
            parseCanonEvents(data),
        )
    }

    @Test
    fun skipsUnknownRecords() {
        val data = record(0xC1A7, 1, 2) + propChanged()
        assertEquals(
            listOf(CanonEvent.Unknown(0xC1A7, 16), CanonEvent.PropValueChanged(iso, 0x58)),
            parseCanonEvents(data),
        )
    }

    @Test
    fun emptyDataGivesEmptyList() {
        assertEquals(emptyList<CanonEvent>(), parseCanonEvents(ByteArray(0)))
        assertEquals(emptyList<CanonEvent>(), parseCanonEvents(terminator))
    }

    @Test
    fun truncatedRecordKeepsPreviousEvents() {
        val w = ByteWriter()
        w.u32(40)
        w.u32(CanonEventCode.PROP_VALUE_CHANGED)
        w.u32(iso)
        val data = propChanged() + w.toByteArray()
        val events = parseCanonEvents(data)
        assertEquals(2, events.size)
        assertEquals(CanonEvent.PropValueChanged(iso, 0x58), events[0])
        assertEquals(16, (events[1] as CanonEvent.Malformed).offset)
    }

    @Test
    fun sizeBelowHeaderIsMalformed() {
        val w = ByteWriter()
        w.u32(4)
        w.u32(CanonEventCode.PROP_VALUE_CHANGED)
        val events = parseCanonEvents(w.toByteArray())
        assertEquals(1, events.size)
        assertEquals(0, (events[0] as CanonEvent.Malformed).offset)
    }

    @Test
    fun availableListWithOverflowingCountIsMalformedAndParsingContinues() {
        val bad = record(CanonEventCode.AVAILABLE_LIST_CHANGED, iso, 3, 1000)
        assertEquals(20, bad.size)
        val events = parseCanonEvents(bad + propChanged())
        assertEquals(2, events.size)
        assertEquals(0, (events[0] as CanonEvent.Malformed).offset)
        assertEquals(CanonEvent.PropValueChanged(iso, 0x58), events[1])
    }

    @Test
    fun shortPropChangedBodyIsMalformedAndParsingContinues() {
        val short = record(CanonEventCode.PROP_VALUE_CHANGED, 0xD406) // no value: 12 bytes
        val events = parseCanonEvents(short + propChanged() + terminator)
        assertEquals(2, events.size)
        assertEquals(0, (events[0] as CanonEvent.Malformed).offset)
        assertEquals(CanonEvent.PropValueChanged(iso, 0x58), events[1])
    }

    @Test
    fun availableListWithOtherDataTypeIsSkippedAsUnknown() {
        val other = record(CanonEventCode.AVAILABLE_LIST_CHANGED, 0xD402, 1, 99)
        val events = parseCanonEvents(other + propChanged())
        assertEquals(
            listOf(CanonEvent.Unknown(CanonEventCode.AVAILABLE_LIST_CHANGED, 20), CanonEvent.PropValueChanged(iso, 0x58)),
            events,
        )
    }
}
