package dev.corn.flashing.core.stlink

/** Only SRAM uses the probe's 32-bit write command. Flash STRH runs on the Cortex-M3. */
interface F1LoaderTarget {
    fun readWord(address: Long): Long
    fun writeRam(address: Long, data: ByteArray)
    fun readBytes(address: Long, length: Int): ByteArray
    fun readCoreRegister(index: Int): Long
    fun writeCoreRegister(index: Int, value: Long)
    /** Resume with debug enabled and interrupts masked; halt must clear the mask again. */
    fun resume()
    fun halt()
    fun isHalted(): Boolean
}

/** Original stackless Thumb loader; assembly source lives in core/src/main/asm. */
class F1RamLoader(
    private val target: F1LoaderTarget,
    private val checkCancelled: () -> Unit = {},
    private val timeoutMillis: Long = 5000,
) {
    private var prepared = false

    /** Called before Flash unlock/erase. Also tests register access and a zero-write BKPT run. */
    fun prepare() {
        prepared = false
        checkCancelled()
        check(target.isHalted()) { "RAM loader 需要目标先停核；未执行擦除" }
        target.writeRam(CODE_ADDRESS, CODE)
        check(target.readBytes(CODE_ADDRESS, CODE.size).contentEquals(CODE)) { "RAM loader 上传校验失败；未执行擦除" }
        val probe = ByteArray(16) { (it * 17).toByte() }
        target.writeRam(BUFFER_ADDRESS, probe)
        check(target.readBytes(BUFFER_ADDRESS, probe.size).contentEquals(probe)) { "RAM 数据区校验失败；未执行擦除" }
        val savedR0 = target.readCoreRegister(0)
        target.writeCoreRegister(0, savedR0)
        check(target.readCoreRegister(0) == savedR0) { "核心寄存器读写自检失败；未执行擦除" }
        run(FLASH_BASE, 0)
        prepared = true
    }

    fun program(address: Long, halfWords: IntArray) {
        check(prepared) { "RAM loader 未完成擦除前自检" }
        require(halfWords.isNotEmpty() && halfWords.size <= MAX_HALF_WORDS) { "RAM loader 写入块长度无效" }
        require(address % 2 == 0L && address >= FLASH_BASE && address + halfWords.size * 2 <= FLASH_END) { "RAM loader Flash 地址无效" }
        require(halfWords.all { it in 0..0xFFFF }) { "RAM loader 半字值无效" }
        checkCancelled()
        // WRITE_MEM32 requires a multiple of four; the loader still consumes only the requested halfwords.
        val payload = ByteArray((halfWords.size * 2 + 3) and -4) { 0xFF.toByte() }
        halfWords.forEachIndexed { i, word -> payload[i * 2] = word.toByte(); payload[i * 2 + 1] = (word ushr 8).toByte() }
        target.writeRam(BUFFER_ADDRESS, payload)
        check(target.readBytes(BUFFER_ADDRESS, payload.size).contentEquals(payload)) { "RAM 待烧录数据上传校验失败" }
        run(address, halfWords.size)
    }

    private fun run(address: Long, count: Int) {
        checkCancelled()
        check(target.isHalted()) { "RAM loader 执行前目标未停核" }
        target.writeCoreRegister(0, address)
        target.writeCoreRegister(1, BUFFER_ADDRESS)
        target.writeCoreRegister(2, count.toLong())
        target.writeCoreRegister(3, 0)
        target.writeCoreRegister(14, (CODE_ADDRESS + BREAK_OFFSET) or 1L)
        target.writeCoreRegister(16, 0x01000000) // Thumb, Thread mode, no stale IT/ICI or exception state.
        target.writeCoreRegister(15, CODE_ADDRESS)
        var failure: Throwable? = null
        try {
            // resume may submit successfully and then fail while checking status: halt in that case too.
            target.resume()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!target.isHalted()) {
                checkCancelled()
                check(System.nanoTime() < deadline) { "RAM loader 执行超时，已请求停核" }
                Thread.sleep(1)
            }
            checkCancelled()
            val pc = target.readCoreRegister(15) and -2L
            check(pc == CODE_ADDRESS + BREAK_OFFSET || pc == CODE_ADDRESS + BREAK_OFFSET + 2) {
                "RAM loader 未在结束断点停核：PC=0x${pc.toString(16)}"
            }
            val remaining = target.readCoreRegister(2)
            val status = target.readCoreRegister(3)
            check(status and 0x15L == 0L) { "RAM loader Flash 错误：FLASH_SR=0x${status.toString(16)}，剩余 $remaining 半字" }
            check(remaining == 0L && target.readCoreRegister(0) == address + count * 2 &&
                target.readCoreRegister(1) == BUFFER_ADDRESS + count * 2) { "RAM loader 写入未完成，剩余 $remaining 半字" }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { target.halt() } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    companion object {
        const val CODE_ADDRESS = 0x20000000L
        const val BUFFER_ADDRESS = 0x20000100L
        const val MAX_HALF_WORDS = 256
        const val BREAK_OFFSET = 0x26L
        private const val FLASH_BASE = 0x08000000L
        private const val FLASH_END = 0x08080000L
        // Generated from f1_halfword_loader.S with NDK LLVM clang/objcopy, not copied from another loader.
        internal val CODE: ByteArray = "0023002a0fd0094c14260127342525600d88058023683b42fcd1334203d102300231013af2d100befde700000c200240"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
