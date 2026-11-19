package dev.canonremote.core.remote

import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.FocusStep
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Test-only [CameraController]: records every call in [calls], returns [result] (or throws
 * [failure]) after [commandDelayMs]; [state] and [liveViewFrames] are freely scriptable.
 */
class FakeController(initial: CameraState = CameraState()) : CameraController {
    override val state = MutableStateFlow(initial)
    override val liveViewFrames = MutableStateFlow<ByteArray?>(null)

    val calls: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var result: Boolean = true
    @Volatile var failure: Throwable? = null
    @Volatile var commandDelayMs: Long = 0

    private suspend fun run(call: String): Boolean {
        calls += call
        if (commandDelayMs > 0) delay(commandDelayMs)
        failure?.let { throw it }
        return result
    }

    override suspend fun set(setting: CameraSetting, value: Int) = run("set($setting, $value)")
    override suspend fun shoot() = run("shoot")
    override suspend fun autofocus() = run("autofocus")
    override suspend fun focus(step: FocusStep) = run("focus($step)")
    override suspend fun setLiveView(on: Boolean) = run("setLiveView($on)")
    override fun clearError() { calls += "clearError" }
    override suspend fun disconnect() { calls += "disconnect" }
}
