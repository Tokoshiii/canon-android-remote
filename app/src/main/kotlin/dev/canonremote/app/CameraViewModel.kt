package dev.canonremote.app

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.canonremote.app.ui.CameraActions
import dev.canonremote.app.usb.UsbConnector
import dev.canonremote.app.usb.UsbStatus
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.CanonCamera
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.ControlsModel
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.toControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions {
    val log: LogBus = (app as CanonRemoteApp).log
    private val connector = UsbConnector(app, log)

    private val camera = MutableStateFlow<CanonCamera?>(null)

    val usbStatus: StateFlow<UsbStatus> = connector.status

    val cameraState: StateFlow<CameraState> = camera
        .flatMapLatest { it?.state ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState())

    val controls: StateFlow<ControlsModel> = cameraState
        .map { it.toControls() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState().toControls())

    private val rawFrames: StateFlow<ByteArray?> = camera
        .flatMapLatest { it?.liveViewFrames ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live view JPEG decoded off the main thread. */
    val frame: StateFlow<ImageBitmap?> = rawFrames
        .map { bytes -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val errorChannel = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = errorChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            cameraState.collect { s ->
                s.lastError?.let {
                    errorChannel.trySend(it)
                    camera.value?.clearError()
                }
            }
        }
        viewModelScope.launch {
            cameraState.collect { s -> connector.markConnected(s.status == ConnectionStatus.CONNECTED) }
        }
        viewModelScope.launch {
            connector.status.collect { status ->
                if (status is UsbStatus.NoDevice && cameraState.value.status != ConnectionStatus.DISCONNECTED) {
                    log.warn("Appareil USB débranché")
                    disconnect()
                }
            }
        }
    }

    fun connect() {
        if (cameraState.value.status != ConnectionStatus.DISCONNECTED) return
        connector.requestPermission { device ->
            viewModelScope.launch {
                val transport = withContext(Dispatchers.IO) { connector.open(device) }
                if (transport == null) {
                    log.error("Impossible d'ouvrir l'interface USB")
                    errorChannel.trySend("Impossible d'ouvrir l'appareil en USB")
                    return@launch
                }
                val cam = CanonCamera(transport, log, viewModelScope)
                camera.value = cam
                cam.connect()
            }
        }
    }

    fun disconnect() {
        val cam = camera.value ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { cam.disconnect() }
        }
    }

    override fun onSet(setting: CameraSetting, value: Int) {
        val cam = camera.value ?: return
        viewModelScope.launch { cam.set(setting, value) }
    }

    override fun onShoot() {
        val cam = camera.value ?: return
        viewModelScope.launch { cam.shoot() }
    }

    override fun onAutofocus() {
        val cam = camera.value ?: return
        viewModelScope.launch { cam.autofocus() }
    }

    override fun onFocus(step: FocusStep) {
        val cam = camera.value ?: return
        viewModelScope.launch { cam.focus(step) }
    }

    override fun onLiveView(on: Boolean) {
        val cam = camera.value ?: return
        viewModelScope.launch { cam.setLiveView(on) }
    }

    override fun onCleared() {
        connector.close()
        super.onCleared()
    }
}
