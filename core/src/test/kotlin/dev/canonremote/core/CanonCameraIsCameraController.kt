package dev.canonremote.core

import dev.canonremote.ptp.PtpOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonCameraIsCameraController {
    @Test fun setThroughInterface() = runTest {
        val t = FakeTransport()
        t.handler = { cmd ->
            when (cmd.code) {
                PtpOp.GET_DEVICE_INFO -> t.respond(cmd, data = testDeviceInfo())
                0x9116 -> t.respond(cmd, data = TERMINATOR)
                else -> t.respond(cmd)
            }
        }
        val canon = CanonCamera(t, LogBus(), backgroundScope, 200)
        canon.connect()
        val controller: CameraController = canon
        assertTrue(controller.set(CameraSetting.ISO, 0x48))
    }
}
