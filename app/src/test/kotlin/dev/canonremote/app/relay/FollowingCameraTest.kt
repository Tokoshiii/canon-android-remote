package dev.canonremote.app.relay

import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingCameraTest {
    private class FakeCamera(model: String) : CameraController {
        override val state = MutableStateFlow(CameraState(ConnectionStatus.CONNECTED, model = model))
        override val liveViewFrames = MutableStateFlow<ByteArray?>(null)
        val calls = mutableListOf<String>()
        override suspend fun set(setting: CameraSetting, value: Int): Boolean { calls += "set $setting $value"; return true }
        override suspend fun shoot(): Boolean { calls += "shoot"; return true }
        override suspend fun autofocus(): Boolean { calls += "af"; return true }
        override suspend fun focus(step: FocusStep): Boolean { calls += "focus $step"; return true }
        override suspend fun setLiveView(on: Boolean): Boolean { calls += "lv $on"; return true }
        override fun clearError() { calls += "clear" }
        override suspend fun disconnect() { calls += "disconnect" }
    }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withTimeout(2000) { first(predicate) }

    @Test
    fun followsTheCurrentCamera(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = MutableStateFlow<CameraController?>(null)
            val following = FollowingCamera(source, scope)
            assertEquals(CameraState(), following.state.value)
            assertFalse(following.shoot())

            val a = FakeCamera("A")
            source.value = a
            following.state.awaitValue { it.model == "A" }
            assertTrue(following.shoot())
            assertTrue(following.setLiveView(true))
            assertEquals(listOf("shoot", "lv true"), a.calls)

            a.liveViewFrames.value = byteArrayOf(1)
            following.liveViewFrames.awaitValue { it?.contentEquals(byteArrayOf(1)) == true }

            val b = FakeCamera("B")
            source.value = b
            following.state.awaitValue { it.model == "B" }
            following.liveViewFrames.awaitValue { it == null }
            following.focus(FocusStep.NEAR_1)
            assertEquals(listOf("focus NEAR_1"), b.calls)

            source.value = null
            following.state.awaitValue { it.status == ConnectionStatus.DISCONNECTED }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun neverDisconnectsTheCamera(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = FakeCamera("A")
            val following = FollowingCamera(MutableStateFlow<CameraController?>(a), scope)
            following.disconnect()
            following.clearError()
            assertEquals(listOf("clear"), a.calls)
        } finally {
            scope.cancel()
        }
    }
}
