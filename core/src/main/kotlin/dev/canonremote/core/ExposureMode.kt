package dev.canonremote.core

enum class ExposureMode { P, TV, AV, M, BULB, SCENE, CUSTOM, UNKNOWN }

private val SCENE_CODES = setOf(0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19)

fun exposureModeOf(code: Int?): ExposureMode = when (code) {
    null -> ExposureMode.UNKNOWN
    0 -> ExposureMode.P
    1 -> ExposureMode.TV
    2 -> ExposureMode.AV
    3 -> ExposureMode.M
    4 -> ExposureMode.BULB
    7, 0x10, 0x11 -> ExposureMode.CUSTOM
    in SCENE_CODES -> ExposureMode.SCENE
    else -> ExposureMode.UNKNOWN
}

fun modeLabel(code: Int?): String? = when (code) {
    null -> null
    0 -> "P"
    1 -> "Tv"
    2 -> "Av"
    3 -> "M"
    4 -> "B"
    0x10 -> "C1"
    0x11 -> "C2"
    7 -> "C3"
    8 -> "A+"
    in SCENE_CODES -> "Scène"
    else -> "0x%X".format(code)
}

/** true = réglage verrouillé (non modifiable) dans ce mode. */
data class ExposureLocks(val shutter: Boolean, val aperture: Boolean, val iso: Boolean)

fun locksFor(mode: ExposureMode): ExposureLocks = when (mode) {
    ExposureMode.P -> ExposureLocks(shutter = true, aperture = true, iso = false)
    ExposureMode.TV -> ExposureLocks(shutter = false, aperture = true, iso = false)
    ExposureMode.AV -> ExposureLocks(shutter = true, aperture = false, iso = false)
    ExposureMode.M -> ExposureLocks(shutter = false, aperture = false, iso = false)
    ExposureMode.BULB -> ExposureLocks(shutter = true, aperture = false, iso = false)
    ExposureMode.SCENE -> ExposureLocks(shutter = true, aperture = true, iso = true)
    ExposureMode.CUSTOM, ExposureMode.UNKNOWN -> ExposureLocks(shutter = false, aperture = false, iso = false)
}

fun isManualFocus(focusMode: Int?): Boolean = focusMode == 3
