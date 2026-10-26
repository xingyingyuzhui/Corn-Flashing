package dev.corn.flashing.core.engine

/** Capabilities are negotiated from a live probe, never inferred from its USB product name. */
enum class ProbeCapability(val label: String) {
    MEMORY_32("32位内存访问"), CORE_REGISTERS("核心寄存器"), HALT_RESUME("停核与运行"),
    SOFTWARE_RESET("软件系统复位"), NATIVE_HALFWORD_WRITE("原生半字写入"),
}

enum class ProbeKind(val label: String) { ST_LINK_V2("ST-LINK/V2"), ST_LINK_V3("ST-LINK/V3"), UNKNOWN("未知探针") }

data class ProbeInfo(
    val kind: ProbeKind,
    val firmware: String,
    val capabilities: Set<ProbeCapability>,
    val transport: String,
) {
    val label: String get() = kind.label
    val programmingPath: String get() = when {
        ProbeCapability.NATIVE_HALFWORD_WRITE in capabilities -> "原生半字编程"
        ProbeCapability.CORE_REGISTERS in capabilities -> "RAM 算法执行"
        else -> "仅连接诊断"
    }
}

/** Transport-neutral Cortex debug access used by target drivers and future pack algorithms. */
interface DebugProbe {
    val probeInfo: ProbeInfo
    fun readMemory(address: Long, length: Int): ByteArray
    /** SRAM or peripheral/debug registers; callers must never use this to emulate Flash STRH. */
    fun writeMemory32(address: Long, data: ByteArray)
    fun writeHalfWord(address: Long, value: Int)
    /** Establish a reproducible halted programming state; adapters may reconnect/reset-halt. */
    fun prepareProgramming() { halt() }
    fun halt()
    fun isHalted(): Boolean
    fun resume(maskInterrupts: Boolean = false)
    fun readCoreRegister(index: Int): Long
    fun writeCoreRegister(index: Int, value: Long)
    fun resetAndRun()

    fun read32(address: Long): Long {
        require(address >= 0 && address <= 0xFFFF_FFFCL && address % 4 == 0L)
        val data = readMemory(address, 4)
        check(data.size == 4) { "32位读回长度不符" }
        return data.indices.fold(0L) { value, i -> value or ((data[i].toLong() and 255) shl (i * 8)) }
    }
    fun write32(address: Long, value: Long) {
        require(address >= 0 && address <= 0xFFFF_FFFCL && address % 4 == 0L)
        writeMemory32(address, ByteArray(4) { (value ushr (it * 8)).toByte() })
    }
}
