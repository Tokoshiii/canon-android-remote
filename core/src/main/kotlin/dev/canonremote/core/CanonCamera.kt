package dev.canonremote.core

import dev.canonremote.ptp.CanonEvent
import dev.canonremote.ptp.CanonOp
import dev.canonremote.ptp.CanonProp
import dev.canonremote.ptp.CanonValues
import dev.canonremote.ptp.DeviceInfo
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import dev.canonremote.ptp.extractLiveViewJpeg
import dev.canonremote.ptp.parseCanonEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class CanonCamera(
    private val transport: Transport,
    private val log: LogBus,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 200,
) {
    private val session = PtpSession(transport, log)
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _liveViewFrames = MutableStateFlow<ByteArray?>(null)
    val liveViewFrames: StateFlow<ByteArray?> = _liveViewFrames.asStateFlow()

    @Volatile private var eventLoop: Job? = null
    @Volatile private var liveViewLoop: Job? = null
    private val loggedUnknownTypes = mutableSetOf<Int>()

    /** Guards connect/disconnect/live view start-stop/failure handling; [generation] invalidates stale failures. */
    private val lifecycle = Mutex()
    @Volatile private var generation = 0
    private val shooting = AtomicBoolean(false)

    suspend fun connect() = lifecycle.withLock {
        if (_state.value.status != ConnectionStatus.DISCONNECTED) return@withLock
        val gen = ++generation
        _state.value = CameraState(status = ConnectionStatus.CONNECTING)
        try {
            val open = session.execute(PtpOp.OPEN_SESSION, listOf(1))
            if (!open.isOk && open.responseCode != PtpResponse.SESSION_ALREADY_OPEN) {
                throw PtpException(open.responseCode, "OpenSession refusé: 0x%04X".format(open.responseCode))
            }
            val info = DeviceInfo.decode(session.executeOk(PtpOp.GET_DEVICE_INFO).data ?: ByteArray(0))
            log.info("Appareil: ${info.model} (série ${info.serialNumber})")
            _state.update { it.copy(model = info.model, serial = info.serialNumber) }
            session.executeOk(CanonOp.SET_REMOTE_MODE, listOf(1))
            session.executeOk(CanonOp.SET_EVENT_MODE, listOf(1))
            pollEvents(logData = true)
            _state.update { it.copy(status = ConnectionStatus.CONNECTED) }
        } catch (e: CancellationException) {
            _state.value = CameraState()
            throw e
        } catch (e: PtpException) {
            log.error("Échec de la connexion", e)
            failConnection("Connexion refusée par l'appareil")
            return@withLock
        } catch (e: Exception) {
            log.error("Erreur de transport", e)
            failConnection("Appareil déconnecté")
            return@withLock
        }
        eventLoop = scope.launch {
            var lastErrorCode: Int? = null
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    pollEvents(logData = false)
                    lastErrorCode = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PtpException) {
                    if (e.responseCode != lastErrorCode) log.warn("GetEvent: ${e.message}")
                    lastErrorCode = e.responseCode
                } catch (e: Exception) {
                    handleTransportFailure(gen, e)
                    return@launch
                }
            }
        }
    }

    suspend fun disconnect() = lifecycle.withLock {
        generation++
        stopLiveViewLoop()
        if (_state.value.liveView) {
            try {
                session.execute(CanonOp.SET_DEVICE_PROP_VALUE_EX, dataOut = CanonValues.setPropPayload(CanonProp.EVF_OUTPUT_DEVICE, 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Arrêt du live view ignoré: ${e.message}")
            }
        }
        eventLoop?.cancel()
        eventLoop?.join()
        eventLoop = null
        try {
            session.execute(PtpOp.CLOSE_SESSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("CloseSession ignoré: ${e.message}")
        }
        closeTransport()
        _liveViewFrames.value = null
        _state.value = CameraState()
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    suspend fun set(setting: CameraSetting, value: Int): Boolean = command("Réglage ${setting.title}") {
        session.executeOk(
            CanonOp.SET_DEVICE_PROP_VALUE_EX,
            dataOut = CanonValues.setPropPayload(setting.prop, value),
        )
    }

    suspend fun shoot(): Boolean {
        if (_state.value.status != ConnectionStatus.CONNECTED) return false
        if (!shooting.compareAndSet(false, true)) return false
        _state.update { it.copy(busy = true) }
        try {
            return command("Déclenchement") {
                session.executeOk(CanonOp.REMOTE_RELEASE_ON, listOf(1, 0))
                // Once the shutter is pressed, always release it, even if a later step fails.
                var secondPressed = false
                try {
                    session.executeOk(CanonOp.REMOTE_RELEASE_ON, listOf(2, 0))
                    secondPressed = true
                } finally {
                    withContext(NonCancellable) {
                        if (secondPressed) releaseBestEffort(2)
                        releaseBestEffort(1)
                    }
                }
            }
        } finally {
            _state.update { it.copy(busy = false) }
            shooting.set(false)
        }
    }

    private suspend fun releaseBestEffort(param: Int) {
        try {
            session.executeOk(CanonOp.REMOTE_RELEASE_OFF, listOf(param))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("RemoteReleaseOff($param) ignoré: ${e.message}")
        }
    }

    suspend fun autofocus(): Boolean = command("Mise au point automatique") {
        session.executeOk(CanonOp.DO_AF)
    }

    suspend fun focus(step: FocusStep): Boolean {
        if (!_state.value.liveView) return false
        return command("Mise au point manuelle") { session.executeOk(CanonOp.DRIVE_LENS, listOf(step.code)) }
    }

    suspend fun setLiveView(on: Boolean): Boolean {
        if (_state.value.status != ConnectionStatus.CONNECTED) return false
        val gen = generation
        return lifecycle.withLock {
            if (gen != generation || _state.value.status != ConnectionStatus.CONNECTED) return@withLock false
            if (on == _state.value.liveView) return@withLock true
            if (on) {
                val ok = command("Live view", lockHeld = true) {
                    session.executeOk(
                        CanonOp.SET_DEVICE_PROP_VALUE_EX,
                        dataOut = CanonValues.setPropPayload(CanonProp.EVF_OUTPUT_DEVICE, 2),
                    )
                }
                if (ok && _state.value.status == ConnectionStatus.CONNECTED) {
                    _state.update { it.copy(liveView = true) }
                    liveViewLoop = scope.launch { runLiveView(gen) }
                }
                ok
            } else {
                stopLiveViewLoop()
                val ok = command("Arrêt du live view", lockHeld = true) {
                    session.executeOk(
                        CanonOp.SET_DEVICE_PROP_VALUE_EX,
                        dataOut = CanonValues.setPropPayload(CanonProp.EVF_OUTPUT_DEVICE, 0),
                    )
                }
                if (_state.value.status == ConnectionStatus.CONNECTED) _state.update { it.copy(liveView = false) }
                _liveViewFrames.value = null
                ok
            }
        }
    }

    private suspend fun runLiveView(gen: Int) {
        var lastErrorCode: Int? = null
        while (currentCoroutineContext().isActive) {
            try {
                val r = session.execute(
                    CanonOp.GET_VIEWFINDER_DATA,
                    listOf(0x00200000, 0, 0),
                    logData = false,
                )
                if (r.isOk) {
                    lastErrorCode = null
                    r.data?.let { extractLiveViewJpeg(it) }?.let { _liveViewFrames.value = it }
                } else if (r.responseCode != PtpResponse.CANON_NOT_READY && r.responseCode != PtpResponse.DEVICE_BUSY) {
                    if (r.responseCode != lastErrorCode) {
                        log.warn("GetViewFinderData: réponse 0x%04X".format(r.responseCode))
                    }
                    lastErrorCode = r.responseCode
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleTransportFailure(gen, e)
                return
            }
            delay(LIVE_VIEW_INTERVAL_MS)
        }
    }

    private suspend fun stopLiveViewLoop() {
        liveViewLoop?.cancel()
        // Joining is safe: PtpSession transactions are NonCancellable and finish on their own.
        liveViewLoop?.join()
        liveViewLoop = null
    }

    /** Runs a command when CONNECTED. PtpException -> logged + lastError + false; transport error -> disconnect. */
    private suspend fun command(action: String, lockHeld: Boolean = false, block: suspend () -> Unit): Boolean {
        if (_state.value.status != ConnectionStatus.CONNECTED) return false
        val gen = generation
        return try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: PtpException) {
            val msg = "%s refusé : 0x%04X".format(action, e.responseCode)
            log.error(msg)
            _state.update { it.copy(lastError = msg) }
            false
        } catch (e: Exception) {
            if (lockHeld) {
                log.error("Erreur de transport", e)
                failConnection("Appareil déconnecté")
            } else {
                handleTransportFailure(gen, e)
            }
            false
        }
    }

    private suspend fun pollEvents(logData: Boolean) {
        val data = session.executeOk(CanonOp.GET_EVENT, logData = logData).data ?: return
        val events = parseCanonEvents(data)
        _state.update { s -> s.copy(settings = events.fold(s.settings, ::applyEvent)) }
    }

    private fun applyEvent(
        settings: Map<CameraSetting, SettingState>,
        event: CanonEvent,
    ): Map<CameraSetting, SettingState> {
        fun update(prop: Int, f: (SettingState) -> SettingState): Map<CameraSetting, SettingState> {
            val setting = CameraSetting.entries.firstOrNull { it.prop == prop } ?: return settings
            return settings + (setting to f(settings[setting] ?: SettingState()))
        }
        return when (event) {
            is CanonEvent.PropValueChanged -> update(event.prop) { it.copy(current = event.value) }
            is CanonEvent.AvailableValuesChanged -> update(event.prop) { it.copy(available = event.values) }
            is CanonEvent.Unknown -> {
                if (loggedUnknownTypes.add(event.type)) {
                    log.ptp("Événement Canon inconnu 0x%04X (%d o)".format(event.type, event.size))
                }
                settings
            }
            is CanonEvent.Malformed -> {
                log.warn("Événement Canon malformé à l'offset ${event.offset}: ${event.reason}")
                settings
            }
        }
    }

    private suspend fun handleTransportFailure(gen: Int, e: Exception) {
        lifecycle.withLock {
            if (gen != generation) return@withLock // stale failure from a previous connection
            log.error("Erreur de transport", e)
            failConnection("Appareil déconnecté")
        }
    }

    /** Must be called with [lifecycle] held. */
    private fun failConnection(message: String) {
        generation++
        eventLoop?.cancel()
        eventLoop = null
        liveViewLoop?.cancel()
        liveViewLoop = null
        closeTransport()
        _liveViewFrames.value = null
        _state.value = CameraState(status = ConnectionStatus.DISCONNECTED, lastError = message)
    }

    private fun closeTransport() {
        try {
            transport.close()
        } catch (e: Exception) {
            log.warn("Fermeture du transport ignorée: ${e.message}")
        }
    }

    private companion object {
        const val LIVE_VIEW_INTERVAL_MS = 50L
    }
}
