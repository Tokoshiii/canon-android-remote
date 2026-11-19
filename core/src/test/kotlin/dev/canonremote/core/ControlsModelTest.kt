package dev.canonremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsModelTest {
    @Test
    fun disconnectedShowsGreyedTiles() {
        val model = CameraState().toControls()

        assertEquals("Déconnecté", model.statusLabel)
        assertFalse(model.connected)
        assertEquals(
            listOf("ISO", "Vitesse", "Ouverture", "Balance des blancs"),
            model.tiles.map { it.title },
        )
        model.tiles.forEach { tile ->
            assertFalse(tile.enabled)
            assertEquals("—", tile.valueLabel)
            assertTrue(tile.options.isEmpty())
            assertNull(tile.selected)
        }
        assertFalse(model.shutterEnabled)
        assertFalse(model.afEnabled)
        assertFalse(model.manualFocusEnabled)
        assertFalse(model.liveViewToggleEnabled)
    }

    @Test
    fun connectedShowsLabels() {
        val state = CameraState(
            status = ConnectionStatus.CONNECTED,
            model = "Canon EOS 90D",
            settings = mapOf(
                CameraSetting.ISO to SettingState(current = 0x58, available = listOf(0x48, 0x58)),
            ),
        )

        val model = state.toControls()
        val iso = model.tiles.first { it.setting == CameraSetting.ISO }

        assertEquals("Canon EOS 90D", model.statusLabel)
        assertTrue(model.connected)
        assertEquals("ISO", iso.title)
        assertEquals("400", iso.valueLabel)
        assertTrue(iso.enabled)
        assertEquals(listOf(0x48 to "100", 0x58 to "400"), iso.options)
        assertEquals(0x58, iso.selected)
        assertTrue(model.afEnabled)
        assertTrue(model.liveViewToggleEnabled)
        assertTrue(model.shutterEnabled)
    }

    @Test
    fun connectingAndUnnamedConnectedStatusLabels() {
        assertEquals("Connexion…", CameraState(status = ConnectionStatus.CONNECTING).toControls().statusLabel)
        assertEquals(
            "Connecté",
            CameraState(status = ConnectionStatus.CONNECTED).toControls().statusLabel,
        )
    }

    @Test
    fun tileWithoutAvailableValuesDisabled() {
        val state = CameraState(
            status = ConnectionStatus.CONNECTED,
            settings = mapOf(CameraSetting.SHUTTER to SettingState(current = null, available = emptyList())),
        )

        val shutter = state.toControls().tiles.first { it.setting == CameraSetting.SHUTTER }

        assertFalse(shutter.enabled)
        assertEquals("—", shutter.valueLabel)
        assertTrue(shutter.options.isEmpty())
        assertNull(shutter.selected)
    }

    @Test
    fun busyDisablesShutter() {
        val state = CameraState(status = ConnectionStatus.CONNECTED, busy = true)

        assertFalse(state.toControls().shutterEnabled)
    }

    @Test
    fun manualFocusOnlyWithLiveView() {
        val connected = CameraState(status = ConnectionStatus.CONNECTED)
        assertFalse(connected.toControls().manualFocusEnabled)

        val withLiveView = connected.copy(liveView = true)
        val model = withLiveView.toControls()
        assertTrue(model.manualFocusEnabled)
        assertTrue(model.liveViewOn)
    }
}
