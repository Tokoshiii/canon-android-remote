package dev.canonremote.core

import kotlinx.coroutines.flow.StateFlow

/** Commands and observable state of a camera, independent of how it is reached (USB or relay). */
interface CameraController {
    val state: StateFlow<CameraState>
    val liveViewFrames: StateFlow<ByteArray?>

    suspend fun set(setting: CameraSetting, value: Int): Boolean
    suspend fun shoot(): Boolean
    suspend fun autofocus(): Boolean
    suspend fun focus(step: FocusStep): Boolean
    suspend fun setLiveView(on: Boolean): Boolean
    fun clearError()
    suspend fun disconnect()
}
