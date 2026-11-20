package dev.canonremote.app.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShutterTimerTest {
    @Test
    fun zeroDelayFiresImmediatelyAndStaysIdle() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(0) { fired++ }
        runCurrent()
        assertEquals(1, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun countsDownOnePerSecondThenFires() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(2) { fired++ }
        runCurrent()
        assertEquals(TimerState.Counting(2), timer.state.value)
        advanceTimeBy(999); runCurrent()
        assertEquals(TimerState.Counting(2), timer.state.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(TimerState.Counting(1), timer.state.value)
        assertEquals(0, fired)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun tenSecondCountdownEmitsEveryValue() = runTest {
        val timer = ShutterTimer(backgroundScope)
        timer.press(10) {}
        val seen = mutableListOf<Int>()
        repeat(10) {
            runCurrent()
            seen += (timer.state.value as TimerState.Counting).remaining
            advanceTimeBy(1_000)
        }
        runCurrent()
        assertEquals((10 downTo 1).toList(), seen)
        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun pressWhileCountingCancelsWithoutFiring() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(10) { fired++ }
        advanceTimeBy(3_500)
        runCurrent()
        timer.press(10) { fired++ }
        assertEquals(TimerState.Idle, timer.state.value)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun delayChangeCancelsRunningCountButIdleIsUnaffected() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.onDelayChanged()
        assertEquals(TimerState.Idle, timer.state.value)
        timer.press(2) { fired++ }
        runCurrent()
        timer.onDelayChanged()
        assertEquals(TimerState.Idle, timer.state.value)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, fired)
    }

    @Test
    fun canShootAgainAfterCancelAndAfterFire() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(2) { fired++ }
        runCurrent()
        timer.press(2) { fired++ }
        timer.press(2) { fired++ }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, fired)
        timer.press(0) { fired++ }
        runCurrent()
        assertEquals(2, fired)
    }
}
