package dev.canonremote.core

import dev.canonremote.ptp.CanonEvent
import dev.canonremote.ptp.CanonOp
import dev.canonremote.ptp.DeviceInfo
import dev.canonremote.ptp.PtpOp
import dev.canonremote.ptp.PtpResponse
import dev.canonremote.ptp.parseCanonEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    private var eventLoop: Job? = null
    private val loggedUnknownTypes = mutableSetOf<Int>()

    suspend fun connect() {
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
            throw e
        } catch (e: PtpException) {
            log.error("Échec de la connexion", e)
            failConnection("Connexion refusée par l'appareil")
            return
        } catch (e: Exception) {
            handleTransportFailure(e)
            return
        }
        eventLoop = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    pollEvents(logData = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PtpException) {
                    log.warn("GetEvent: ${e.message}")
                } catch (e: Exception) {
                    handleTransportFailure(e)
                    return@launch
                }
            }
        }
    }

    suspend fun disconnect() {
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
        transport.close()
        _liveViewFrames.value = null
        _state.value = CameraState()
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

    private fun handleTransportFailure(e: Exception) {
        log.error("Erreur de transport", e)
        failConnection("Appareil déconnecté")
    }

    private fun failConnection(message: String) {
        eventLoop?.cancel()
        eventLoop = null
        transport.close()
        _liveViewFrames.value = null
        _state.value = CameraState(status = ConnectionStatus.DISCONNECTED, lastError = message)
    }
}
