package dev.canonremote.app.dev

import dev.canonremote.core.CameraSetting
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.toControls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class DevCameraControllerTest {
    private val log = LogBus()
    private val rendered = mutableListOf<Pair<Int, String>>()

    private fun TestScope.controller() = DevCameraController(
        log = log,
        scope = backgroundScope,
        renderFrame = { index, caption ->
            rendered += index to caption
            byteArrayOf(index.toByte())
        },
        renderContext = EmptyCoroutineContext,
    )

    private fun messages() = log.entries.value.map { it.message }

    @Test
    fun startsConnectedAsSimulated90dInManualWithAf() = runTest {
        val s = controller().state.value
        assertEquals(ConnectionStatus.CONNECTED, s.status)
        assertEquals("90D (simulé)", s.model)
        assertEquals(3, s.shootingMode)
        assertEquals(0, s.focusMode)
        assertFalse(s.liveView)
        CameraSetting.entries.forEach { setting ->
            val st = s.settings.getValue(setting)
            assertTrue("$setting has values", st.available.isNotEmpty())
            assertTrue("$setting current in list", st.current in st.available)
        }
    }

    @Test
    fun listsAreRealistic90dValues() = runTest {
        val s = controller().state.value.settings
        val iso = s.getValue(CameraSetting.ISO).available
        assertEquals(0x00, iso.first()) // Auto first
        assertEquals(0x48, iso[1]) // 100
        assertEquals(0x88, iso.last()) // 25600
        val shutter = s.getValue(CameraSetting.SHUTTER).available
        assertEquals(0x10, shutter.first()) // 30"
        assertEquals(0xA0, shutter.last()) // 1/8000
        assertEquals(shutter.sorted(), shutter)
        val controls = controller().state.value.toControls()
        controls.sliders.forEach { slider ->
            slider.options.forEach { (_, label) -> assertFalse("hex label $label", label.startsWith("0x")) }
        }
        controls.whiteBalance.options.forEach { (_, label) -> assertFalse(label.startsWith("0x")) }
    }

    @Test
    fun setAppliesValueAndLogs() = runTest {
        val cam = controller()
        assertTrue(cam.set(CameraSetting.ISO, 0x60))
        assertEquals(0x60, cam.state.value.settings.getValue(CameraSetting.ISO).current)
        assertEquals("[dev] ISO → 800", messages().last())
    }

    @Test
    fun setRejectsValueNotOffered() = runTest {
        val cam = controller()
        val before = cam.state.value
        assertFalse(cam.set(CameraSetting.ISO, 0x33))
        assertEquals(before, cam.state.value)
        assertTrue(messages().last().startsWith("[dev] "))
    }

    @Test
    fun setRefusesSettingLockedByMode() = runTest {
        val cam = controller()
        cam.setShootingMode(2) // Av: shutter locked
        val shutter = cam.state.value.settings.getValue(CameraSetting.SHUTTER).current
        assertFalse(cam.set(CameraSetting.SHUTTER, 0x80))
        assertEquals(shutter, cam.state.value.settings.getValue(CameraSetting.SHUTTER).current)
        assertTrue(cam.set(CameraSetting.APERTURE, 0x38))
        assertEquals(0x38, cam.state.value.settings.getValue(CameraSetting.APERTURE).current)
    }

    @Test
    fun shootingAndFocusModesAreSettableAndLogged() = runTest {
        val cam = controller()
        cam.setShootingMode(1)
        assertEquals(1, cam.state.value.shootingMode)
        assertEquals("[dev] Mode → Tv", messages().last())
        cam.setFocusMode(3)
        assertEquals(3, cam.state.value.focusMode)
        assertEquals("[dev] Objectif → MF", messages().last())
    }

    @Test
    fun shootLogsAndSucceeds() = runTest {
        val cam = controller()
        assertTrue(cam.shoot())
        assertEquals("[dev] Déclenchement", messages().last())
        assertFalse(cam.state.value.busy)
    }

    @Test
    fun autofocusRefusedWithManualLens() = runTest {
        val cam = controller()
        assertTrue(cam.autofocus())
        assertEquals("[dev] Autofocus", messages().last())
        cam.setFocusMode(3)
        assertFalse(cam.autofocus())
        assertTrue(messages().last().startsWith("[dev] "))
    }

    @Test
    fun focusStepIsLogged() = runTest {
        val cam = controller()
        assertTrue(cam.focus(FocusStep.FAR_3))
        assertTrue(messages().last().startsWith("[dev] Mise au point"))
    }

    @Test
    fun liveViewProducesFramesAboutTwicePerSecondUntilStopped() = runTest {
        val cam = controller()
        assertTrue(cam.setLiveView(true))
        runCurrent()
        assertTrue(cam.state.value.liveView)
        assertArrayEquals(byteArrayOf(0), cam.liveViewFrames.value)
        advanceTimeBy(500); runCurrent()
        assertArrayEquals(byteArrayOf(1), cam.liveViewFrames.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, rendered.size)
        assertTrue(rendered.last().second.contains("ISO 400"))

        assertTrue(cam.setLiveView(false))
        runCurrent()
        assertFalse(cam.state.value.liveView)
        assertNull(cam.liveViewFrames.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(4, rendered.size)
        assertEquals("[dev] Live view désactivé", messages().last())
    }

    @Test
    fun disconnectStopsLiveViewAndGoesDisconnected() = runTest {
        val cam = controller()
        cam.setLiveView(true)
        runCurrent()
        cam.disconnect()
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, cam.state.value.status)
        assertFalse(cam.state.value.liveView)
        assertNull(cam.liveViewFrames.value)
        val count = rendered.size
        advanceTimeBy(2_000); runCurrent()
        assertEquals(count, rendered.size)
    }

    @Test
    fun everyCommandIsPrefixedDev() = runTest {
        val cam = controller()
        cam.set(CameraSetting.WHITE_BALANCE, 8)
        cam.shoot()
        cam.autofocus()
        cam.focus(FocusStep.NEAR_1)
        cam.setLiveView(true)
        cam.setLiveView(false)
        cam.setShootingMode(9)
        cam.setFocusMode(0)
        cam.disconnect()
        assertTrue(messages().isNotEmpty())
        messages().forEach { assertTrue(it, it.startsWith("[dev] ")) }
    }
}
