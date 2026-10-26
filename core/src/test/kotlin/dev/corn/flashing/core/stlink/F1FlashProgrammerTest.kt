package dev.corn.flashing.core.stlink

import dev.corn.flashing.core.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class F1FlashProgrammerTest {
    private val base = 0x08000000L
    private fun plan(vararg segments: FirmwareSegment): FlashPlan = FlashPlanner.plan(
        FirmwareImage(FirmwareFormat.BIN, segments.toList(), "test"), ProductProfiles.F103_OPEN,
        TargetInfo(0x410, 0x2003, 128L * 1024, "TEST", "F1"))

    private class Memory : F1TargetMemory {
        val bytes = mutableMapOf<Long, Byte>()
        val words = mutableMapOf(StLinkProtocol.ADDR_FLASH_CR to StLinkProtocol.FLASH_CR_LOCK.toLong())
        val writes = mutableListOf<Pair<Long, Int>>()
        val erased = mutableListOf<Long>()
        var reset = false
        var rejectWrite = false
        var rejectReset = false
        var corruptRead = false
        override fun readWord(address: Long) = words[address] ?: 0L
        override fun writeWord(address: Long, value: Long) {
            when (address) {
                StLinkProtocol.ADDR_FLASH_KEYR -> if (value == StLinkProtocol.FLASH_KEY2) words[StLinkProtocol.ADDR_FLASH_CR] = 0
                StLinkProtocol.ADDR_FLASH_SR -> words[address] = 0
                StLinkProtocol.ADDR_FLASH_CR -> {
                    words[address] = value and StLinkProtocol.FLASH_CR_STRT.toLong().inv()
                    if (value and StLinkProtocol.FLASH_CR_STRT.toLong() != 0L) {
                        val page = words.getValue(StLinkProtocol.ADDR_FLASH_AR)
                        erased += page
                        repeat(1024) { bytes[page + it] = 0xFF.toByte() }
                    }
                }
                else -> words[address] = value
            }
        }
        override fun writeHalfWord(address: Long, value: Int) {
            if (rejectWrite) error("USB memory FAULT")
            check(address % 2 == 0L)
            check(readWord(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_PG.toLong() != 0L)
            writes += address to value
            bytes[address] = value.toByte(); bytes[address + 1] = (value ushr 8).toByte()
        }
        override fun readBytes(address: Long, length: Int) = ByteArray(length) {
            if (corruptRead) 0 else bytes[address + it] ?: 0xFF.toByte()
        }
        override fun resetAndRun() { if (rejectReset) error("Reset failed"); reset = true }
    }

    @Test fun programsOnlyHalfWordsAndVerifiesBeforeSuccess() {
        val memory = Memory()
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2, 3, 4)))
        val stages = mutableListOf<FlashStage>()
        F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { stage, _, _ -> stages += stage }
        assertEquals(listOf(base to 0x0201, base + 2 to 0x0403), memory.writes)
        assertEquals(FlashStage.SUCCEEDED, stages.last())
        assertTrue(stages.contains(FlashStage.VERIFYING))
        assertTrue(memory.reset)
        assertTrue(memory.readWord(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_LOCK.toLong() != 0L)
    }

    @Test fun oddAdjacentRecordsShareOneHalfWordAndPadOnlyMissingBytes() {
        val memory = Memory()
        val plan = plan(FirmwareSegment(base + 1, byteArrayOf(1)), FirmwareSegment(base + 2, byteArrayOf(2)),
            FirmwareSegment(base + 3, byteArrayOf(3)), FirmwareSegment(base + 4, byteArrayOf(4)))
        F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { _, _, _ -> }
        assertEquals(listOf(base to 0x01FF, base + 2 to 0x0302, base + 4 to 0xFF04), memory.writes)
        assertEquals(memory.writes.size, memory.writes.map { it.first }.distinct().size)
    }

    @Test fun memoryFaultStopsWithoutResetOrSuccess() {
        val memory = Memory().apply { rejectWrite = true }
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        val stages = mutableListOf<FlashStage>()
        val failure = assertThrows(FlashFailure::class.java) {
            F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { stage, _, _ -> stages += stage }
        }
        assertEquals(FlashStage.WRITING, failure.stage)
        assertTrue(failure.message!!.contains("FAULT"))
        assertFalse(memory.reset); assertFalse(stages.contains(FlashStage.SUCCEEDED))
    }

    @Test fun cancellationStopsBeforeNextHalfWordAndRemainsCancellation() {
        val memory = Memory()
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2, 3, 4)))
        val stages = mutableListOf<FlashStage>()
        assertThrows(CancellationException::class.java) {
            F1FlashProgrammer(memory) {
                if (memory.writes.size == 1) throw CancellationException("user cancel")
            }.execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { stage, _, _ -> stages += stage }
        }
        assertEquals(1, memory.writes.size)
        assertFalse(memory.reset); assertFalse(stages.contains(FlashStage.SUCCEEDED))
        assertTrue(memory.readWord(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_LOCK.toLong() != 0L)
    }

    @Test fun resetFailureIsNotSuccess() {
        val memory = Memory().apply { rejectReset = true }
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        val stages = mutableListOf<FlashStage>()
        val failure = assertThrows(FlashFailure::class.java) {
            F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { stage, _, _ -> stages += stage }
        }
        assertEquals(FlashStage.RESETTING, failure.stage)
        assertFalse(stages.contains(FlashStage.SUCCEEDED))
    }

    @Test fun verifyMismatchFailsAtVerify() {
        val memory = Memory().apply { corruptRead = true }
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        val failure = assertThrows(FlashFailure::class.java) {
            F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { _, _, _ -> }
        }
        assertEquals(FlashStage.VERIFYING, failure.stage); assertFalse(memory.reset)
    }

    @Test fun unsupportedDualBankRejectedBeforeAnyErase() {
        val image = FirmwareImage(FirmwareFormat.BIN, listOf(FirmwareSegment(base, byteArrayOf(1, 2))), "test")
        val unsupported = TargetInfo(0x430, 1, 1024L * 1024, "TEST", "F1")
        // A manually assembled legacy plan still cannot bypass the algorithm's execution guard.
        val plan = FlashPlan(image, ProductProfiles.alignedTo(ProductProfiles.F103_OPEN, unsupported), unsupported, listOf(base))
        val memory = Memory()
        assertThrows(IllegalArgumentException::class.java) {
            F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { _, _, _ -> }
        }
        assertTrue(memory.erased.isEmpty()); assertTrue(memory.writes.isEmpty())
    }

    @Test fun incompleteErasePlanRejectedBeforeAnyErase() {
        val plan = plan(FirmwareSegment(base, ByteArray(1026)))
        val memory = Memory()
        assertThrows(IllegalArgumentException::class.java) {
            F1FlashProgrammer(memory).execute(plan, plan.erasePageAddresses.take(1), FlashExecuteOptions()) { _, _, _ -> }
        }
        assertTrue(memory.erased.isEmpty())
    }
}
