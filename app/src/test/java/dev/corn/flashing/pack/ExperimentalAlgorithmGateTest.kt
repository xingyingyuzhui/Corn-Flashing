package dev.corn.flashing.pack

import dev.corn.flashing.core.*
import dev.corn.flashing.core.engine.*
import org.junit.Assert.*
import org.junit.Test

class ExperimentalAlgorithmGateTest {
    private val target = TargetInfo(0x410, 0, 64L * 1024, "fixture", "F1")
    private val info = ProbeInfo(ProbeKind.ST_LINK_V2, "fixture", emptySet(), "test")
    private val plan = FlashPlan(FirmwareImage(FirmwareFormat.BIN, listOf(FirmwareSegment(0x08000000, byteArrayOf(1, 2))), "fixture"),
        ProductProfile(), target, listOf(0x08000000))
    private val probe = object : DebugProbe {
        override val probeInfo = info
        override fun readMemory(address: Long, length: Int): ByteArray = error("Unexpected hardware access")
        override fun writeMemory32(address: Long, data: ByteArray): Unit = error("Unexpected hardware access")
        override fun writeHalfWord(address: Long, value: Int): Unit = error("Unexpected hardware access")
        override fun halt(): Unit = error("Unexpected hardware access")
        override fun isHalted(): Boolean = error("Unexpected hardware access")
        override fun resume(maskInterrupts: Boolean): Unit = error("Unexpected hardware access")
        override fun readCoreRegister(index: Int): Long = error("Unexpected hardware access")
        override fun writeCoreRegister(index: Int, value: Long): Unit = error("Unexpected hardware access")
        override fun resetAndRun(): Unit = error("Unexpected hardware access")
    }
    private class CountingAlgorithm : FlashAlgorithm {
        override val id = "flm-fixture"
        var validations = 0
        var executions = 0
        override fun validate(probe: ProbeInfo, target: TargetInfo) { validations++ }
        override fun execute(probe: DebugProbe, plan: FlashPlan, pages: List<Long>, options: FlashExecuteOptions,
                             checkCancelled: () -> Unit, onProgress: (FlashProgress) -> Unit) { executions++ }
    }
    private fun execute(driver: FlashAlgorithm) = driver.execute(probe, plan, plan.erasePageAddresses,
        FlashExecuteOptions(), {}, {})

    @Test fun newProcessGateAlwaysStartsClosed() {
        val previousProcess = ExperimentalAlgorithmGate()
        previousProcess.setEnabled(true)
        val nextProcess = ExperimentalAlgorithmGate()
        assertFalse(nextProcess.enabled.value)
        assertThrows(IllegalStateException::class.java) { nextProcess.requireEnabled() }
        assertThrows(IllegalStateException::class.java) { nextProcess.guard(CountingAlgorithm()) }
    }

    @Test fun explicitOptInPreservesAlgorithmIdentityAndAllowsDispatch() {
        val gate = ExperimentalAlgorithmGate()
        gate.setEnabled(true)
        val algorithm = CountingAlgorithm()
        val driver = gate.guard(algorithm)
        assertEquals(algorithm.id, driver.id)
        driver.validate(info, target)
        execute(driver)
        assertEquals(1, algorithm.validations)
        assertEquals(1, algorithm.executions)
    }

    @Test fun disablingAfterValidationBlocksStaleDriverBeforeExecution() {
        val gate = ExperimentalAlgorithmGate()
        gate.setEnabled(true)
        val algorithm = CountingAlgorithm()
        val driver = gate.guard(algorithm)
        driver.validate(info, target)
        gate.setEnabled(false)
        assertThrows(IllegalStateException::class.java) { driver.validate(info, target) }
        assertThrows(IllegalStateException::class.java) { execute(driver) }
        assertEquals(1, algorithm.validations)
        assertEquals(0, algorithm.executions)
    }

    @Test fun reopeningDoesNotResurrectDriversIssuedBeforeRevocation() {
        val gate = ExperimentalAlgorithmGate()
        gate.setEnabled(true)
        val algorithm = CountingAlgorithm()
        val stale = gate.guard(algorithm)
        gate.setEnabled(false)
        gate.setEnabled(true)
        assertThrows(IllegalStateException::class.java) { execute(stale) }
        execute(gate.guard(algorithm))
        assertEquals(1, algorithm.executions)
    }
}
