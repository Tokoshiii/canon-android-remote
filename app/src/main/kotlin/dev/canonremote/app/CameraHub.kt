package dev.canonremote.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.canonremote.app.relay.PairingCode
import dev.canonremote.app.relay.RelayLink
import dev.canonremote.app.relay.RelayService
import dev.canonremote.app.relay.RelayUiState
import dev.canonremote.app.relay.RemoteLinkTracker
import dev.canonremote.app.relay.RemoteLinkUi
import dev.canonremote.app.relay.remoteLinkUi
import dev.canonremote.app.remote.DiscoveredRelay
import dev.canonremote.app.remote.RelayHotspot
import dev.canonremote.app.remote.RelayNsd
import dev.canonremote.app.remote.connectBluetooth
import dev.canonremote.app.remote.connectWebSocket
import dev.canonremote.app.remote.joinHotspot
import dev.canonremote.app.remote.leaveHotspot
import dev.canonremote.app.settings.AppMode
import dev.canonremote.app.settings.AppSettings
import dev.canonremote.app.usb.UsbConnector
import dev.canonremote.app.usb.UsbStatus
import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraState
import dev.canonremote.core.CanonCamera
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.LogBus
import dev.canonremote.core.remote.LinkKind
import dev.canonremote.core.remote.RelayClient
import dev.canonremote.core.remote.RemoteCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the remote connects to. */
sealed interface RemoteTarget {
    /** WebSocket relay; [ssid]/[password] set when the relay's access point must be joined first. */
    data class Wifi(
        val host: String,
        val port: Int,
        val code: String,
        val ssid: String? = null,
        val password: String? = null,
    ) : RemoteTarget

    data class Bluetooth(val device: BluetoothDevice) : RemoteTarget
}

/** Relays found on the local Wi-Fi, or why the search failed. */
data class RelayDiscovery(val relays: List<DiscoveredRelay> = emptyList(), val error: String? = null)

/**
 * App-level owner of the cameras, shared by the UI and [RelayService]: the USB [CanonCamera], the
 * relay's state (its runtime lives in [RelayService]) and the [RemoteCamera] of the Télécommande
 * mode. [activeCamera] is what the Appareil screen drives: the USB camera in Direct and Relais
 * modes, the remote in Télécommande mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraHub(context: Context, val log: LogBus, private val settings: AppSettings) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connector = UsbConnector(this.context, log)

    val usbStatus: StateFlow<UsbStatus> = connector.status

    private val _usbCamera = MutableStateFlow<CanonCamera?>(null)
    val usbCamera: StateFlow<CanonCamera?> = _usbCamera.asStateFlow()

    private val _remote = MutableStateFlow<RemoteCamera?>(null)
    val remote: StateFlow<RemoteCamera?> = _remote.asStateFlow()

    val mode: StateFlow<AppMode> = settings.preferences
        .map { it.mode }
        .stateIn(scope, SharingStarted.Eagerly, settings.preferences.value.mode)

    val activeCamera: StateFlow<CameraController?> = combine(mode, _usbCamera, _remote) { m, usb, remote ->
        if (m == AppMode.REMOTE) remote else usb
    }.stateIn(scope, SharingStarted.Eagerly, if (mode.value == AppMode.REMOTE) null else _usbCamera.value)

    private val errorChannel = Channel<String>(Channel.BUFFERED)

    /** Errors of the active camera (each consumed once), for the snackbar. */
    val errors: Flow<String> = errorChannel.receiveAsFlow()

    private val _relay = MutableStateFlow(RelayUiState())
    val relayState: StateFlow<RelayUiState> = _relay.asStateFlow()

    private val _bluetoothRestarts = MutableStateFlow(0)

    /** Bumped by « Relancer »: the relay restarts its Bluetooth server. */
    val bluetoothRestarts: StateFlow<Int> = _bluetoothRestarts.asStateFlow()

    private val hotspot = RelayHotspot(this.context)
    private var hotspotJob: Job? = null

    private val tracker = RemoteLinkTracker()
    private val _remoteLink = MutableStateFlow(remoteLinkUi(ConnectionStatus.DISCONNECTED, LinkKind.WIFI, null))
    val remoteLink: StateFlow<RemoteLinkUi> = _remoteLink.asStateFlow()
    private var lastTarget: RemoteTarget? = null
    private var remoteJob: Job? = null
    private var joining = false
    private var remoteStatus = ConnectionStatus.DISCONNECTED

    /** [joinHotspot] / [leaveHotspot] calls must not overlap. */
    private val hotspotJoinLock = Mutex()
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var opening = false

    init {
        scope.launch {
            activeCamera
                .flatMapLatest { cam -> cam?.state?.map { cam to it } ?: emptyFlow() }
                .collect { (cam, s) ->
                    if (cam is RemoteCamera && cam === _remote.value) {
                        tracker.onState(s.status, s.lastError)
                        remoteStatus = s.status
                        publishRemoteLink()
                    }
                    s.lastError?.let {
                        errorChannel.trySend(it)
                        cam.clearError()
                    }
                }
        }
        scope.launch {
            _usbCamera
                .flatMapLatest { it?.state ?: flowOf(CameraState()) }
                .collect { s -> connector.markConnected(s.status == ConnectionStatus.CONNECTED) }
        }
        scope.launch {
            connector.status.collect { status ->
                if (status is UsbStatus.NoDevice && usbCameraStatus() != ConnectionStatus.DISCONNECTED) {
                    log.warn("Appareil USB débranché")
                    disconnectUsb()
                }
            }
        }
    }

    private fun usbCameraStatus(): ConnectionStatus =
        _usbCamera.value?.state?.value?.status ?: ConnectionStatus.DISCONNECTED

    // ---- Mode ----

    fun setMode(newMode: AppMode) {
        val old = mode.value
        if (old == newMode) return
        if (old == AppMode.RELAY) stopRelay()
        if (old == AppMode.REMOTE) disconnectRemote()
        if (newMode == AppMode.REMOTE && usbCameraStatus() != ConnectionStatus.DISCONNECTED) {
            log.info("Mode Télécommande : déconnexion de l'appareil USB")
            disconnectUsb()
        }
        settings.setMode(newMode)
    }

    /** Called when the UI goes away: disconnects the cameras unless the relay is running. */
    fun releaseIfIdle() {
        if (_relay.value.running) return
        disconnectUsb()
        if (_remote.value != null) disconnectRemote()
    }

    // ---- USB (Direct and Relais modes) ----

    fun connectUsb() {
        if (opening || usbCameraStatus() != ConnectionStatus.DISCONNECTED) return
        connector.requestPermission { device ->
            if (opening || usbCameraStatus() != ConnectionStatus.DISCONNECTED) return@requestPermission
            opening = true
            scope.launch {
                try {
                    val opened = withContext(Dispatchers.IO) { connector.open(device) }
                    if (opened == null) {
                        log.error("Impossible d'ouvrir l'interface USB")
                        errorChannel.trySend("Impossible d'ouvrir l'appareil en USB")
                        return@launch
                    }
                    val cam = CanonCamera(opened, log, scope)
                    _usbCamera.value = cam
                    cam.connect()
                } finally {
                    opening = false
                }
            }
        }
    }

    fun disconnectUsb() {
        val cam = _usbCamera.value ?: return
        scope.launch {
            withContext(NonCancellable) { cam.disconnect() }
        }
    }

    // ---- Relais ----

    /** Starts the relay (new pairing code) and its foreground service. Call from a UI action. */
    fun startRelay() {
        if (_relay.value.running) return
        val code = PairingCode.generate()
        _relay.update {
            it.copy(running = true, pairingCode = code, client = null, hotspot = null, linkErrors = emptyMap())
        }
        log.info("Relais démarré")
        try {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        } catch (e: Exception) {
            log.error("Impossible de démarrer le service du relais", e)
            errorChannel.trySend("Impossible de démarrer le relais")
            _relay.update { it.copy(running = false, pairingCode = null) }
            return
        }
        if (RelayLink.HOTSPOT in _relay.value.links) startHotspot()
    }

    fun stopRelay() {
        if (!_relay.value.running) return
        stopHotspot()
        _relay.update {
            it.copy(running = false, pairingCode = null, client = null, hotspot = null, hotspotStarting = false)
        }
        log.info("Relais arrêté")
        context.stopService(Intent(context, RelayService::class.java))
    }

    /** Switches a link on or off; the access point must be switched on from a UI action. */
    fun setRelayLink(link: RelayLink, enabled: Boolean) {
        _relay.update {
            it.copy(
                links = if (enabled) it.links + link else it.links - link,
                linkErrors = it.linkErrors - link,
            )
        }
        if (link == RelayLink.HOTSPOT) {
            if (enabled && _relay.value.running) startHotspot()
            if (!enabled) stopHotspot()
        }
    }

    fun restartBluetoothLink() {
        _relay.update { it.copy(linkErrors = it.linkErrors - RelayLink.BLUETOOTH) }
        _bluetoothRestarts.update { it + 1 }
    }

    /** Called by the relay runtime (any thread). */
    internal fun reportLinkError(link: RelayLink, message: String?) {
        _relay.update {
            it.copy(linkErrors = if (message == null) it.linkErrors - link else it.linkErrors + (link to message))
        }
    }

    /** Called by the relay runtime (any thread). */
    internal fun reportClient(client: RelayClient?) {
        _relay.update { if (it.running || client == null) it.copy(client = client) else it }
    }

    private fun startHotspot() {
        hotspotJob?.cancel()
        _relay.update { it.copy(hotspot = null, hotspotStarting = true) }
        val job = scope.launch {
            try {
                val info = hotspot.start()
                val wanted = _relay.value.let { it.running && RelayLink.HOTSPOT in it.links }
                if (wanted) {
                    log.info("Point d'accès du relais : ${info.ssid} (${info.hostIp})")
                    _relay.update { it.copy(hotspot = info) }
                } else {
                    hotspot.stop()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Impossible de démarrer le point d'accès"
                log.warn("Point d'accès : $message")
                _relay.update {
                    it.copy(links = it.links - RelayLink.HOTSPOT, linkErrors = it.linkErrors + (RelayLink.HOTSPOT to message))
                }
            }
        }
        hotspotJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (hotspotJob === job) {
                    hotspotJob = null
                    _relay.update { it.copy(hotspotStarting = false) }
                }
            }
        }
    }

    private fun stopHotspot() {
        hotspotJob?.cancel()
        hotspotJob = null
        hotspot.stop()
        _relay.update { it.copy(hotspot = null, hotspotStarting = false) }
    }

    // ---- Télécommande ----

    /** Relays announced on the local Wi-Fi; discovery runs while the flow is collected. */
    fun discoverRelays(): Flow<RelayDiscovery> = RelayNsd(context).discover()
        .map { RelayDiscovery(relays = it) }
        .catch { e -> emit(RelayDiscovery(error = e.message ?: "Recherche des relais impossible")) }

    fun bluetoothAdapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun connectRemote(target: RemoteTarget) {
        remoteJob?.cancel()
        lastTarget = target
        tracker.reset()
        val old = _remote.value
        val link = if (target is RemoteTarget.Bluetooth) LinkKind.BLUETOOTH else LinkKind.WIFI
        val rc = when (target) {
            is RemoteTarget.Wifi -> RemoteCamera(
                connect = { connectWebSocket(target.host, target.port) },
                link = LinkKind.WIFI,
                token = target.code,
                clientName = Build.MODEL,
                log = log,
                scope = remoteScope,
            )
            is RemoteTarget.Bluetooth -> RemoteCamera(
                connect = { connectBluetooth(target.device) },
                link = LinkKind.BLUETOOTH,
                token = null,
                clientName = Build.MODEL,
                log = log,
                scope = remoteScope,
            )
        }
        val needsJoin = target is RemoteTarget.Wifi && target.ssid != null
        joining = needsJoin
        remoteStatus = ConnectionStatus.DISCONNECTED
        _remote.value = rc
        publishRemoteLink(link)
        remoteJob = scope.launch {
            old?.let { withContext(NonCancellable) { it.disconnect() } }
            try {
                hotspotJoinLock.withLock {
                    if (target is RemoteTarget.Wifi && target.ssid != null) {
                        log.info("Connexion au point d'accès « ${target.ssid} »")
                        joinHotspot(context, target.ssid, target.password.orEmpty())
                    } else {
                        leaveHotspot()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Impossible de rejoindre le point d'accès du relais"
                log.warn(message)
                joining = false
                tracker.onFailure(message)
                publishRemoteLink(link)
                errorChannel.trySend(message)
                return@launch
            }
            joining = false
            publishRemoteLink(link)
            rc.connect()
        }
    }

    /** « Se reconnecter »: same relay, same link (the access point is joined again if needed). */
    fun reconnectRemote() {
        lastTarget?.let(::connectRemote)
    }

    fun disconnectRemote() {
        remoteJob?.cancel()
        remoteJob = null
        val rc = _remote.value
        _remote.value = null
        lastTarget = null
        joining = false
        tracker.reset()
        remoteStatus = ConnectionStatus.DISCONNECTED
        publishRemoteLink()
        scope.launch {
            withContext(NonCancellable) {
                rc?.disconnect()
                hotspotJoinLock.withLock { leaveHotspot() }
            }
        }
    }

    private fun publishRemoteLink(link: LinkKind = _remote.value?.link ?: LinkKind.WIFI) {
        _remoteLink.value = if (joining) {
            RemoteLinkUi(
                title = "Connexion au point d'accès du relais…",
                detail = "Accepte la demande de connexion Wi-Fi si elle s'affiche",
                isError = false,
                canReconnect = false,
                canDisconnect = true,
            )
        } else {
            remoteLinkUi(remoteStatus, link, tracker.failure)
        }
    }
}
