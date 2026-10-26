package dev.corn.flashing.core.pack

import dev.corn.flashing.core.*
import dev.corn.flashing.core.engine.*
import dev.corn.flashing.core.targets.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FlmRunnerTest {
    private fun model(): FlmImage = FlmParser.parse(requireNotNull(javaClass.getResourceAsStream("/fixtures/fixture.flm")).use { it.readBytes() })
    private fun descriptor(model: FlmImage) = TargetCatalog.find(0x414)!!.copy(
        key = "test-flm", algorithmId = "flm-${model.sha256}", support = TargetSupport.FLASH_ALGORITHM,
        allowedCapacityKiB = setOf(256),
    )
    private val target = TargetInfo(0x414, 1, 256L * 1024, "test", "F1")
    private fun image(vararg segments: FirmwareSegment) = FirmwareImage(FirmwareFormat.HEX, segments.toList(), "test")
    private fun plan(model: FlmImage, image: FirmwareImage, reserved: List<AddressRange> = emptyList()) =
        FlashPlanner.plan(image, ProductProfiles.AUTO_DETECT.copy(reservedRanges = reserved), target, TargetRegistry(listOf(descriptor(model))))

    @Test fun realFixtureHasDisjointAlignedRamLayoutAndCorrectCallAbi() {
        val model = model()
        val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        assertEquals(0L, layout.stackTop % 8)
        assertEquals(0L, layout.imageAddress % 16)
        assertEquals(0L, layout.bufferAddress % 16)
        assertTrue(layout.imageAddress + model.image.size <= layout.bufferAddress)
        assertTrue(layout.bufferAddress + layout.bufferSize <= layout.stackBottom)
        val probe = FakeProbe(model, layout)
        val runner = FlmRunner(probe, model, layout)
        runner.prepare()
        runner.call("Init", model.initOffset, longArrayOf(0x08000000, 8_000_000, 1), 100)
        val call = probe.calls.single { it.offset == model.initOffset and -2 }
        assertEquals(0x08000000L, call.registers[0])
        assertEquals(8_000_000L, call.registers[1])
        assertEquals(1L, call.registers[2])
        assertEquals(layout.imageAddress + model.staticBaseOffset, call.registers[9])
        assertEquals(layout.stackTop, call.registers[13])
        assertEquals(layout.trampolineAddress or 1L, call.registers[14])
        assertEquals(0x01000000L, call.registers[16])
        assertEquals(0L, call.registers[20])
        assertTrue(probe.resumeMasked)
        assertTrue(runner.safeToCall)
    }

    @Test fun insufficientRamAndInvalidEntryRejectedWithoutAnyProbeWrites() {
        val model = model()
        assertThrows(IllegalArgumentException::class.java) { FlmRamLayout.create(model, 0x20000000, 128) }
        assertThrows(IllegalArgumentException::class.java) { FlmRamLayout.create(model.copy(initOffset = 0), 0x20000000, 4096) }
        assertThrows(IllegalArgumentException::class.java) { FlmRamLayout.create(model.copy(staticBaseOffset = model.image.size + 4), 0x20000000, 4096) }
    }

    @Test fun zeroWritePreflightDoesNotCallFlashFunctions() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout)
        FlmRunner(probe, model, layout).prepare()
        assertEquals(listOf(-1), probe.calls.map { it.offset })
        assertTrue(probe.flashWrites.isEmpty())
        assertTrue(probe.memory.keys.none { it in model.flash.start until model.flash.start + model.flash.size })
    }

    @Test fun failedUploadStopsBeforeExecutingAnyFunction() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply { corruptRamReads = true }
        assertThrows(IllegalStateException::class.java) { FlmRunner(probe, model, layout).prepare() }
        assertTrue(probe.calls.isEmpty())
    }

    @Test fun nonzeroReturnAndUnexpectedPcNeverSucceed() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout)
        val runner = FlmRunner(probe, model, layout); runner.prepare()
        probe.returnError = model.eraseSectorOffset and -2
        val error = assertThrows(IllegalStateException::class.java) { runner.call("EraseSector", model.eraseSectorOffset, longArrayOf(model.flash.start), 100) }
        assertTrue(error.message!!.contains("返回错误")); assertTrue(runner.safeToCall)
        probe.returnError = null; probe.wrongPc = true
        assertThrows(IllegalStateException::class.java) { runner.call("EraseSector", model.eraseSectorOffset, longArrayOf(model.flash.start), 100) }
        assertFalse(runner.safeToCall)
    }

    @Test fun timeoutAndCancellationHaltAndTaintCallState() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout)
        var time = 0L
        val runner = FlmRunner(probe, model, layout, nowNanos = { time += 10_000_000; time })
        runner.prepare(); probe.holdRunning = true
        assertThrows(IllegalStateException::class.java) { runner.call("Init", model.initOffset, longArrayOf(), 1) }
        assertTrue(probe.halted); assertFalse(runner.safeToCall)
        val probe2 = FakeProbe(model, layout)
        var cancelled = false
        val runner2 = FlmRunner(probe2, model, layout, checkCancelled = { if (cancelled) throw CancellationException("test") })
        runner2.prepare(); probe2.holdRunning = true; probe2.onResume = { cancelled = true }
        assertThrows(CancellationException::class.java) { runner2.call("Init", model.initOffset, longArrayOf(), 100) }
        assertTrue(probe2.halted); assertFalse(runner2.safeToCall)
    }

    @Test fun fullLifecycleMergesOddSparseRecordsAndProgramsEachPageOnce() {
        val model = model(); val descriptor = descriptor(model)
        val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor, target)
        val probe = FakeProbe(model, layout)
        val image = image(FirmwareSegment(0x08000001, byteArrayOf(0x12)), FirmwareSegment(0x08000002, byteArrayOf(0x34)),
            FirmwareSegment(0x080000FF, byteArrayOf(0x56, 0x78)))
        val plan = plan(model, image)
        val events = mutableListOf<FlashProgress>()
        FlmFlashAlgorithm(model, descriptor).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, events::add)
        assertEquals(listOf(0x08000000L, 0x08000100L), probe.flashWrites)
        assertEquals(0xFF.toByte(), probe.memory[0x08000000L])
        assertEquals(0x12.toByte(), probe.memory[0x08000001L])
        assertEquals(0x34.toByte(), probe.memory[0x08000002L])
        assertEquals(0x78.toByte(), probe.memory[0x08000100L])
        val phases = probe.calls.filter { it.offset == model.initOffset and -2 }.map { it.registers[2] }
        assertEquals(listOf(1L, 2L), phases)
        assertEquals(2, probe.calls.count { it.offset == model.unInitOffset and -2 })
        assertTrue(probe.reset); assertEquals(FlashStage.SUCCEEDED, events.last().stage)
    }

    @Test fun malformedGeometryAndReservedPaddingFailBeforeInitOrHalt() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout)
        assertThrows(IllegalArgumentException::class.java) { FlmFlashAlgorithm.validateForDescriptor(model.copy(flash = model.flash.copy(size = 128L * 1024)), descriptor(model), target) }
        // The data avoids the reservation but its programming-page padding overlaps it.
        val rawPlan = FlashPlan(image(FirmwareSegment(0x08000001, byteArrayOf(1))),
            ProductProfiles.AUTO_DETECT.copy(reservedRanges = listOf(AddressRange(0x08000080, 0x08000081))), target, listOf(0x08000000))
        assertThrows(IllegalArgumentException::class.java) { FlmFlashAlgorithm(model, descriptor(model)).execute(probe, rawPlan, rawPlan.erasePageAddresses, FlashExecuteOptions(), {}, {}) }
        assertTrue(probe.calls.isEmpty()); assertEquals(0, probe.prepareCalls)
    }

    @Test fun eraseErrorUninitializesSafelyAndDoesNotResetOrReportSuccess() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply { returnError = model.eraseSectorOffset and -2 }
        val plan = plan(model, image(FirmwareSegment(0x08000000, byteArrayOf(1))))
        val events = mutableListOf<FlashProgress>()
        assertThrows(FlashFailure::class.java) { FlmFlashAlgorithm(model, descriptor(model)).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, events::add) }
        assertEquals(1, probe.calls.count { it.offset == model.unInitOffset and -2 })
        assertFalse(probe.reset); assertTrue(events.none { it.stage == FlashStage.SUCCEEDED })
    }

    @Test fun uninitFailurePreservesTheOriginalProgrammingError() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply {
            returnError = model.programPageOffset and -2
            failUninitAfterProgram = true
        }
        val plan = plan(model, image(FirmwareSegment(0x08000000, byteArrayOf(1))))
        val error = assertThrows(FlashFailure::class.java) {
            FlmFlashAlgorithm(model, descriptor(model)).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, {})
        }
        assertTrue(error.message!!.contains("ProgramPage"))
        assertTrue(error.suppressed.single().message!!.contains("UnInit"))
        assertFalse(probe.reset)
    }

    @Test fun wrongReadbackNeverReportsSuccessOrResets() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply { corruptFlash = true }
        val plan = plan(model, image(FirmwareSegment(0x08000000, byteArrayOf(1))))
        val error = assertThrows(FlashFailure::class.java) {
            FlmFlashAlgorithm(model, descriptor(model)).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, {})
        }
        assertEquals(FlashStage.VERIFYING, error.stage)
        assertFalse(probe.reset)
    }

    @Test fun nonzeroInitReturnUninitializesThePartialPhaseAndPreservesError() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply {
            returnError = model.initOffset and -2
            failUninitAlways = true
        }
        val plan = plan(model, image(FirmwareSegment(0x08000000, byteArrayOf(1))))
        val error = assertThrows(FlashFailure::class.java) {
            FlmFlashAlgorithm(model, descriptor(model)).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, {})
        }
        assertTrue(error.message!!.contains("Init 返回错误"))
        val uninit = probe.calls.single { it.offset == model.unInitOffset and -2 }
        assertEquals(1L, uninit.registers[0])
        assertTrue(error.suppressed.single().message!!.contains("UnInit"))
        assertTrue(probe.flashWrites.isEmpty()); assertFalse(probe.reset)
        assertEquals(0, probe.calls.count { it.offset == model.eraseSectorOffset and -2 })
    }

    @Test fun uncertainInitReturnDoesNotExecuteUninit() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout).apply { wrongPcOnInit = true }
        val plan = plan(model, image(FirmwareSegment(0x08000000, byteArrayOf(1))))
        assertThrows(FlashFailure::class.java) {
            FlmFlashAlgorithm(model, descriptor(model)).execute(probe, plan, plan.erasePageAddresses, FlashExecuteOptions(), {}, {})
        }
        assertEquals(0, probe.calls.count { it.offset == model.unInitOffset and -2 })
        assertTrue(probe.halted); assertFalse(probe.reset)
    }

    @Test fun failedHaltStatusReadTaintsSessionBeforeAnyFurtherAlgorithmCall() {
        val model = model(); val layout = FlmFlashAlgorithm.validateForDescriptor(model, descriptor(model), target)
        val probe = FakeProbe(model, layout)
        val runner = FlmRunner(probe, model, layout)
        runner.prepare()
        runner.call("Init", model.initOffset, longArrayOf(model.flash.start, 0, 1), 100)
        val before = probe.calls.size
        probe.failHaltRead = true
        assertThrows(IllegalStateException::class.java) {
            runner.call("EraseSector", model.eraseSectorOffset, longArrayOf(model.flash.start), 100)
        }
        assertFalse(runner.safeToCall)
        assertEquals(before, probe.calls.size)
    }

    private data class Call(val offset: Int, val registers: LongArray)
    private class FakeProbe(val model: FlmImage, val layout: FlmRamLayout) : DebugProbe {
        override val probeInfo = ProbeInfo(ProbeKind.ST_LINK_V2, "V2J23S4", ProbeCapability.entries.toSet(), "test")
        val memory = mutableMapOf<Long, Byte>(); val registers = LongArray(21)
        val calls = mutableListOf<Call>(); val flashWrites = mutableListOf<Long>()
        var halted = true; var holdRunning = false; var wrongPc = false; var returnError: Int? = null
        var corruptRamReads = false; var resumeMasked = false; var reset = false; var prepareCalls = 0
        var onResume: () -> Unit = {}
        var failUninitAfterProgram = false; var failUninitAlways = false; var wrongPcOnInit = false
        var programAttempted = false; var corruptFlash = false
        override fun readMemory(address: Long, length: Int) = ByteArray(length) { memory[address + it] ?: 0xFF.toByte() }.also {
            if (corruptRamReads && address == layout.imageAddress) it[0] = (it[0].toInt() xor 1).toByte()
        }
        override fun writeMemory32(address: Long, data: ByteArray) { check(address % 4 == 0L && data.size % 4 == 0); data.forEachIndexed { index, byte -> memory[address + index] = byte } }
        override fun writeHalfWord(address: Long, value: Int) = error("FLM must not use host Flash writes")
        override fun prepareProgramming() { prepareCalls++; halted = true }
        override fun halt() { halted = true }
        var failHaltRead = false
        override fun isHalted(): Boolean { check(!failHaltRead) { "USB read failed" }; return halted }
        override fun resume(maskInterrupts: Boolean) {
            resumeMasked = maskInterrupts; onResume(); halted = false
            val offset = if (registers[15] == layout.trampolineAddress) -1 else (registers[15] - layout.imageAddress).toInt()
            calls += Call(offset, registers.copyOf())
            if (holdRunning) return
            if (offset == model.eraseSectorOffset and -2 && returnError != offset) {
                for (i in 0 until 2048) memory[registers[0] + i] = 0xFF.toByte()
            }
            if (offset == model.programPageOffset and -2) programAttempted = true
            if (offset == model.programPageOffset and -2 && returnError != offset) {
                flashWrites += registers[0]
                for (i in 0 until registers[1].toInt()) memory[registers[0] + i] = memory[registers[2] + i]!!
            }
            if (corruptFlash && offset == model.programPageOffset and -2) memory[model.flash.start] = 0
            registers[0] = if (returnError == offset || ((failUninitAlways || failUninitAfterProgram && programAttempted) && offset == model.unInitOffset and -2)) 1 else 0
            registers[15] = if (wrongPc || wrongPcOnInit && offset == model.initOffset and -2) model.flash.start else layout.trampolineAddress
            halted = true
        }
        override fun readCoreRegister(index: Int) = registers[index]
        override fun writeCoreRegister(index: Int, value: Long) { registers[index] = value }
        override fun resetAndRun() { reset = true; halted = false }
    }
}
