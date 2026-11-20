package dev.canonremote.core

import dev.canonremote.ptp.CanonEventCode
import dev.canonremote.ptp.CanonProp
import dev.canonremote.ptp.ContainerType
import dev.canonremote.ptp.PtpContainer
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CanonCameraConnectTest {
    private val t = FakeTransport()
    private val log = LogBus()

    private fun isoValue(v: Int) = testRecord(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.ISO, v) + TERMINATOR
    private fun isoList(vararg v: Int) =
        testRecord(CanonEventCode.AVAILABLE_LIST_CHANGED, CanonProp.ISO, 3, v.size, *v)

    /** Handler answering DeviceInfo and serving [events] in order for successive GetEvent calls. */
    private fun script(vararg events: ByteArray) {
        val queue = ArrayDeque(events.toList())
        t.handler = { cmd ->
            when (cmd.code) {
                PtpOp.GET_DEVICE_INFO -> t.respond(cmd, data = testDeviceInfo())
                0x9116 -> t.respond(cmd, data = queue.removeFirstOrNull() ?: TERMINATOR)
                else -> t.respond(cmd)
            }
        }
    }

    private fun TestScope.camera(poll: Long = 200) = CanonCamera(t, log, backgroundScope, poll)

    private fun codes() = t.sent.filter { it.type == dev.canonremote.ptp.ContainerType.COMMAND }.map { it.code }

    @Test fun connectRunsHandshakeInOrder() = runTest {
        script()
        val cam = camera()
        cam.connect()
        assertEquals(listOf(0x1002, 0x1001, 0x9114, 0x9115, 0x9116), codes().take(5))
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
        assertEquals("Canon EOS 90D", cam.state.value.model)
        assertEquals("0123456789", cam.state.value.serial)
    }

    @Test fun sessionAlreadyOpenIsAccepted() = runTest {
        script()
        val inner = t.handler
        t.handler = { cmd ->
            if (cmd.code == PtpOp.OPEN_SESSION) t.respond(cmd, code = PtpResponse.SESSION_ALREADY_OPEN) else inner(cmd)
        }
        val cam = camera()
        cam.connect()
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
    }

    @Test fun initialEventsFillSettings() = runTest {
        script(isoList(0x48, 0x50) + isoValue(0x50))
        val cam = camera()
        cam.connect()
        assertEquals(SettingState(0x50, listOf(0x48, 0x50)), cam.state.value.settings[CameraSetting.ISO])
    }

    @Test fun shootingAndFocusModeApplied() = runTest {
        script(
            testRecord(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.SHOOTING_MODE, 3) +
                testRecord(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.FOCUS_MODE, 3) + TERMINATOR,
            testRecord(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.SHOOTING_MODE, 2) + TERMINATOR,
        )
        val cam = camera()
        cam.connect()
        assertEquals(3, cam.state.value.shootingMode)
        assertEquals(3, cam.state.value.focusMode)
        advanceTimeBy(250)
        assertEquals(2, cam.state.value.shootingMode)
        assertEquals(3, cam.state.value.focusMode)
    }

    @Test fun pollingAppliesLaterChanges() = runTest {
        script(TERMINATOR, isoValue(0x48))
        val cam = camera()
        cam.connect()
        advanceTimeBy(250)
        assertEquals(0x48, cam.state.value.settings[CameraSetting.ISO]?.current)
    }

    @Test fun unknownEventLoggedOncePerType() = runTest {
        val unknown = testRecord(0xC1A7, 1, 2)
        script(unknown + TERMINATOR, unknown + TERMINATOR)
        val cam = camera()
        cam.connect()
        advanceTimeBy(250)
        assertEquals(1, log.entries.value.count { it.level == LogLevel.PTP && it.message.contains("0xC1A7") })
    }

    @Test fun malformedEventLogsWarn() = runTest {
        script(byteArrayOf(0x20, 0, 0, 0, 0x89.toByte(), 0xC1.toByte(), 0, 0))
        val cam = camera()
        cam.connect()
        assertTrue(log.entries.value.any { it.level == LogLevel.WARN && it.message.contains("malformé") })
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
    }

    @Test fun unplugDuringPollingDisconnects() = runTest {
        script()
        val cam = camera()
        cam.connect()
        t.failNextReceive = IOException("usb")
        advanceTimeBy(250)
        assertEquals(ConnectionStatus.DISCONNECTED, cam.state.value.status)
        assertEquals("Appareil déconnecté", cam.state.value.lastError)
        assertEquals(1, log.entries.value.count { it.level == LogLevel.ERROR })
        assertTrue(t.closed)
        val sentCount = t.sent.size
        advanceTimeBy(1000)
        assertEquals(sentCount, t.sent.size)
    }

    @Test fun failedHandshakeLeavesDisconnected() = runTest {
        t.failNextReceive = IOException("usb")
        val cam = camera()
        cam.connect()
        assertEquals(ConnectionStatus.DISCONNECTED, cam.state.value.status)
        assertEquals("Appareil déconnecté", cam.state.value.lastError)
    }

    @Test fun disconnectClosesSession() = runTest {
        script()
        val cam = camera()
        cam.connect()
        cam.disconnect()
        assertEquals(0x1003, codes().last())
        assertEquals(CameraState(), cam.state.value)
        assertTrue(t.closed)
        val sentCount = t.sent.size
        advanceTimeBy(1000)
        assertEquals(sentCount, t.sent.size)
    }

    private fun ptpLines() = log.entries.value.filter { it.level == LogLevel.PTP }.map { it.message }

    private fun propSets() = t.sent.filter { it.type == ContainerType.DATA && it.code == 0x9110 }
        .map { p -> p.payload.let { u32(it, 4) to u32(it, 8) } }

    private fun u32(b: ByteArray, at: Int) =
        (0..3).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }

    @Test fun idlePollsAreNotLogged() = runTest {
        script()
        val cam = camera()
        cam.connect()
        log.clear()
        advanceTimeBy(1050)
        assertTrue(t.sent.count { it.code == 0x9116 } > 3)
        assertEquals(emptyList<String>(), ptpLines())
    }

    @Test fun pollWithEventsIsLogged() = runTest {
        script(TERMINATOR, isoValue(0x48))
        val cam = camera()
        cam.connect()
        log.clear()
        advanceTimeBy(250)
        val ptp = ptpLines()
        assertTrue(ptp.toString(), ptp.any { it.startsWith("→ 0x9116") })
        assertTrue(ptp.toString(), ptp.any { it.startsWith("← DATA 24 o: ") })
    }

    @Test fun failedPollIsLoggedOncePerCode() = runTest {
        var polls = 0
        script()
        val inner = t.handler
        t.handler = { cmd ->
            if (cmd.code == 0x9116 && polls++ > 0) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else inner(cmd)
        }
        val cam = camera()
        cam.connect()
        advanceTimeBy(1050)
        assertEquals(1, ptpLines().count { it == "← 0x2019" })
    }

    @Test fun connectLogsHandshakeAndDumpsDeviceInfoInFull() = runTest {
        script()
        camera().connect()
        val ptp = ptpLines()
        listOf("→ 0x1002", "→ 0x1001", "→ 0x9114", "→ 0x9115", "→ 0x9116").forEach { op ->
            assertTrue(op, ptp.any { it.startsWith(op) })
        }
        val info = testDeviceInfo()
        val line = ptp.single { it.startsWith("← DATA ${info.size} o") }
        assertEquals(info.size, line.substringAfter(": ").split(" ").size)
    }

    @Test fun captureDestinationHostIsSwitchedToCard() = runTest {
        val dest = CanonProp.CAPTURE_DESTINATION
        script(
            testRecord(CanonEventCode.AVAILABLE_LIST_CHANGED, dest, 3, 2, 4, 2) +
                testRecord(CanonEventCode.PROP_VALUE_CHANGED, dest, 4) + TERMINATOR,
        )
        val cam = camera()
        cam.connect()
        assertEquals(listOf(dest to 2), propSets())
        val firstPoll = t.sent.indexOfFirst { it.code == 0x9116 }
        assertTrue(t.sent.indexOfFirst { it.code == 0x9110 } > firstPoll)
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
    }

    @Test fun captureDestinationCardIsLeftAlone() = runTest {
        script(testRecord(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.CAPTURE_DESTINATION, 2) + TERMINATOR)
        camera().connect()
        assertEquals(emptyList<Pair<Int, Int>>(), propSets())
    }

    @Test fun captureDestinationRefusalIsWarnOnly() = runTest {
        val dest = CanonProp.CAPTURE_DESTINATION
        script(
            testRecord(CanonEventCode.AVAILABLE_LIST_CHANGED, dest, 3, 2, 4, 1) +
                testRecord(CanonEventCode.PROP_VALUE_CHANGED, dest, 4) + TERMINATOR,
        )
        val inner = t.handler
        t.handler = { cmd -> if (cmd.code == 0x9110) t.respond(cmd, code = PtpResponse.DEVICE_BUSY) else inner(cmd) }
        val cam = camera()
        cam.connect()
        assertEquals(ConnectionStatus.CONNECTED, cam.state.value.status)
        assertTrue(log.entries.value.any { it.level == LogLevel.WARN && it.message.contains("0x2019") })
        assertTrue(log.entries.value.none { it.level == LogLevel.ERROR })
    }
}
