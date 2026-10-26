package dev.corn.flashing.core.stlink

import dev.corn.flashing.core.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class F1RamLoaderTest {
    private class Target : F1LoaderTarget, F1TargetMemory {
        val bytes = mutableMapOf<Long, Byte>()
        val regs = mutableMapOf<Int, Long>()
        val words = mutableMapOf(StLinkProtocol.ADDR_FLASH_CR to 0x80L)
        val erased = mutableListOf<Long>()
        val writes = mutableListOf<Pair<Long, Int>>()
        val counts = mutableListOf<Int>()
        var halted = true
        var haltCount = 0
        var corruptRam = false
        var wrongPc = false
        var neverHalt = false
        var programmingError = false
        var incomplete = false
        var reset = false
        override fun readWord(address: Long) = words[address] ?: 0L
        override fun writeWord(address: Long, value: Long) {
            when (address) {
                StLinkProtocol.ADDR_FLASH_KEYR -> if (value == StLinkProtocol.FLASH_KEY2) words[StLinkProtocol.ADDR_FLASH_CR] = 0
                StLinkProtocol.ADDR_FLASH_SR -> words[address] = 0
                StLinkProtocol.ADDR_FLASH_CR -> {
                    words[address] = value and 0x40L.inv()
                    if (value and 0x40 != 0L) {
                        val page = words.getValue(StLinkProtocol.ADDR_FLASH_AR)
                        erased += page
                        repeat(1024) { bytes[page + it] = 0xFF.toByte() }
                    }
                }
                else -> words[address] = value
            }
        }
        override fun writeHalfWord(address: Long, value: Int) { error("旧探针不得调用USB半字命令") }
        override fun writeRam(address: Long, data: ByteArray) {
            require(address % 4 == 0L && data.size % 4 == 0)
            require(address >= 0x20000000 && address + data.size <= 0x20000400)
            data.forEachIndexed { i, b -> bytes[address + i] = b }
        }
        override fun readBytes(address: Long, length: Int) = ByteArray(length) {
            if (corruptRam && address >= 0x20000000) 0 else bytes[address + it] ?: 0xFF.toByte()
        }
        override fun readCoreRegister(index: Int) = regs[index] ?: 0L
        override fun writeCoreRegister(index: Int, value: Long) { regs[index] = value }
        override fun resume() {
            halted = false
            val count = regs.getValue(2).toInt()
            counts += count
            if (neverHalt) return
            if (count > 0) {
                check(readWord(StLinkProtocol.ADDR_FLASH_CR) and 1 != 0L)
                if (!programmingError) repeat(count) {
                    val dest = regs.getValue(0) + it * 2
                    val src = regs.getValue(1) + it * 2
                    val value = (bytes.getValue(src).toInt() and 255) or ((bytes.getValue(src + 1).toInt() and 255) shl 8)
                    writes += dest to value
                    bytes[dest] = value.toByte(); bytes[dest + 1] = (value ushr 8).toByte()
                }
            }
            if (!programmingError && !incomplete) {
                regs[0] = regs.getValue(0) + count * 2
                regs[1] = regs.getValue(1) + count * 2
                regs[2] = 0
            }
            regs[3] = if (programmingError && count > 0) 4 else 0
            regs[15] = if (wrongPc) 0x08001234 else F1RamLoader.CODE_ADDRESS + F1RamLoader.BREAK_OFFSET
            halted = true
        }
        override fun halt() { halted = true; haltCount++ }
        override fun isHalted() = halted
        override fun resetAndRun() { reset = true }
    }

    private val base = 0x08000000L
    private fun plan(vararg segments: FirmwareSegment) = FlashPlanner.plan(
        FirmwareImage(FirmwareFormat.BIN, segments.toList(), "test"), ProductProfiles.F103_OPEN,
        TargetInfo(0x410, 1, 128L * 1024, "TEST", "F1"))

    @Test fun preflightExecutesZeroWriteWithoutFlashMutation() {
        val target = Target()
        F1RamLoader(target).prepare()
        assertEquals(listOf(0), target.counts)
        assertTrue(target.writes.isEmpty()); assertTrue(target.erased.isEmpty())
        assertEquals(1, target.haltCount)
    }

    @Test fun corruptLoaderIsRejectedBeforeErase() {
        val target = Target().apply { corruptRam = true }
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        assertThrows(IllegalStateException::class.java) {
            F1FlashProgrammer(target, F1RamLoader(target)).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { _, _, _ -> }
        }
        assertTrue(target.erased.isEmpty()); assertTrue(target.counts.isEmpty())
    }

    @Test fun wrongPreflightBreakpointStopsBeforeErase() {
        val target = Target().apply { wrongPc = true }
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        assertThrows(IllegalStateException::class.java) {
            F1FlashProgrammer(target, F1RamLoader(target)).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { _, _, _ -> }
        }
        assertTrue(target.erased.isEmpty()); assertTrue(target.halted)
    }

    @Test fun oddSparseRecordsAndMultiChunkUseRamWithoutUsbHalfwordCommand() {
        val target = Target()
        val plan = plan(FirmwareSegment(base + 1, ByteArray(513) { it.toByte() }),
            FirmwareSegment(base + 514, byteArrayOf(0x42)), FirmwareSegment(base + 600, byteArrayOf(0x7F)))
        val stages = mutableListOf<FlashStage>()
        F1FlashProgrammer(target, F1RamLoader(target)).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { s, _, _ -> stages += s }
        assertEquals(listOf(0, 256, 2, 1), target.counts)
        assertEquals(0x00FF, target.writes.first().second)
        assertEquals(base + 514 to 0xFF42, target.writes[target.writes.lastIndex - 1])
        assertEquals(base + 600 to 0xFF7F, target.writes.last())
        assertTrue(target.reset); assertEquals(FlashStage.SUCCEEDED, stages.last())
        assertEquals(0x80L, target.readWord(StLinkProtocol.ADDR_FLASH_CR) and 0x87L)
    }

    @Test fun flashFaultNeverReportsSuccessAndLocks() {
        val target = Target()
        val loader = F1RamLoader(target)
        val plan = plan(FirmwareSegment(base, byteArrayOf(1, 2)))
        val failure = assertThrows(FlashFailure::class.java) {
            F1FlashProgrammer(target, loader).execute(plan, plan.erasePageAddresses, FlashExecuteOptions()) { stage, _, _ ->
                if (stage == FlashStage.WRITING) target.programmingError = true
            }
        }
        assertEquals(FlashStage.WRITING, failure.stage)
        assertFalse(target.reset); assertTrue(target.halted)
        assertEquals(0x80L, target.readWord(StLinkProtocol.ADDR_FLASH_CR) and 0x87L)
    }

    @Test fun unexpectedRemainingCountIsFailure() {
        val target = Target()
        val loader = F1RamLoader(target)
        loader.prepare()
        target.incomplete = true
        target.words[StLinkProtocol.ADDR_FLASH_CR] = 1
        assertThrows(IllegalStateException::class.java) { loader.program(base, intArrayOf(0x1234)) }
        assertTrue(target.halted)
    }

    @Test fun timeoutRequestsHalt() {
        val target = Target().apply { neverHalt = true }
        assertThrows(IllegalStateException::class.java) { F1RamLoader(target, timeoutMillis = 1).prepare() }
        assertTrue(target.halted); assertEquals(1, target.haltCount)
    }

    @Test fun cancellationDuringExecutionRequestsHaltAndRetainsCancellation() {
        val target = Target().apply { neverHalt = true }
        val loader = F1RamLoader(target, checkCancelled = { if (!target.halted) throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { loader.prepare() }
        assertTrue(target.halted); assertEquals(1, target.haltCount)
    }

    @Test fun outOfRangeAndUnpreparedCannotStartExecution() {
        val target = Target()
        val loader = F1RamLoader(target)
        assertThrows(IllegalStateException::class.java) { loader.program(base, intArrayOf(1)) }
        loader.prepare()
        assertThrows(IllegalArgumentException::class.java) { loader.program(base + 1, intArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { loader.program(0x08080000, intArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { loader.program(base, IntArray(257)) }
        assertEquals(listOf(0), target.counts)
    }
}
