package dev.canonremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsModelTest {
    private fun connected(
        shootingMode: Int? = null,
        focusMode: Int? = null,
        settings: Map<CameraSetting, SettingState> = emptyMap(),
        liveView: Boolean = false,
    ) = CameraState(
        status = ConnectionStatus.CONNECTED,
        settings = settings,
        shootingMode = shootingMode,
        focusMode = focusMode,
        liveView = liveView,
    )

    private fun ControlsModel.slider(s: CameraSetting) = sliders.first { it.setting == s }

    private val fullSettings = mapOf(
        CameraSetting.SHUTTER to SettingState(current = 0x68, available = listOf(0x65, 0x68, 0x6B, 0x70)),
        CameraSetting.APERTURE to SettingState(current = 0x20, available = listOf(0x20, 0x23, 0x28)),
        CameraSetting.ISO to SettingState(current = 0x58, available = listOf(0x00, 0x48, 0x58)),
    )

    @Test
    fun disconnectedShowsGreyedControls() {
        val model = CameraState().toControls()

        assertEquals("Déconnecté", model.statusLabel)
        assertFalse(model.connected)
        assertNull(model.modeLabel)
        assertEquals(listOf("Vitesse", "Ouverture", "ISO"), model.sliders.map { it.title })
        model.sliders.forEach { s ->
            assertFalse(s.enabled)
            assertEquals("—", s.valueLabel)
            assertTrue(s.options.isEmpty())
            assertEquals(-1, s.index)
            assertFalse(s.autoSelected)
        }
        assertFalse(model.whiteBalance.enabled)
        assertEquals("—", model.whiteBalance.valueLabel)
        assertNull(model.whiteBalance.selected)
        assertFalse(model.shutterEnabled)
        assertFalse(model.focus.afEnabled)
        assertFalse(model.focus.manualStepsEnabled)
        assertNull(model.focus.lensLabel)
        assertFalse(model.liveViewToggleEnabled)
    }

    @Test
    fun connectedSlidersInManualMode() {
        val model = connected(shootingMode = 3, settings = fullSettings).toControls()
        val iso = model.slider(CameraSetting.ISO)
        val shutter = model.slider(CameraSetting.SHUTTER)

        assertEquals("M", model.modeLabel)
        assertEquals("400", iso.valueLabel)
        assertEquals(2, iso.index)
        assertEquals(listOf(0x00 to "Auto", 0x48 to "100", 0x58 to "400"), iso.options)
        assertEquals(setOf(0, 1, 2), iso.majorIndices)
        assertTrue(iso.enabled)
        assertFalse(iso.locked)
        assertFalse(iso.autoSelected)
        assertEquals("1/60", shutter.valueLabel)
        assertEquals(1, shutter.index)
        assertEquals(setOf(1, 3), shutter.majorIndices)
        model.sliders.forEach { assertTrue(it.enabled && !it.locked && it.lockHint == null) }
    }

    @Test
    fun sliderOrderIsShutterApertureIso() {
        assertEquals(
            listOf(CameraSetting.SHUTTER, CameraSetting.APERTURE, CameraSetting.ISO),
            connected().toControls().sliders.map { it.setting },
        )
    }

    @Test
    fun unknownCurrentGivesIndexMinusOneAndDash() {
        val s = connected(
            settings = mapOf(CameraSetting.SHUTTER to SettingState(null, listOf(0x68))),
        ).toControls().slider(CameraSetting.SHUTTER)
        assertEquals(-1, s.index)
        assertEquals("—", s.valueLabel)
        assertTrue(s.enabled)
    }

    @Test
    fun sliderWithoutAvailableValuesDisabled() {
        val s = connected(shootingMode = 3).toControls().slider(CameraSetting.SHUTTER)
        assertFalse(s.enabled)
        assertTrue(s.options.isEmpty())
        assertEquals("—", s.valueLabel)
    }

    @Test
    fun isoAutoSelected() {
        val settings = mapOf(CameraSetting.ISO to SettingState(0x00, listOf(0x00, 0x48)))
        val iso = connected(shootingMode = 3, settings = settings).toControls().slider(CameraSetting.ISO)
        assertTrue(iso.autoSelected)
        assertEquals("Auto", iso.valueLabel)
        assertEquals(0, iso.index)
        assertTrue(iso.enabled)
    }

    @Test
    fun avLocksShutter() {
        val m = connected(shootingMode = 2, settings = fullSettings).toControls()
        val shutter = m.slider(CameraSetting.SHUTTER)
        assertTrue(shutter.locked)
        assertFalse(shutter.enabled)
        assertEquals("Molette du boîtier sur M ou Tv pour régler", shutter.lockHint)
        assertEquals("1/60", shutter.valueLabel)
        assertFalse(m.slider(CameraSetting.APERTURE).locked)
        assertEquals("Av", m.modeLabel)
    }

    @Test
    fun tvLocksAperture() {
        val a = connected(shootingMode = 1, settings = fullSettings).toControls().slider(CameraSetting.APERTURE)
        assertTrue(a.locked)
        assertFalse(a.enabled)
        assertEquals("Molette du boîtier sur M ou Av pour régler", a.lockHint)
    }

    @Test
    fun programLocksShutterAndAperture() {
        val m = connected(shootingMode = 0, settings = fullSettings).toControls()
        assertTrue(m.slider(CameraSetting.SHUTTER).locked)
        assertTrue(m.slider(CameraSetting.APERTURE).locked)
        assertFalse(m.slider(CameraSetting.ISO).locked)
    }

    @Test
    fun sceneLocksAllWithIsoHint() {
        val m = connected(shootingMode = 0x0C, settings = fullSettings).toControls()
        m.sliders.forEach {
            assertTrue(it.locked)
            assertFalse(it.enabled)
        }
        assertEquals("Mode scène : ISO automatique", m.slider(CameraSetting.ISO).lockHint)
        assertEquals("Scène", m.modeLabel)
    }

    @Test
    fun bulbShowsLockedBulbShutter() {
        val m = connected(shootingMode = 4, settings = fullSettings).toControls()
        val shutter = m.slider(CameraSetting.SHUTTER)
        assertEquals("Bulb", shutter.valueLabel)
        assertTrue(shutter.locked)
        assertFalse(shutter.enabled)
        assertEquals("B", m.modeLabel)
        assertFalse(m.slider(CameraSetting.APERTURE).locked)
    }

    @Test
    fun customAndUnknownModeLockNothing() {
        listOf(7, null).forEach { code ->
            connected(shootingMode = code, settings = fullSettings).toControls().sliders.forEach {
                assertFalse(it.locked)
            }
        }
        assertNull(connected().toControls().modeLabel)
    }

    @Test
    fun whiteBalanceChoice() {
        val wb = connected(
            settings = mapOf(CameraSetting.WHITE_BALANCE to SettingState(8, listOf(0, 8))),
        ).toControls().whiteBalance
        assertEquals("Ombre", wb.valueLabel)
        assertTrue(wb.enabled)
        assertEquals(listOf(0 to "Auto", 8 to "Ombre"), wb.options)
        assertEquals(8, wb.selected)
    }

    @Test
    fun connectedStatusLabels() {
        assertEquals(
            "Canon EOS 90D",
            CameraState(status = ConnectionStatus.CONNECTED, model = "Canon EOS 90D").toControls().statusLabel,
        )
        assertEquals("Connexion…", CameraState(status = ConnectionStatus.CONNECTING).toControls().statusLabel)
        assertEquals("Connecté", CameraState(status = ConnectionStatus.CONNECTED).toControls().statusLabel)
    }

    @Test
    fun busyDisablesShutter() {
        assertFalse(connected().copy(busy = true).toControls().shutterEnabled)
        assertTrue(connected().toControls().shutterEnabled)
        assertTrue(connected().toControls().liveViewToggleEnabled)
    }

    @Test
    fun focusAfAndManualSteps() {
        val af = connected(focusMode = 0).toControls().focus
        assertTrue(af.afEnabled)
        assertFalse(af.manualStepsEnabled)
        assertEquals("AF", af.lensLabel)
        assertNull(af.afHint)

        val live = connected(focusMode = 0, liveView = true).toControls()
        assertTrue(live.focus.manualStepsEnabled)
        assertTrue(live.liveViewOn)
    }

    @Test
    fun focusMfDisablesAf() {
        val f = connected(focusMode = 3, liveView = true).toControls().focus
        assertFalse(f.afEnabled)
        assertTrue(f.manualStepsEnabled)
        assertEquals("MF", f.lensLabel)
        assertEquals("Objectif en MF", f.afHint)
    }

    @Test
    fun focusModeUnknownHasNoLensLabel() {
        val f = connected().toControls().focus
        assertNull(f.lensLabel)
        assertTrue(f.afEnabled)
        assertNull(f.afHint)
    }

    @Test
    fun settingValueLabelUsesCanonTables() {
        assertEquals("1/125", settingValueLabel(CameraSetting.SHUTTER, 0x70))
        assertEquals("f/5.6", settingValueLabel(CameraSetting.APERTURE, 0x30))
        assertEquals("400", settingValueLabel(CameraSetting.ISO, 0x58))
        assertEquals("Ombre", settingValueLabel(CameraSetting.WHITE_BALANCE, 8))
        assertEquals("0xEE", settingValueLabel(CameraSetting.ISO, 0xEE))
    }
}
