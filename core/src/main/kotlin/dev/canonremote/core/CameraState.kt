package dev.canonremote.core

@kotlinx.serialization.Serializable
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

@kotlinx.serialization.Serializable
enum class CameraSetting(val prop: Int, val title: String) {
    ISO(0xD103, "ISO"),
    SHUTTER(0xD102, "Vitesse"),
    APERTURE(0xD101, "Ouverture"),
    WHITE_BALANCE(0xD109, "Balance des blancs"),
}

@kotlinx.serialization.Serializable
enum class FocusStep(val code: Int) {
    NEAR_3(0x0003), NEAR_2(0x0002), NEAR_1(0x0001), FAR_1(0x8001), FAR_2(0x8002), FAR_3(0x8003),
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
