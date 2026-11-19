package dev.canonremote.core

import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonCameraCommandsTest {
    private val t = FakeTransport()
    private val log = LogBus()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
    private val jpegBlock = byteArrayOf((8 + jpeg.size).toByte(), 0, 0, 0, 1, 0, 0, 0) + jpeg

    /** Default handler; [extra] may answer first (return null to fall through). */
    private fun script(extra: suspend (dev.canonremote.ptp.PtpContainer) -> List<dev.canonremote.ptp.PtpContainer>? = { null }) {
        t.handler = { cmd ->
            extra(cmd) ?: when (cmd.code) {
                PtpOp.GET_DEVICE_INFO -> t.respond(cmd, data = testDeviceInfo())
                0x9116 -> t.respond(cmd, data = TERMINATOR)
                else -> t.respond(cmd)
            }
        }
    }

    private fun TestScope.camera() = CanonCamera(t, log, backgroundScope, 200)

    private fun commands() = t.sent.filter { it.type == ContainerType.COMMAND && it.code != 0x9116 }

    private suspend fun CanonCamera.connectedAndClear(): CanonCamera {
        connect()
        t.sent.clear()
        return this
    }

    @Test fun setSendsPropPayload() = runTest {
        script()
        val cam = camera().connectedAndClear()
        assertTrue(cam.set(CameraSetting.ISO, 0x48))
        val data = t.sent.single { it.type == ContainerType.DATA && it.code == 0x9110 }
        assertArrayEquals(byteArrayOf(0x0C, 0, 0, 0, 0x03, 0xD1.toByte(), 0, 0, 0x48, 0, 0, 0), data.payload)
    }

    @Test fun setWhenBusyReportsError() = runTest {
        script { cmd -> if (cmd.code == 0x9110) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else null }
        val cam = camera().connectedAndClear()
        assertFalse(cam.set(CameraSetting.ISO, 0x48))
        assertTrue(cam.state.value.lastError!!.contains("0x2019"))
        assertNull(cam.state.value.settings[CameraSetting.ISO]?.current)
        assertTrue(log.entries.value.any { it.level == LogLevel.ERROR && it.message.contains("refusé : 0x2019") })
        t.sent.clear()
        advanceTimeBy(250)
        assertTrue(t.sent.any { it.code == 0x9116 })
        cam.clearError()
        assertNull(cam.state.value.lastError)
    }

    @Test fun shootSendsReleaseSequence() = runTest {
        script()
        val cam = camera().connectedAndClear()
        assertTrue(cam.shoot())
        assertEquals(
            listOf(0x9128 to listOf(1, 0), 0x9128 to listOf(2, 0), 0x9129 to listOf(2), 0x9129 to listOf(1)),
            commands().map { it.code to it.params },
        )
        assertFalse(cam.state.value.busy)
    }

    @Test fun shootResetsBusyOnError() = runTest {
        script { cmd -> if (cmd.code == 0x9128) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else null }
        val cam = camera().connectedAndClear()
        assertFalse(cam.shoot())
        assertTrue(commands().none { it.code == 0x9129 })
        assertFalse(cam.state.value.busy)
        assertTrue(cam.state.value.lastError!!.contains("0x2019"))
    }

    @Test fun shootReleasesShutterWhenSecondPressFails() = runTest {
        script { cmd ->
            if (cmd.code == 0x9128 && cmd.params == listOf(2, 0)) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else null
        }
        val cam = camera().connectedAndClear()
        assertFalse(cam.shoot())
        assertEquals(
            listOf(0x9128 to listOf(1, 0), 0x9128 to listOf(2, 0), 0x9129 to listOf(1)),
            commands().map { it.code to it.params },
        )
        assertFalse(cam.state.value.busy)
    }

    @Test fun shootTransportErrorOnReleaseDisconnects() = runTest {
        script { cmd ->
            if (cmd.code == 0x9129 && cmd.params == listOf(2)) throw java.io.IOException("usb")
            null
        }
        val cam = camera().connectedAndClear()
        assertFalse(cam.shoot())
        assertEquals(ConnectionStatus.DISCONNECTED, cam.state.value.status)
        assertTrue(commands().any { it.code == 0x9129 && it.params == listOf(1) })
    }

    @Test fun secondShootWhileBusyIsIgnored() = runTest {
        script { cmd -> if (cmd.code == 0x9128 || cmd.code == 0x9129) { delay(100); t.respond(cmd) } else null }
        val cam = camera().connectedAndClear()
        val a = async { cam.shoot() }
        val b = async { cam.shoot() }
        val results = listOf(a.await(), b.await())
        assertEquals(listOf(true, false), results.sortedDescending())
        assertEquals(4, commands().size)
    }

    @Test fun autofocusSendsDoAf() = runTest {
        script()
        val cam = camera().connectedAndClear()
        assertTrue(cam.autofocus())
        assertEquals(listOf(0x9154), commands().map { it.code })
    }

    @Test fun focusRequiresLiveView() = runTest {
        script()
        val cam = camera().connectedAndClear()
        assertFalse(cam.focus(FocusStep.FAR_1))
        assertTrue(commands().isEmpty())
        assertTrue(cam.setLiveView(true))
        assertTrue(cam.focus(FocusStep.FAR_1))
        assertEquals(listOf(0x8001), commands().last { it.code == 0x9155 }.params)
    }

    @Test fun liveViewPublishesFrames() = runTest {
        script { cmd -> if (cmd.code == 0x9153) t.respond(cmd, data = jpegBlock) else null }
        val cam = camera().connectedAndClear()
        assertTrue(cam.setLiveView(true))
        assertTrue(cam.state.value.liveView)
        advanceTimeBy(60)
        assertArrayEquals(jpeg, cam.liveViewFrames.value)
        val vf = commands().first { it.code == 0x9153 }
        assertEquals(listOf(0x00200000, 0, 0), vf.params)

        assertTrue(cam.setLiveView(false))
        assertFalse(cam.state.value.liveView)
        assertNull(cam.liveViewFrames.value)
        val sets = t.sent.filter { it.type == ContainerType.DATA && it.code == 0x9110 }
        assertEquals(2, sets.size)
        assertEquals(2, sets[0].payload[8].toInt())
        assertEquals(0, sets[1].payload[8].toInt())
    }

    @Test fun liveViewLoopStopsAfterOff() = runTest {
        script { cmd -> if (cmd.code == 0x9153) t.respond(cmd, data = jpegBlock) else null }
        val cam = camera().connectedAndClear()
        cam.setLiveView(true)
        advanceTimeBy(60)
        cam.setLiveView(false)
        t.sent.clear()
        advanceTimeBy(150)
        assertTrue(commands().none { it.code == 0x9153 })
    }

    @Test fun liveViewSkipsNotReady() = runTest {
        var n = 0
        script { cmd ->
            if (cmd.code == 0x9153) {
                if (n++ == 0) t.respond(cmd, code = PtpResponse.CANON_NOT_READY) else t.respond(cmd, data = jpegBlock)
            } else null
        }
        val cam = camera().connectedAndClear()
        cam.setLiveView(true)
        advanceTimeBy(120)
        assertArrayEquals(jpeg, cam.liveViewFrames.value)
        assertNull(cam.state.value.lastError)
        assertTrue(log.entries.value.none { it.level == LogLevel.ERROR })
    }

    @Test fun disconnectStopsLiveView() = runTest {
        script { cmd -> if (cmd.code == 0x9153) t.respond(cmd, data = jpegBlock) else null }
        val cam = camera().connectedAndClear()
        cam.setLiveView(true)
        advanceTimeBy(60)
        cam.disconnect()
        val sets = t.sent.filter { it.type == ContainerType.DATA && it.code == 0x9110 }
        assertEquals(0, sets.last().payload[8].toInt())
        assertNull(cam.liveViewFrames.value)
        assertFalse(cam.state.value.liveView)
        t.sent.clear()
        advanceTimeBy(500)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun commandsWhenDisconnectedDoNothing() = runTest {
        script()
        val cam = camera()
        assertFalse(cam.set(CameraSetting.ISO, 1))
        assertFalse(cam.shoot())
        assertFalse(cam.autofocus())
        assertFalse(cam.focus(FocusStep.NEAR_1))
        assertFalse(cam.setLiveView(true))
        assertTrue(t.sent.isEmpty())
    }

    @Test fun secondConnectIsNoOp() = runTest {
        script()
        val cam = camera()
        cam.connect()
        cam.connect()
        assertEquals(1, t.sent.count { it.type == ContainerType.COMMAND && it.code == PtpOp.OPEN_SESSION })
        t.sent.clear()
        advanceTimeBy(450)
        assertEquals(2, t.sent.count { it.code == 0x9116 }) // a single poll loop
    }

    @Test fun transportCloseFailureIsLogged() = runTest {
        script()
        val closing = object : Transport by t {
            override fun close() = throw IllegalStateException("boom")
        }
        val cam = CanonCamera(closing, log, backgroundScope, 200)
        cam.connect()
        cam.disconnect()
        assertEquals(ConnectionStatus.DISCONNECTED, cam.state.value.status)
        assertTrue(log.entries.value.any { it.level == LogLevel.WARN && it.message.contains("boom") })
    }

    @Test fun pollErrorWarnLoggedOnlyWhenCodeChanges() = runTest {
        var polls = 0
        script { cmd ->
            if (cmd.code == 0x9116 && polls++ > 0) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else null
        }
        val cam = camera()
        cam.connect()
        advanceTimeBy(1050)
        assertEquals(1, log.entries.value.count { it.level == LogLevel.WARN && it.message.contains("GetEvent") })
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
    }
}
