package dev.canonremote.app.timer

enum class ShotOutcome { TAKEN, FAILED, CAMERA_CHANGED }

/**
 * Shot at the end of a countdown: only on the camera [captured] when the shutter was pressed,
 * and only if it is still the [active] one. A camera that changed meanwhile (simulated → real,
 * real → simulated, another mode) is never shot.
 */
suspend fun <C : Any> shootIfStillActive(captured: C, active: () -> C?, shoot: suspend (C) -> Boolean): ShotOutcome = when {
    active() !== captured -> ShotOutcome.CAMERA_CHANGED
    shoot(captured) -> ShotOutcome.TAKEN
    else -> ShotOutcome.FAILED
}
