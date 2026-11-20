package dev.canonremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureModeTest {
    @Test fun exposureModeMapping() {
        assertEquals(ExposureMode.P, exposureModeOf(0))
        assertEquals(ExposureMode.TV, exposureModeOf(1))
        assertEquals(ExposureMode.AV, exposureModeOf(2))
        assertEquals(ExposureMode.M, exposureModeOf(3))
        assertEquals(ExposureMode.BULB, exposureModeOf(4))
        for (c in listOf(7, 0x10, 0x11)) assertEquals(ExposureMode.CUSTOM, exposureModeOf(c))
        for (c in listOf(8, 9, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19)) {
            assertEquals("0x%X".format(c), ExposureMode.SCENE, exposureModeOf(c))
        }
        assertEquals(ExposureMode.UNKNOWN, exposureModeOf(null))
        assertEquals(ExposureMode.UNKNOWN, exposureModeOf(0x42))
        assertEquals(ExposureMode.UNKNOWN, exposureModeOf(5))
    }

    @Test fun labels() {
        assertEquals("P", modeLabel(0))
        assertEquals("Tv", modeLabel(1))
        assertEquals("Av", modeLabel(2))
        assertEquals("M", modeLabel(3))
        assertEquals("B", modeLabel(4))
        assertEquals("C1", modeLabel(7))
        assertEquals("C2", modeLabel(0x10))
        assertEquals("C3", modeLabel(0x11))
        assertEquals("A+", modeLabel(9))
        assertEquals("A+", modeLabel(0x16))
        assertEquals("Scène", modeLabel(0x0A))
        assertNull(modeLabel(null))
        assertEquals("0x42", modeLabel(0x42))
    }

    @Test fun locks() {
        assertEquals(ExposureLocks(shutter = true, aperture = true, iso = false), locksFor(ExposureMode.P))
        assertEquals(ExposureLocks(shutter = false, aperture = true, iso = false), locksFor(ExposureMode.TV))
        assertEquals(ExposureLocks(shutter = true, aperture = false, iso = false), locksFor(ExposureMode.AV))
        assertEquals(ExposureLocks(shutter = false, aperture = false, iso = false), locksFor(ExposureMode.M))
        assertEquals(ExposureLocks(shutter = true, aperture = false, iso = false), locksFor(ExposureMode.BULB))
        assertEquals(ExposureLocks(shutter = true, aperture = true, iso = true), locksFor(ExposureMode.SCENE))
        assertEquals(ExposureLocks(shutter = false, aperture = false, iso = false), locksFor(ExposureMode.CUSTOM))
        assertEquals(ExposureLocks(shutter = false, aperture = false, iso = false), locksFor(ExposureMode.UNKNOWN))
    }

    @Test fun manualFocus() {
        assertTrue(isManualFocus(3))
        assertFalse(isManualFocus(0))
        assertFalse(isManualFocus(null))
    }
}
