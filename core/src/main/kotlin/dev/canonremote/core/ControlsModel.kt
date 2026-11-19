package dev.canonremote.core

import dev.canonremote.ptp.CanonValues

/** Tuile d'un réglage : libellés déjà formatés, sans logique PTP. */
data class TileModel(
    val setting: CameraSetting,
    val title: String,
    val valueLabel: String,
    val enabled: Boolean,
    val options: List<Pair<Int, String>>,
    val selected: Int?,
)

/** Modèle d'écran pur, dérivé d'un [CameraState]. */
data class ControlsModel(
    val statusLabel: String,
    val connected: Boolean,
    val tiles: List<TileModel>,
    val shutterEnabled: Boolean,
    val afEnabled: Boolean,
    val manualFocusEnabled: Boolean,
    val liveViewToggleEnabled: Boolean,
    val liveViewOn: Boolean,
)

private const val NO_VALUE = "—"

private val TILE_ORDER = listOf(
    CameraSetting.ISO,
    CameraSetting.SHUTTER,
    CameraSetting.APERTURE,
    CameraSetting.WHITE_BALANCE,
)

fun CameraState.toControls(): ControlsModel {
    val connected = status == ConnectionStatus.CONNECTED
    val tiles = TILE_ORDER.map { setting ->
        val settingState = settings[setting] ?: SettingState()
        if (!connected) {
            TileModel(setting, setting.title, NO_VALUE, false, emptyList(), null)
        } else {
            val current = settingState.current
            TileModel(
                setting = setting,
                title = setting.title,
                valueLabel = current?.let { CanonValues.label(setting.prop, it) } ?: NO_VALUE,
                enabled = settingState.available.isNotEmpty(),
                options = settingState.available.map { it to CanonValues.label(setting.prop, it) },
                selected = current,
            )
        }
    }
    return ControlsModel(
        statusLabel = when (status) {
            ConnectionStatus.DISCONNECTED -> "Déconnecté"
            ConnectionStatus.CONNECTING -> "Connexion…"
            ConnectionStatus.CONNECTED -> model ?: "Connecté"
        },
        connected = connected,
        tiles = tiles,
        shutterEnabled = connected && !busy,
        afEnabled = connected,
        manualFocusEnabled = connected && liveView,
        liveViewToggleEnabled = connected,
        liveViewOn = liveView,
    )
}
