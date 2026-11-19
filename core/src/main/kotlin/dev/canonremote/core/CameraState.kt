package dev.canonremote.core

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

enum class CameraSetting(val prop: Int, val title: String) {
    ISO(0xD103, "ISO"),
    SHUTTER(0xD102, "Vitesse"),
    APERTURE(0xD101, "Ouverture"),
    WHITE_BALANCE(0xD109, "Balance des blancs"),
}

data class SettingState(val current: Int? = null, val available: List<Int> = emptyList())

data class CameraState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val model: String? = null,
    val serial: String? = null,
    val settings: Map<CameraSetting, SettingState> = emptyMap(),
    val liveView: Boolean = false,
    val busy: Boolean = false,
    val lastError: String? = null,
)
