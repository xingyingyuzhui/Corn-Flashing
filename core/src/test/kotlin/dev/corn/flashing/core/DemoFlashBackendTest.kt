package dev.corn.flashing.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoFlashBackendTest {
    @Test fun simulationHasExplicitIdentityAndCompletesAllStages() = runBlocking {
        val backend = DemoFlashBackend(stepDelayMillis = 0)
        val target = backend.probe()
        assertTrue(backend.isSimulation)
        assertTrue(target.uid!!.startsWith("DEMO-"))
        val progress = mutableListOf<FlashProgress>()
        backend.execute(plan(target), FlashExecuteOptions(), progress::add)
        assertEquals(FlashStage.SUCCEEDED, progress.last().stage)
        assertEquals(1f, progress.last().fraction)
        assertTrue(progress.all { it.message.contains("模拟") })
        assertTrue(progress.zipWithNext().all { (a, b) -> a.fraction <= b.fraction })
    }

    @Test fun cancellationCannotReportSuccess() = runBlocking {
        val backend = DemoFlashBackend(stepDelayMillis = 10)
        val target = backend.probe()
        val reachedWriting = CompletableDeferred<Unit>()
        val stages = mutableListOf<FlashStage>()
        val job = launch {
            backend.execute(plan(target), FlashExecuteOptions()) {
                stages += it.stage
                if (it.stage == FlashStage.WRITING) reachedWriting.complete(Unit)
            }
        }
        reachedWriting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(stages.contains(FlashStage.SUCCEEDED))
        assertFalse(stages.contains(FlashStage.VERIFYING))
    }

    @Test fun hardwarePlaceholderNeverPretendsToProbe() = runBlocking {
        val backend = UnavailableHardwareBackend()
        assertFalse(backend.isSimulation)
        try {
            backend.probe()
            fail("Hardware placeholder must reject the request")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(expected.message.orEmpty().contains("尚未接入"))
        }
    }

    private fun plan(target: TargetInfo): FlashPlan = FlashPlanner.plan(
        FirmwareParser.parse("app.bin", byteArrayOf(1, 2, 3, 4), 0x08000000),
        ProductProfile(), target,
    )
}
