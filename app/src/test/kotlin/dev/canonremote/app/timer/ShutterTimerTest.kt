package dev.canonremote.app.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    @Test
    fun anEarlierJobFinishingLateDoesNotResetANewerCountdown() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        // The first shot is slow (e.g. through a relay): its job is still running...
        timer.press(1) { delay(1_500); fired++ }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(TimerState.Idle, timer.state.value)
        // ...when a new countdown starts.
        timer.press(2) { fired++ }
        runCurrent()
        assertEquals(TimerState.Counting(2), timer.state.value)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(1, fired)
        // The first job's end must not clobber the running countdown.
        assertEquals(TimerState.Counting(1), timer.state.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun cancelThenPressKeepsTheNewCountdown() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(10) { fired++ }
        runCurrent()
        timer.cancel()
        timer.press(2) { fired++ }
        runCurrent()
        assertEquals(TimerState.Counting(2), timer.state.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }
    @Test
    fun delayChangeThenPressKeepsTheNewCountdown() = runTest {
        val timer = ShutterTimer(backgroundScope)
        timer.press(10) {}
        runCurrent()
        timer.onDelayChanged()
        timer.press(2) {}
        runCurrent()
        assertEquals(TimerState.Counting(2), timer.state.value)
    }

    @Test
    fun delayChangeWhileTheShotIsInProgressDoesNotCancelIt() = runTest {
        val timer = ShutterTimer(backgroundScope)
        var fired = 0
        timer.press(1) { delay(500); fired++ }
        advanceTimeBy(1_100); runCurrent()
        timer.onDelayChanged()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, fired)
        assertEquals(TimerState.Idle, timer.state.value)
    }
}
