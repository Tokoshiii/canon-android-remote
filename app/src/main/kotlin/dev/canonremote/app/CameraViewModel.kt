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
import dev.canonremote.app.settings.AppSettings
import dev.canonremote.app.timer.ShotOutcome
import dev.canonremote.app.timer.ShutterTimer
import dev.canonremote.app.timer.shootIfStillActive
import dev.canonremote.app.timer.TimerState
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.app.ui.camera.DevPanelActions
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI side of [CameraHub]: the Appareil screen drives [CameraHub.activeCamera] whatever the mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions, ConnectionActions, DevPanelActions {
    val log: LogBus = (app as CanonRemoteApp).log
    val hub: CameraHub = (app as CanonRemoteApp).hub
    private val settings: AppSettings = (app as CanonRemoteApp).settings

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

    /** True while the Appareil screen drives the developer mode's simulated camera. */
    val devActive: StateFlow<Boolean> = hub.devCamera
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val errors: Flow<String> = hub.errors

    /** Self-timer of the shutter button; the countdown runs on this phone (the one that shoots). */
    private val timer = ShutterTimer(viewModelScope)
    val timerState: StateFlow<TimerState> = timer.state

    private val _shots = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One event per shot the camera took (after the countdown): vibration, sound, « Photo prise ». */
    val shots: SharedFlow<Unit> = _shots.asSharedFlow()

    init {
        // A countdown belongs to the camera it was started on: another camera cancels it.
        viewModelScope.launch {
            hub.activeCamera.drop(1).collect {
                if (timerState.value is TimerState.Counting) log.info("Retardateur annulé : l'appareil a changé")
                timer.cancel()
            }
        }
    }

    override fun connect() = hub.connectUsb()

    override fun disconnect() = hub.disconnectUsb()

    override fun setMode(mode: AppMode) = hub.setMode(mode)

    override fun startRelay() = hub.startRelay()

    override fun stopRelay() = hub.stopRelay()

    override fun setRelayLink(link: RelayLink, enabled: Boolean) = hub.setRelayLink(link, enabled)

    override fun restartBluetoothLink() = hub.restartBluetoothLink()

    override fun restartHotspot() = hub.restartHotspot()

    override fun connectRemote(target: RemoteTarget) = hub.connectRemote(target)

    override fun reconnectRemote() = hub.reconnectRemote()

    override fun reconnectRemoteWithCode(code: String) = hub.reconnectRemoteWithCode(code)

    override fun disconnectRemote() = hub.disconnectRemote()

    override fun discoverRelays(): Flow<RelayDiscovery> = hub.discoverRelays()

    override fun bluetoothAdapter(): BluetoothAdapter? = hub.bluetoothAdapter()

    override fun onSet(setting: CameraSetting, value: Int) {
        val cam = hub.activeCamera.value ?: return
        viewModelScope.launch { cam.set(setting, value) }
    }

    override fun onShutter() {
        // The camera is fixed at the press: the shot never goes to one that replaced it meanwhile.
        val cam = hub.activeCamera.value ?: run {
            timer.cancel()
            return
        }
        timer.press(settings.preferences.value.timerDelay) {
            when (shootIfStillActive(cam, { hub.activeCamera.value }) { it.shoot() }) {
                // Feedback only once the camera has taken the picture.
                ShotOutcome.TAKEN -> _shots.tryEmit(Unit)
                ShotOutcome.CAMERA_CHANGED -> log.warn("Déclenchement annulé : l'appareil a changé")
                ShotOutcome.FAILED -> Unit
            }
        }
    }

    override fun onTimerDelay(seconds: Int) {
        if (seconds == settings.preferences.value.timerDelay) return
        settings.setTimerDelay(seconds)
        timer.onDelayChanged()
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

    override fun onDevShootingMode(code: Int) {
        hub.devCamera.value?.setShootingMode(code)
    }

    override fun onDevFocusMode(code: Int) {
        hub.devCamera.value?.setFocusMode(code)
    }

    override fun onCleared() {
        // The app's UI is gone: as before, stop driving the camera, unless the relay keeps it.
        hub.releaseIfIdle()
        super.onCleared()
    }
}
