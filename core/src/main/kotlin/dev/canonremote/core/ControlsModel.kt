package dev.canonremote.core

import dev.canonremote.ptp.CanonValues

/** Choix dans une liste (feuille de sélection), p. ex. la balance des blancs. */
data class ChoiceModel(
    val setting: CameraSetting,
    val title: String,
    val valueLabel: String,
    val enabled: Boolean,
    val options: List<Pair<Int, String>>,
    val selected: Int?,
)

/**
 * Curseur à crans d'un réglage (vitesse, ouverture, ISO).
 * [index] = position de la valeur courante dans [options], ou -1 si inconnue (alors [valueLabel] = « — »).
 * [majorIndices] = positions des crans « principaux » (points affichés).
 */
data class SliderModel(
    val setting: CameraSetting,
    val title: String,
    val valueLabel: String,
    val options: List<Pair<Int, String>>,
    val index: Int,
    val majorIndices: Set<Int>,
    val enabled: Boolean,
    val locked: Boolean,
    val lockHint: String?,
    val autoSelected: Boolean,
)

/** Mise au point : [lensLabel] « AF » / « MF », null si le mode de l'objectif est inconnu. */
data class FocusModel(
    val afEnabled: Boolean,
    val manualStepsEnabled: Boolean,
    val lensLabel: String?,
    val afHint: String?,
)

/** Modèle d'écran pur, dérivé d'un [CameraState]. */
data class ControlsModel(
    val statusLabel: String,
    val connected: Boolean,
    val modeLabel: String?,
    val sliders: List<SliderModel>,
    val whiteBalance: ChoiceModel,
    val focus: FocusModel,
    val shutterEnabled: Boolean,
    val liveViewToggleEnabled: Boolean,
    val liveViewOn: Boolean,
)

private const val NO_VALUE = "—"
private const val SHUTTER_HINT = "Molette du boîtier sur M ou Tv pour régler"
private const val APERTURE_HINT = "Molette du boîtier sur M ou Av pour régler"
private const val ISO_HINT = "Mode scène : ISO automatique"
private const val ISO_AUTO = 0x00

private val SLIDER_ORDER = listOf(CameraSetting.SHUTTER, CameraSetting.APERTURE, CameraSetting.ISO)

private fun sliderFor(
    setting: CameraSetting,
    state: SettingState,
    connected: Boolean,
    locked: Boolean,
    hint: String?,
    bulb: Boolean,
): SliderModel {
    if (!connected) {
        return SliderModel(
            setting, setting.title, NO_VALUE, emptyList(), -1, emptySet(),
            enabled = false, locked = false, lockHint = null, autoSelected = false,
        )
    }
    val options = state.available.map { it to CanonValues.label(setting.prop, it) }
    val current = state.current
    val index = if (current == null) -1 else state.available.indexOf(current)
    val majors = state.available.withIndex()
        .filter { CanonValues.isMajor(setting.prop, it.value) }
        .map { it.index }
        .toSet()
    val label = when {
        bulb -> "Bulb"
        current != null -> CanonValues.label(setting.prop, current)
        else -> NO_VALUE
    }
    return SliderModel(
        setting = setting,
        title = setting.title,
        valueLabel = label,
        options = options,
        index = index,
        majorIndices = majors,
        enabled = options.isNotEmpty() && !locked,
        locked = locked,
        lockHint = if (locked) hint else null,
        autoSelected = setting == CameraSetting.ISO && current == ISO_AUTO,
    )
}

fun CameraState.toControls(): ControlsModel {
    val connected = status == ConnectionStatus.CONNECTED
    val mode = exposureModeOf(shootingMode)
    val locks = locksFor(mode)
    val sliders = SLIDER_ORDER.map { setting ->
        val (locked, hint) = when (setting) {
            CameraSetting.SHUTTER -> locks.shutter to SHUTTER_HINT
            CameraSetting.APERTURE -> locks.aperture to APERTURE_HINT
            else -> locks.iso to ISO_HINT
        }
        sliderFor(
            setting = setting,
            state = settings[setting] ?: SettingState(),
            connected = connected,
            locked = locked,
            // Bulb : la vitesse est verrouillée mais « Bulb » se suffit à lui-même, pas d'aide.
            hint = if (mode == ExposureMode.BULB && setting == CameraSetting.SHUTTER) null else hint,
            bulb = mode == ExposureMode.BULB && setting == CameraSetting.SHUTTER,
        )
    }

    val wbSetting = CameraSetting.WHITE_BALANCE
    val wbState = settings[wbSetting] ?: SettingState()
    val whiteBalance = if (!connected) {
        ChoiceModel(wbSetting, wbSetting.title, NO_VALUE, false, emptyList(), null)
    } else {
        ChoiceModel(
            setting = wbSetting,
            title = wbSetting.title,
            valueLabel = wbState.current?.let { CanonValues.label(wbSetting.prop, it) } ?: NO_VALUE,
            enabled = wbState.available.isNotEmpty(),
            options = wbState.available.map { it to CanonValues.label(wbSetting.prop, it) },
            selected = wbState.current,
        )
    }

    val manualLens = isManualFocus(focusMode)
    val focus = FocusModel(
        afEnabled = connected && !manualLens,
        manualStepsEnabled = connected && liveView,
        lensLabel = when {
            !connected || focusMode == null -> null
            manualLens -> "MF"
            else -> "AF"
        },
        afHint = if (connected && manualLens) "Objectif en MF" else null,
    )

    return ControlsModel(
        statusLabel = when (status) {
            ConnectionStatus.DISCONNECTED -> "Déconnecté"
            ConnectionStatus.CONNECTING -> "Connexion…"
            ConnectionStatus.CONNECTED -> model ?: "Connecté"
        },
        connected = connected,
        modeLabel = if (connected) modeLabel(shootingMode) else null,
        sliders = sliders,
        whiteBalance = whiteBalance,
        focus = focus,
        shutterEnabled = connected && !busy,
        liveViewToggleEnabled = connected,
        liveViewOn = liveView,
    )
}
