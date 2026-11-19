package dev.canonremote.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonValuesTest {
    @Test
    fun isoLabels() {
        assertEquals("400", CanonValues.label(CanonProp.ISO, 0x58))
        assertEquals("Auto", CanonValues.label(CanonProp.ISO, 0x00))
    }

    @Test
    fun shutterLabels() {
        assertEquals("1/250", CanonValues.label(CanonProp.SHUTTER_SPEED, 0x78))
        assertEquals("30\"", CanonValues.label(CanonProp.SHUTTER_SPEED, 0x10))
        assertEquals("Bulb", CanonValues.label(CanonProp.SHUTTER_SPEED, 0x0C))
    }

    @Test
    fun apertureLabels() {
        assertEquals("f/5.6", CanonValues.label(CanonProp.APERTURE, 0x30))
        assertEquals("f/2.8", CanonValues.label(CanonProp.APERTURE, 0x20))
    }

    @Test
    fun whiteBalanceLabels() {
        assertEquals("Auto", CanonValues.label(CanonProp.WHITE_BALANCE, 0))
        assertEquals("Ombre", CanonValues.label(CanonProp.WHITE_BALANCE, 8))
    }

    @Test
    fun unknownValueIsHex() {
        assertEquals("0x41", CanonValues.label(CanonProp.ISO, 0x41))
        assertEquals("0x03", CanonValues.label(0xD1FF, 3))
    }

    @Test
    fun setPropPayloadLayout() {
        val expected = byteArrayOf(
            0x0C, 0x00, 0x00, 0x00,
            0x03, 0xD1.toByte(), 0x00, 0x00,
            0x48, 0x00, 0x00, 0x00,
        )
        assertArrayEquals(expected, CanonValues.setPropPayload(CanonProp.ISO, 0x48))
    }
}
