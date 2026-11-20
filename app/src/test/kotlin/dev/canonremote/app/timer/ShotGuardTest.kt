package dev.canonremote.app.timer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotGuardTest {
    private class Cam(val ok: Boolean) {
        var shots = 0
    }

    private suspend fun shootCam(c: Cam): Boolean {
        c.shots++
        return c.ok
    }

    @Test
    fun shootsWhenCapturedCameraIsStillActive() = runTest {
        val cam = Cam(ok = true)
        assertEquals(ShotOutcome.TAKEN, shootIfStillActive(cam, { cam }, ::shootCam))
        assertEquals(1, cam.shots)
    }

    @Test
    fun reportsFailureOfShoot() = runTest {
        val cam = Cam(ok = false)
        assertEquals(ShotOutcome.FAILED, shootIfStillActive(cam, { cam }, ::shootCam))
        assertEquals(1, cam.shots)
    }

    @Test
    fun neverShootsAnotherCameraThanTheCapturedOne() = runTest {
        val captured = Cam(ok = true)
        val other = Cam(ok = true)
        assertEquals(ShotOutcome.CAMERA_CHANGED, shootIfStillActive(captured, { other }, ::shootCam))
        assertEquals(ShotOutcome.CAMERA_CHANGED, shootIfStillActive(captured, { null }, ::shootCam))
        assertTrue(captured.shots == 0 && other.shots == 0)
    }
}
