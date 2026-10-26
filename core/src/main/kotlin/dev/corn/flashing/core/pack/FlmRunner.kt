package dev.corn.flashing.core.pack

import dev.corn.flashing.core.AddressRange
import dev.corn.flashing.core.engine.DebugProbe

/** Fixed SRAM allocation for one algorithm, one page buffer and an independent return breakpoint. */
data class FlmRamLayout(
    val trampolineAddress: Long,
    val imageAddress: Long,
    val bufferAddress: Long,
    val bufferSize: Int,
    val stackBottom: Long,
    val stackTop: Long,
) {
    companion object {
        fun create(model: FlmImage, ramStart: Long, ramSize: Long, stackBytes: Int = 1024): FlmRamLayout {
            require(ramStart >= 0 && ramStart % 4 == 0L && ramSize > 0 && ramSize <= 0x1_0000_0000L - ramStart) { "FLM RAM 范围无效" }
            require(stackBytes in 512..65536 && stackBytes % 8 == 0) { "FLM 栈空间必须至少512字节并8字节对齐" }
            require(model.image.isNotEmpty() && model.image.size <= 1024 * 1024) { "FLM 代码大小无效" }
            require(model.imageBase == 0L) { "FLM 当前只支持基址为0的位置无关映像" }
            require(model.flash.pageSize in 1..65536) { "FLM 页缓冲大小无效" }
            val imageAddress = align(ramStart + 4, 16)
            val bufferAddress = align(imageAddress + align(model.image.size.toLong(), 4), 16)
            val bufferSize = align(model.flash.pageSize.toLong(), 4).toInt()
            val stackBottom = align(bufferAddress + bufferSize, 8)
            val stackTop = stackBottom + 32 + stackBytes
            require(stackTop <= ramStart + ramSize) { "FLM RAM不足：代码、页缓冲和栈无法同时放入芯片RAM" }
            val ram = AddressRange(ramStart, ramStart + ramSize)
            val flash = AddressRange(model.flash.start, model.flash.start + model.flash.size)
            require(!ram.overlaps(flash)) { "FLM RAM与Flash范围重叠" }
            val codeEnd = model.codeOffset.toLong() + model.codeSize
            require(model.codeOffset >= 0 && model.codeSize > 0 && codeEnd <= model.image.size) { "FLM 代码区域无效" }
            for (offset in listOf(model.initOffset, model.unInitOffset, model.eraseSectorOffset, model.programPageOffset)) {
                val entry = offset.toLong() and -2L
                require(offset and 1 == 1 && entry >= model.codeOffset && entry + 2 <= codeEnd) { "FLM 入口不是有效的Thumb代码地址" }
            }
            require(model.staticBaseOffset in 0..model.image.size) { "FLM 静态数据基址无效" }
            for ((offset, size) in listOf(model.rwOffset to model.rwSize, model.ziOffset to model.ziSize)) {
                require(offset >= 0 && size >= 0 && offset.toLong() + size <= model.image.size) { "FLM 数据区域无效" }
            }
            require(model.ziSize == 0 || model.image.copyOfRange(model.ziOffset, model.ziOffset + model.ziSize).all { it == 0.toByte() }) { "FLM ZI区域必须初始化为0" }
            return FlmRamLayout(ramStart, imageAddress, bufferAddress, bufferSize, stackBottom, stackTop)
        }
        private fun align(value: Long, alignment: Int) = (value + alignment - 1) and -(alignment.toLong())
    }
}

/** Implements the CMSIS FlashOS call ABI. Nothing is executed merely by parsing/importing an FLM. */
class FlmRunner(
    private val probe: DebugProbe,
    val model: FlmImage,
    val layout: FlmRamLayout,
    private val checkCancelled: () -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var prepared = false
    /** False after a timeout, unexpected halt or interrupted call; do not attempt UnInit in that state. */
    var safeToCall: Boolean = false
        private set
    private val guard = ByteArray(32) { 0xA5.toByte() }

    fun prepare() {
        checkCancelled()
        check(probe.isHalted()) { "FLM 上传前目标未停核" }
        safeToCall = false
        val image = model.image.copyOf((model.image.size + 3) and -4)
        probe.writeMemory32(layout.trampolineAddress, byteArrayOf(0x00, 0xBE.toByte(), 0xFD.toByte(), 0xE7.toByte()))
        probe.writeMemory32(layout.imageAddress, image)
        check(probe.readMemory(layout.imageAddress, image.size).contentEquals(image)) { "FLM 上传回读校验失败；未执行擦除" }
        check(probe.readMemory(layout.trampolineAddress, 4).contentEquals(byteArrayOf(0x00, 0xBE.toByte(), 0xFD.toByte(), 0xE7.toByte()))) { "FLM 返回断点上传失败；未执行擦除" }
        probe.writeMemory32(layout.stackBottom, guard)
        prepared = true
        safeToCall = true
        callInternal("返回断点自检", null, longArrayOf(0), 1000, true)
    }

    fun uploadPage(bytes: ByteArray) {
        check(prepared && safeToCall) { "FLM 当前状态不能上传数据" }
        require(bytes.isNotEmpty() && bytes.size <= model.flash.pageSize)
        checkCancelled()
        val padded = bytes.copyOf((bytes.size + 3) and -4)
        try {
            probe.writeMemory32(layout.bufferAddress, padded)
            check(probe.readMemory(layout.bufferAddress, padded.size).contentEquals(padded)) { "FLM 页缓冲上传校验失败" }
        } catch (error: Throwable) {
            // A failed transport may have ambiguous command framing. Do not execute cleanup code on it.
            safeToCall = false
            throw error
        }
    }

    fun call(name: String, offset: Int, args: LongArray, timeoutMs: Long, observeCancellation: Boolean = true) {
        require(offset in listOf(model.initOffset, model.unInitOffset, model.eraseSectorOffset, model.programPageOffset)) { "FLM 调用入口未登记" }
        callInternal(name, offset, args, timeoutMs, observeCancellation)
    }

    private fun callInternal(name: String, offset: Int?, args: LongArray, timeoutMs: Long, observeCancellation: Boolean) {
        check(prepared && safeToCall) { "FLM 执行状态无效，不能继续调用 $name" }
        require(args.size <= 4 && timeoutMs in 1..120_000) { "FLM 调用参数/超时无效" }
        if (observeCancellation) checkCancelled()
        safeToCall = false
        check(probe.isHalted()) { "FLM 调用前目标未停核" }
        var failure: Throwable? = null
        try {
            for (register in 0..3) probe.writeCoreRegister(register, args.getOrElse(register) { 0 })
            probe.writeCoreRegister(9, layout.imageAddress + model.staticBaseOffset)
            probe.writeCoreRegister(20, 0) // Privileged Thread/MSP; clear CONTROL and special masks.
            probe.writeCoreRegister(13, layout.stackTop) // AAPCS: 8-byte-aligned SP.
            probe.writeCoreRegister(14, layout.trampolineAddress or 1L)
            probe.writeCoreRegister(16, 0x01000000) // Thumb, Thread mode, clear stale IT/ICI state.
            probe.writeCoreRegister(15, if (offset == null) layout.trampolineAddress else layout.imageAddress + (offset.toLong() and -2L))
            val deadline = nowNanos() + timeoutMs * 1_000_000L
            probe.resume(maskInterrupts = true)
            while (!probe.isHalted()) {
                if (observeCancellation) checkCancelled()
                check(nowNanos() < deadline) { "FLM $name 执行超时，已请求停核" }
                Thread.sleep(1)
            }
            if (observeCancellation) checkCancelled()
            val pc = probe.readCoreRegister(15) and -2L
            check(pc == layout.trampolineAddress || pc == layout.trampolineAddress + 2) { "FLM $name 未返回指定断点：PC=0x${pc.toString(16)}" }
            check(probe.readMemory(layout.stackBottom, guard.size).contentEquals(guard)) { "FLM $name 栈越界" }
            val result = probe.readCoreRegister(0)
            safeToCall = true
            check(result == 0L) { "FLM $name 返回错误：0x${result.toString(16)}" }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { probe.halt() } catch (cleanup: Throwable) {
                safeToCall = false
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
