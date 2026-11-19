package dev.canonremote.app.relay

import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.FocusStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * [CameraController] that forwards to whichever camera [source] currently holds (the USB camera,
 * which the user may reconnect while the relay runs). With no camera: disconnected state, commands
 * return false. [disconnect] is a no-op: the relay never disconnects the camera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowingCamera(
    private val source: StateFlow<CameraController?>,
    scope: CoroutineScope,
) : CameraController {
    override val state: StateFlow<CameraState> = source
        .flatMapLatest { it?.state ?: flowOf(CameraState()) }
        .stateIn(scope, SharingStarted.Eagerly, source.value?.state?.value ?: CameraState())

    override val liveViewFrames: StateFlow<ByteArray?> = source
        .flatMapLatest { it?.liveViewFrames ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, source.value?.liveViewFrames?.value)

    override suspend fun set(setting: CameraSetting, value: Int) = source.value?.set(setting, value) ?: false
    override suspend fun shoot() = source.value?.shoot() ?: false
    override suspend fun autofocus() = source.value?.autofocus() ?: false
    override suspend fun focus(step: FocusStep) = source.value?.focus(step) ?: false
    override suspend fun setLiveView(on: Boolean) = source.value?.setLiveView(on) ?: false
    override fun clearError() { source.value?.clearError() }
    override suspend fun disconnect() = Unit
}
