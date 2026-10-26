package dev.corn.flashing.flash

import android.util.Log
import dev.corn.flashing.core.*
import dev.corn.flashing.core.engine.*
import dev.corn.flashing.core.stlink.*
import dev.corn.flashing.core.targets.TargetCatalog

/** Chip-specific sequencing is independent of ST-LINK USB packet transport. */
class Stm32F1Algorithm : FlashAlgorithm {
    override val id = "stm32f1-single-bank"

    override fun validate(probe: ProbeInfo, target: TargetInfo) {
        val descriptor = TargetCatalog.requireProgrammable(target)
        require(descriptor.algorithmId == id) { "芯片与 F1 算法不匹配；未执行擦除" }
        val required = setOf(ProbeCapability.MEMORY_32, ProbeCapability.HALT_RESUME, ProbeCapability.SOFTWARE_RESET)
        require(probe.capabilities.containsAll(required)) { "探针 ${probe.firmware} 缺少所需调试能力；未执行擦除" }
        require(ProbeCapability.NATIVE_HALFWORD_WRITE in probe.capabilities || ProbeCapability.CORE_REGISTERS in probe.capabilities) {
            "探针既不支持半字写入，也不支持 RAM 算法运行；未执行擦除"
        }
    }

    override fun execute(probe: DebugProbe, plan: FlashPlan, pages: List<Long>, options: FlashExecuteOptions,
                         checkCancelled: () -> Unit, onProgress: (FlashProgress) -> Unit) {
        validate(probe.probeInfo, plan.target)
        checkCancelled()
        probe.prepareProgramming()
        val useRamLoader = ProbeCapability.NATIVE_HALFWORD_WRITE !in probe.probeInfo.capabilities
        val memory = object : F1TargetMemory {
            override fun readWord(address: Long) = probe.read32(address)
            override fun writeWord(address: Long, value: Long) = probe.write32(address, value)
            override fun writeHalfWord(address: Long, value: Int) = probe.writeHalfWord(address, value)
            override fun readBytes(address: Long, length: Int) = probe.readMemory(address, length)
            override fun resetAndRun() = probe.resetAndRun()
        }
        val loader = if (useRamLoader) F1RamLoader(loaderTarget(probe), checkCancelled) else null
        Log.i("CornUsb", "Algorithm=$id path=${probe.probeInfo.programmingPath} firmware=${probe.probeInfo.firmware}")
        val debugControl = if (useRamLoader) probe.read32(0xE0042004L) else null
        Log.i("CornUsb", "F1 before programming RCC_CSR=0x${probe.read32(0x40021024L).toString(16)} DBGMCU_CR=0x${debugControl?.toString(16)}")
        var failure: Throwable? = null
        try {
            if (debugControl != null) {
                probe.write32(0xE0042004L, debugControl or 0x300L)
                check(probe.read32(0xE0042004L) and 0x300L == 0x300L) { "无法冻结调试态看门狗；未执行擦除" }
            }
            F1FlashProgrammer(memory, ramLoader = loader, checkCancelled = checkCancelled)
                .execute(plan, pages, options) { stage, progress, message -> onProgress(FlashProgress(stage, progress, message)) }
        } catch (error: Throwable) {
            failure = error
            Log.e("CornUsb", "Algorithm=$id failed", error)
            runCatching {
                val halted = probe.isHalted()
                val pc = if (halted) probe.readCoreRegister(15).toString(16) else "running"
                val remaining = if (halted) probe.readCoreRegister(2).toString() else "unknown"
                Log.e("CornUsb", "F1 failure snapshot: PC=$pc remaining=$remaining FLASH_SR=0x${probe.read32(0x4002200CL).toString(16)} RCC_CSR=0x${probe.read32(0x40021024L).toString(16)}")
            }
            throw error
        } finally {
            if (debugControl != null) try { probe.write32(0xE0042004L, debugControl) } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun loaderTarget(probe: DebugProbe) = object : F1LoaderTarget {
        override fun readWord(address: Long) = probe.read32(address)
        override fun readBytes(address: Long, length: Int) = probe.readMemory(address, length)
        override fun writeRam(address: Long, data: ByteArray) {
            require(address >= 0x20000000L && address + data.size <= 0x20000400L &&
                address % 4 == 0L && data.size % 4 == 0) { "RAM loader 上传范围/对齐无效" }
            probe.writeMemory32(address, data)
        }
        override fun readCoreRegister(index: Int) = probe.readCoreRegister(index)
        override fun writeCoreRegister(index: Int, value: Long) = probe.writeCoreRegister(index, value)
        override fun resume() {
            // Reload an already-running F1 IWDG before each short RAM execution.
            // 0xAAAA reloads; it does not start the watchdog (0xCCCC) or change its configuration.
            probe.write32(0x40003000L, 0xAAAAL)
            probe.resume(maskInterrupts = true)
        }
        override fun halt() = probe.halt()
        override fun isHalted() = probe.isHalted()
    }
}
