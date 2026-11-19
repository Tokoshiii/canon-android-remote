package dev.canonremote.app

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.canonremote.app.relay.RelayLink
import dev.canonremote.app.relay.RelayUiState
import dev.canonremote.app.relay.RemoteLinkUi
import dev.canonremote.app.settings.AppMode
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.app.ui.connection.ConnectionActions
import dev.canonremote.app.usb.UsbStatus
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ControlsModel
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.RemoteCamera
import dev.canonremote.core.toControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI side of [CameraHub]: the Appareil screen drives [CameraHub.activeCamera] whatever the mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions, ConnectionActions {
    val log: LogBus = (app as CanonRemoteApp).log
    val hub: CameraHub = (app as CanonRemoteApp).hub

    val usbStatus: StateFlow<UsbStatus> = hub.usbStatus
    val mode: StateFlow<AppMode> = hub.mode
    val relayState: StateFlow<RelayUiState> = hub.relayState
    val remoteLink: StateFlow<RemoteLinkUi> = hub.remoteLink

    /** State of the USB camera (Connexion tab), whatever the mode. */
    val usbCameraState: StateFlow<CameraState> = hub.usbCamera
        .flatMapLatest { it?.state ?: flowOf(CameraState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState())

    /** State of the camera the Appareil screen drives. */
    val cameraState: StateFlow<CameraState> = hub.activeCamera
        .flatMapLatest { it?.state ?: flowOf(CameraState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState())

    val controls: StateFlow<ControlsModel> = cameraState
        .map { it.toControls() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState().toControls())

    /** True when the Appareil screen drives a relay over Bluetooth (no live view). */
    val bluetoothRemote: StateFlow<Boolean> = hub.activeCamera
        .map { it is RemoteCamera && it.link == LinkKind.BLUETOOTH }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val rawFrames: StateFlow<ByteArray?> = hub.activeCamera
        .flatMapLatest { it?.liveViewFrames ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live view JPEG decoded off the main thread. */
    val frame: StateFlow<ImageBitmap?> = rawFrames
        .map { bytes -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val errors: Flow<String> = hub.errors

    override fun connect() = hub.connectUsb()

    override fun disconnect() = hub.disconnectUsb()

    override fun setMode(mode: AppMode) = hub.setMode(mode)

    override fun startRelay() = hub.startRelay()

    override fun stopRelay() = hub.stopRelay()

    override fun setRelayLink(link: RelayLink, enabled: Boolean) = hub.setRelayLink(link, enabled)

    override fun restartBluetoothLink() = hub.restartBluetoothLink()

    override fun connectRemote(target: RemoteTarget) = hub.connectRemote(target)

    override fun reconnectRemote() = hub.reconnectRemote()

    override fun disconnectRemote() = hub.disconnectRemote()

    override fun discoverRelays(): Flow<RelayDiscovery> = hub.discoverRelays()

    override fun bluetoothAdapter(): BluetoothAdapter? = hub.bluetoothAdapter()

    override fun onSet(setting: CameraSetting, value: Int) {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.set(setting, value) }
    }

    override fun onShoot() {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.shoot() }
    }

    override fun onAutofocus() {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.autofocus() }
    }

    override fun onFocus(step: FocusStep) {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.focus(step) }
    }

    override fun onLiveView(on: Boolean) {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.setLiveView(on) }
    }

    override fun onCleared() {
        // The app's UI is gone: as before, stop driving the camera, unless the relay keeps it.
        hub.releaseIfIdle()
        super.onCleared()
    }
}
