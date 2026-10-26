package dev.corn.flashing.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NextBoardWatcherTest {
    @Test fun leavingResultStopsFutureProbesAndReentryDoesNotRenewDeadline() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var probes = 0
        var timedOut = 0
        fun start() = watch.start("job", "same", { true }, { probes++; "same" }, {}, { timedOut++ })
        start(); advanceTimeBy(2_001); runCurrent()
        assertEquals(1, probes)
        watch.stop(); advanceTimeBy(170_000); runCurrent()
        assertEquals(1, probes)
        start(); advanceTimeBy(8_000); runCurrent()
        assertEquals(1, timedOut)
        val atTimeout = probes
        start(); advanceTimeBy(20_000); runCurrent()
        assertEquals(atTimeout, probes)
        assertEquals(1, timedOut)
    }

    @Test fun cancellationDuringUsbProbeCannotStartNewBoardAfterPageExit() = runTest {
        val returned = CompletableDeferred<Unit>()
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var starts = 0
        watch.start("job", "old", { true }, {
            withContext(NonCancellable) { returned.await() }
            "new"
        }, { starts++ }, {})
        advanceTimeBy(2_001); runCurrent()
        watch.stop(); returned.complete(Unit); runCurrent()
        assertEquals(0, starts)
    }

    @Test fun sameUidAndReadErrorsNeverFlashAndReplacementStartsOnce() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var probes = 0
        val started = mutableListOf<String>()
        watch.start("job", "old", { true }, {
            when (++probes) { 1 -> error("unplugged"); 2 -> "old"; else -> "new" }
        }, started::add, {})
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf("new"), started)
        watch.start("job", "old", { true }, { "another" }, started::add, {})
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf("new"), started)
    }

    @Test fun preferenceOrRunningGuardStopsBeforeProbe() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var enabled = true
        var probes = 0
        watch.start("job", "old", { enabled }, { probes++; "new" }, {}, {})
        enabled = false
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, probes)
    }

    @Test fun disablingWhileProbeReturnsCannotStartReplacement() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var enabled = true
        var starts = 0
        watch.start("job", "old", { enabled }, { enabled = false; "new" }, { starts++ }, {})
        advanceTimeBy(2_001); runCurrent()
        assertEquals(0, starts)
    }

    @Test fun cancellationExceptionIsNotSwallowedAndNeverTimesOutLater() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var probes = 0
        var timeouts = 0
        watch.start("job", "old", { true }, { probes++; throw CancellationException() }, {}, { timeouts++ })
        advanceTimeBy(200_000); runCurrent()
        assertEquals(1, probes)
        assertEquals(0, timeouts)
    }

    @Test fun slowProbeIsBoundedByThreeMinuteBudget() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var starts = 0
        var timeouts = 0
        watch.start("job", "old", { true }, { delay(300_000); "new" }, { starts++ }, { timeouts++ })
        advanceTimeBy(180_001); runCurrent()
        assertEquals(0, starts)
        assertEquals(1, timeouts)
    }

    @Test fun unknownPreviousUidCannotTriggerReflashAndNewJobGetsOwnBudget() = runTest {
        val watch = NextBoardWatcher(backgroundScope, { testScheduler.currentTime })
        var starts = 0
        watch.start("job", null, { true }, { "new" }, { starts++ }, {})
        advanceTimeBy(200_000); runCurrent()
        assertEquals(0, starts)
        watch.start("next-job", "old", { true }, { "new" }, { starts++ }, {})
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, starts)
    }
}
