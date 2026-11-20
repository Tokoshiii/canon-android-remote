package dev.canonremote.app.dev

import dev.canonremote.core.CameraController
import dev.canonremote.core.CameraSetting
import dev.canonremote.core.CameraState
import dev.canonremote.core.ConnectionStatus
import dev.canonremote.core.FocusStep
import dev.canonremote.core.LogBus
import dev.canonremote.core.SettingState
import dev.canonremote.core.exposureModeOf
import dev.canonremote.core.isManualFocus
import dev.canonremote.core.locksFor
import dev.canonremote.core.modeLabel
import dev.canonremote.core.settingValueLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * **Test tool** (Paramètres → Diagnostic → Mode développeur): a simulated EOS 90D that lets the
 * Appareil screen be tried without any camera. Nothing is sent anywhere: each command is written
 * to the log, prefixed « [dev] », and applied to the local [state] (a `set` becomes the current
 * value, a shot is only logged). The exposure mode, the lens AF/MF switch and the live view are
 * driven from the developer panel; the live view shows generated test images (~2 per second)
 * made by [renderFrame] (frame index, caption) on [renderContext].
 *
 * Used by [dev.canonremote.app.CameraHub] only while developer mode is on and no real camera is
 * connected.
 */
class DevCameraController(
    private val log: LogBus,
    private val scope: CoroutineScope,
    private val renderFrame: (index: Int, caption: String) -> ByteArray,
    private val renderContext: CoroutineContext = Dispatchers.Default,
) : CameraController {
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _frames = MutableStateFlow<ByteArray?>(null)
    override val liveViewFrames: StateFlow<ByteArray?> = _frames.asStateFlow()

    private var liveViewJob: Job? = null

    override suspend fun set(setting: CameraSetting, value: Int): Boolean {
        val s = _state.value
        val label = settingValueLabel(setting, value)
        val current = s.settings[setting] ?: SettingState()
        if (value !in current.available) {
            log.warn("[dev] ${setting.title} → $label refusé (valeur non proposée)")
            return false
        }
        if (isLocked(s.shootingMode, setting)) {
            log.warn("[dev] ${setting.title} → $label refusé (verrouillé en ${modeLabel(s.shootingMode)})")
            return false
        }
        _state.update { it.copy(settings = it.settings + (setting to current.copy(current = value))) }
        log.info("[dev] ${setting.title} → $label")
        return true
    }

    override suspend fun shoot(): Boolean {
        log.info("[dev] Déclenchement")
        return true
    }

    override suspend fun autofocus(): Boolean {
        if (isManualFocus(_state.value.focusMode)) {
            log.warn("[dev] Autofocus refusé (objectif en MF)")
            return false
        }
        log.info("[dev] Autofocus")
        return true
    }

    override suspend fun focus(step: FocusStep): Boolean {
        log.info("[dev] Mise au point ${step.name}")
        return true
    }

    override suspend fun setLiveView(on: Boolean): Boolean {
        if (on) startLiveView() else stopLiveView()
        _state.update { it.copy(liveView = on) }
        log.info(if (on) "[dev] Live view activé" else "[dev] Live view désactivé")
        return true
    }

    /** Developer panel: simulated mode dial (0 P, 1 Tv, 2 Av, 3 M, 4 B, 9 A+…). */
    fun setShootingMode(code: Int) {
        _state.update { it.copy(shootingMode = code) }
        log.info("[dev] Mode → ${modeLabel(code)}")
    }

    /** Developer panel: simulated lens switch (0 AF, 3 MF). */
    fun setFocusMode(code: Int) {
        _state.update { it.copy(focusMode = code) }
        log.info("[dev] Objectif → ${if (isManualFocus(code)) "MF" else "AF"}")
    }

    override fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    override suspend fun disconnect() {
        stopLiveView()
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTED, liveView = false) }
        log.info("[dev] Appareil simulé arrêté")
    }

    private fun startLiveView() {
        synchronized(this) {
            if (liveViewJob?.isActive == true) return
            liveViewJob = scope.launch {
                var index = 0
                while (isActive) {
                    val caption = caption(_state.value)
                    val i = index++
                    val jpeg = withContext(renderContext) { renderFrame(i, caption) }
                    // Stopped meanwhile: no frame after the live view went off.
                    if (!isActive) break
                    _frames.value = jpeg
                    delay(FRAME_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopLiveView() {
        synchronized(this) {
            liveViewJob?.cancel()
            liveViewJob = null
        }
        _frames.value = null
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 500L
    }
}

/** Line written on the test image: mode and exposure values. */
internal fun caption(s: CameraState): String {
    fun v(setting: CameraSetting) = s.settings[setting]?.current?.let { settingValueLabel(setting, it) } ?: "—"
    return "${modeLabel(s.shootingMode) ?: "—"}  ${v(CameraSetting.SHUTTER)}  ${v(CameraSetting.APERTURE)}  " +
        "ISO ${v(CameraSetting.ISO)}"
}

private fun isLocked(shootingMode: Int?, setting: CameraSetting): Boolean {
    val locks = locksFor(exposureModeOf(shootingMode))
    return when (setting) {
        CameraSetting.SHUTTER -> locks.shutter
        CameraSetting.APERTURE -> locks.aperture
        CameraSetting.ISO -> locks.iso
        CameraSetting.WHITE_BALANCE -> false
    }
}

/** Third-stop values 30" … 1/8000 (Canon codes, see CanonValues). */
private val DEV_SHUTTER: List<Int> = (0x10..0x98 step 8).flatMap { listOf(it, it + 3, it + 5) } + 0xA0

/** Auto, then ISO 100 … 25600 in third stops. */
private val DEV_ISO: List<Int> = listOf(0x00) + (0x48..0x80 step 8).flatMap { listOf(it, it + 3, it + 5) } + 0x88

/** A f/3.5–22 zoom (EF-S 18-135 mm at 18 mm) in third stops. */
private val DEV_APERTURE: List<Int> = listOf(0x25) + (0x28..0x48 step 8).flatMap { listOf(it, it + 3, it + 5) } + 0x50

/** Auto, Auto (blanc), Lumière du jour, Ombre, Nuageux, Tungstène, Fluorescent, Flash, Personnalisée, K. */
private val DEV_WHITE_BALANCE: List<Int> = listOf(0, 23, 1, 8, 2, 3, 4, 5, 6, 9)

private fun initialState() = CameraState(
    status = ConnectionStatus.CONNECTED,
    model = "90D (simulé)",
    serial = "DEV-0000",
    settings = mapOf(
        CameraSetting.ISO to SettingState(current = 0x58, available = DEV_ISO),
        CameraSetting.SHUTTER to SettingState(current = 0x70, available = DEV_SHUTTER),
        CameraSetting.APERTURE to SettingState(current = 0x30, available = DEV_APERTURE),
        CameraSetting.WHITE_BALANCE to SettingState(current = 0, available = DEV_WHITE_BALANCE),
    ),
    shootingMode = 3,
    focusMode = 0,
)
