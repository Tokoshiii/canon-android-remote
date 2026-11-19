package dev.canonremote.core

import dev.canonremote.ptp.ByteWriter
import dev.canonremote.ptp.CanonEventCode
import dev.canonremote.ptp.CanonProp
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

    private fun ByteWriter.ptpString(s: String) {
        u8(s.length + 1)
        s.forEach { u16(it.code) }
        u16(0)
    }

    private fun deviceInfo(): ByteArray {
        val w = ByteWriter()
        w.u16(100); w.u32(0xB); w.u16(100)
        w.ptpString("canon.com: 1.0")
        w.u16(0)
        repeat(4) { w.u32(0) }
        w.u32(0)
        w.ptpString("Canon Inc.")
        w.ptpString("Canon EOS 90D")
        w.ptpString("3-1.0.0")
        w.ptpString("0123456789")
        return w.toByteArray()
    }

    private fun record(type: Int, vararg body: Int): ByteArray {
        val w = ByteWriter()
        w.u32(8 + body.size * 4)
        w.u32(type)
        body.forEach { w.u32(it) }
        return w.toByteArray()
    }

    private val terminator = byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0)

    private fun isoValue(v: Int) = record(CanonEventCode.PROP_VALUE_CHANGED, CanonProp.ISO, v) + terminator
    private fun isoList(vararg v: Int) =
        record(CanonEventCode.AVAILABLE_LIST_CHANGED, CanonProp.ISO, 3, v.size, *v)

    /** Handler answering DeviceInfo and serving [events] in order for successive GetEvent calls. */
    private fun script(vararg events: ByteArray) {
        val queue = ArrayDeque(events.toList())
        t.handler = { cmd ->
            when (cmd.code) {
                PtpOp.GET_DEVICE_INFO -> t.respond(cmd, data = deviceInfo())
                0x9116 -> t.respond(cmd, data = queue.removeFirstOrNull() ?: terminator)
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

    @Test fun pollingAppliesLaterChanges() = runTest {
        script(terminator, isoValue(0x48))
        val cam = camera()
        cam.connect()
        advanceTimeBy(250)
        assertEquals(0x48, cam.state.value.settings[CameraSetting.ISO]?.current)
    }

    @Test fun unknownEventLoggedOncePerType() = runTest {
        val unknown = record(0xC1A7, 1, 2)
        script(unknown + terminator, unknown + terminator)
        val cam = camera()
        cam.connect()
        advanceTimeBy(250)
        assertEquals(1, log.entries.value.count { it.level == LogLevel.PTP && it.message.contains("0xC1A7") })
    }

    @Test fun malformedEventLogsWarn() = runTest {
        script(byteArrayOf(0x20, 0, 0, 0, 0x89.toByte(), 0xC1.toByte(), 0, 0))
        val cam = camera()
        cam.connect()
        assertTrue(log.entries.value.any { it.level == LogLevel.WARN })
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
}
