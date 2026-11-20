package dev.canonremote.app.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TimerState {
    data object Idle : TimerState
    data class Counting(val remaining: Int) : TimerState
}

/**
 * Self-timer for the shutter button. Runs on the phone that triggers. Pressing while idle fires
 * (after [press]'s delay, counting down once per second); pressing while counting cancels.
 */
class ShutterTimer(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<TimerState>(TimerState.Idle)
    val state: StateFlow<TimerState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** [delaySeconds] 0 fires at once; [fire] runs after the countdown, then the state is Idle. */
    fun press(delaySeconds: Int, fire: suspend () -> Unit) {
        if (state.value is TimerState.Counting) {
            cancel()
            return
        }
        if (delaySeconds <= 0) {
            scope.launch { fire() }
            return
        }
        mutableState.value = TimerState.Counting(delaySeconds)
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                for (remaining in delaySeconds downTo 1) {
                    mutableState.value = TimerState.Counting(remaining)
                    delay(1_000)
                }
                // The countdown is over: from now on this job is no longer the timer's, so a new
                // press (or a delay change) neither cancels the shot in progress nor is reset by it.
                job = null
                mutableState.value = TimerState.Idle
                fire()
            } finally {
                // Cancelled mid-count (or fire threw): never stay in Counting, but only when this
                // job is still the current one; a newer countdown must not be clobbered.
                if (job === self) {
                    job = null
                    mutableState.value = TimerState.Idle
                }
            }
        }
    }

    /** The chosen delay changed: a running countdown no longer matches it, so it is cancelled. */
    fun onDelayChanged() = cancel()

    fun cancel() {
        val current = job ?: return
        current.cancel()
        job = null
        mutableState.value = TimerState.Idle
    }
}
