package dev.corn.flashing.core.stlink

import dev.corn.flashing.core.*
import kotlinx.coroutines.CancellationException

/** Explicit access widths keep transport packet length distinct from the target bus access. */
interface F1TargetMemory {
    fun readWord(address: Long): Long
    fun writeWord(address: Long, value: Long)
    fun writeHalfWord(address: Long, value: Int)
    fun readBytes(address: Long, length: Int): ByteArray
    fun resetAndRun()
}

/** Single-bank F1 Flash algorithm; transport errors and cancellation must reach the job owner. */
class F1FlashProgrammer(
    private val memory: F1TargetMemory,
    private val ramLoader: F1RamLoader? = null,
    private val checkCancelled: () -> Unit = {},
) {
    private var stage = FlashStage.PROBING

    fun execute(
        plan: FlashPlan,
        pages: List<Long>,
        options: FlashExecuteOptions,
        onProgress: (FlashStage, Float, String) -> Unit,
    ) {
        checkCancelled()
        validateBeforeErase(plan, pages)
        ramLoader?.prepare() // No Flash mutation until SRAM/register/BKPT self-checks pass.
        var unlocked = false
        var failure: Throwable? = null
        try {
            unlocked = true // Cleanup also runs if the key write succeeded but its status read failed.
            unlock()
            stage = FlashStage.ERASING
            pages.forEachIndexed { index, page ->
                checkCancelled()
                onProgress(stage, .25f + .20f * index / pages.size, "擦除页 ${page.hexAddress()}")
                erasePage(page)
            }
            stage = FlashStage.WRITING
            onProgress(stage, .45f, "开始半字写入")
            var written = 0L
            if (ramLoader == null) {
                halfWords(plan.image).forEach { word ->
                    checkCancelled()
                    waitReady()
                    clearFlags()
                    memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control() or StLinkProtocol.FLASH_CR_PG.toLong())
                    memory.writeHalfWord(word.address, word.value)
                    waitReady()
                    requireFlashStatus("编程 ${word.address.hexAddress()}")
                    memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control())
                    written += word.sourceBytes
                    if (written % 128 == 0L || written == plan.writeBytes) {
                        onProgress(stage, .45f + .30f * written / plan.writeBytes, "写入 $written/${plan.writeBytes} 字节")
                    }
                }
            } else {
                for (chunk in loaderChunks(plan.image)) {
                    checkCancelled()
                    waitReady()
                    clearFlags()
                    memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control() or StLinkProtocol.FLASH_CR_PG.toLong())
                    ramLoader.program(chunk.first().address, chunk.map { it.value }.toIntArray())
                    waitReady()
                    requireFlashStatus("RAM 编程 ${chunk.first().address.hexAddress()}")
                    memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control())
                    written += chunk.sumOf { it.sourceBytes }
                    onProgress(stage, .45f + .30f * written / plan.writeBytes, "RAM 写入 $written/${plan.writeBytes} 字节")
                }
            }
            if (options.verifyMode != 2) {
                stage = FlashStage.VERIFYING
                onProgress(stage, .80f, "回读校验")
                plan.image.segments.forEach { segment ->
                    var offset = 0
                    while (offset < segment.data.size) {
                        checkCancelled()
                        val length = minOf(256, segment.data.size - offset)
                        val actual = memory.readBytes(segment.address + offset, length)
                        require(actual.size == length) { "校验读回长度不符" }
                        for (i in actual.indices) {
                            require(actual[i] == segment.data[offset + i]) {
                                "校验失败：${(segment.address + offset + i).hexAddress()} 期望 0x%02X，读回 0x%02X".format(
                                    segment.data[offset + i].toInt() and 255, actual[i].toInt() and 255)
                            }
                        }
                        offset += length
                    }
                }
                onProgress(stage, .90f, "回读校验通过")
            }
        } catch (error: Throwable) {
            failure = error
            throwAsStage(error)
        } finally {
            if (unlocked) {
                try {
                    // Finish the current hardware operation before clearing PG/PER and locking.
                    waitReady(observeCancellation = false)
                    memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control() or StLinkProtocol.FLASH_CR_LOCK.toLong())
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throwAsStage(cleanup)
                }
            }
        }
        checkCancelled()
        if (options.resetAfter) {
            stage = FlashStage.RESETTING
            onProgress(stage, .95f, "复位并运行")
            try { memory.resetAndRun() } catch (error: Throwable) { throwAsStage(error) }
        }
        checkCancelled()
        onProgress(FlashStage.SUCCEEDED, 1f, if (options.verifyMode == 2) "写入完成（未校验）" else "烧录与回读校验完成")
    }

    private fun validateBeforeErase(plan: FlashPlan, pages: List<Long>) {
        require(plan.target.deviceId in setOf(0x412, 0x410, 0x414) && plan.target.flashSizeBytes <= 512L * 1024) {
            "当前真实烧录仅支持 F1 单 Bank（最大 512 KB）；双 Bank 型号尚未验证，未执行擦除"
        }
        val validated = FlashPlanner.plan(plan.image, plan.profile, plan.target)
        val flashEnd = validated.profile.flashStart + validated.profile.flashSizeBytes
        require(pages.isNotEmpty() && pages.size == pages.distinct().size) { "擦除页计划为空或重复" }
        pages.forEach { page ->
            require(page >= plan.profile.flashStart && page + plan.profile.pageSize <= flashEnd &&
                (page - plan.profile.flashStart) % plan.profile.pageSize == 0L) { "擦除页地址无效" }
            require(plan.profile.reservedRanges.none { it.overlaps(AddressRange(page, page + plan.profile.pageSize)) }) {
                "擦除页涉及保留区"
            }
        }
        require(pages.containsAll(validated.erasePageAddresses)) { "擦除计划未覆盖全部写入页" }
        // Validate padding and all addresses before the first erase; no partial programming on bad layouts.
        halfWords(plan.image).forEach { word ->
            checkCancelled()
            require(word.address >= plan.profile.flashStart && word.address + 2 <= flashEnd) { "半字写入超出 Flash 范围" }
            require(plan.profile.reservedRanges.none { it.overlaps(AddressRange(word.address, word.address + 2)) }) {
                "半字对齐补齐涉及保留区"
            }
        }
    }

    private data class HalfWord(val address: Long, val value: Int, val sourceBytes: Int)

    /** Merge adjacent odd HEX records into one half-word, never program the same cell twice. */
    private fun halfWords(image: FirmwareImage): Sequence<HalfWord> = sequence {
        var current = -1L
        var value = 0xFFFF
        var count = 0
        for (segment in image.segments.sortedBy { it.address }) {
            for (index in segment.data.indices) {
                val address = segment.address + index
                val aligned = address and -2L
                if (current != aligned && current >= 0) {
                    yield(HalfWord(current, value, count))
                    value = 0xFFFF; count = 0
                }
                current = aligned
                val shift = (address and 1).toInt() * 8
                value = (value and (255 shl shift).inv()) or ((segment.data[index].toInt() and 255) shl shift)
                count++
            }
        }
        if (current >= 0) yield(HalfWord(current, value, count))
    }

    private fun loaderChunks(image: FirmwareImage): Sequence<List<HalfWord>> = sequence {
        var chunk = mutableListOf<HalfWord>()
        for (word in halfWords(image)) {
            if (chunk.isNotEmpty() && (chunk.size == F1RamLoader.MAX_HALF_WORDS || word.address != chunk.last().address + 2)) {
                yield(chunk)
                chunk = mutableListOf()
            }
            chunk += word
        }
        if (chunk.isNotEmpty()) yield(chunk)
    }

    private fun unlock() {
        waitReady()
        if (memory.readWord(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_LOCK.toLong() == 0L) return
        memory.writeWord(StLinkProtocol.ADDR_FLASH_KEYR, StLinkProtocol.FLASH_KEY1)
        memory.writeWord(StLinkProtocol.ADDR_FLASH_KEYR, StLinkProtocol.FLASH_KEY2)
        require(memory.readWord(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_LOCK.toLong() == 0L) {
            "无法解锁 Flash；本应用不修改 Option Bytes 或解除读保护"
        }
    }

    private fun control(): Long = memory.readWord(StLinkProtocol.ADDR_FLASH_CR) and 7L.inv()

    private fun erasePage(address: Long) {
        waitReady()
        clearFlags()
        memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control() or StLinkProtocol.FLASH_CR_PER.toLong())
        memory.writeWord(StLinkProtocol.ADDR_FLASH_AR, address)
        memory.writeWord(StLinkProtocol.ADDR_FLASH_CR,
            memory.readWord(StLinkProtocol.ADDR_FLASH_CR) or StLinkProtocol.FLASH_CR_STRT.toLong())
        waitReady()
        requireFlashStatus("擦除页 ${address.hexAddress()}")
        memory.writeWord(StLinkProtocol.ADDR_FLASH_CR, control())
    }

    private fun clearFlags() = memory.writeWord(StLinkProtocol.ADDR_FLASH_SR, StLinkProtocol.flashSrClearMask())

    private fun requireFlashStatus(label: String) {
        val status = memory.readWord(StLinkProtocol.ADDR_FLASH_SR)
        require(status and (StLinkProtocol.FLASH_SR_PGERR or StLinkProtocol.FLASH_SR_WRPRTERR).toLong() == 0L) {
            "$label 失败：FLASH_SR=0x${status.toString(16)}"
        }
    }

    private fun waitReady(observeCancellation: Boolean = true) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            if (observeCancellation) checkCancelled()
            if (memory.readWord(StLinkProtocol.ADDR_FLASH_SR) and StLinkProtocol.FLASH_SR_BSY.toLong() == 0L) return
            check(System.nanoTime() < deadline) { "等待 Flash 空闲超时" }
            Thread.sleep(1)
        }
    }

    private fun throwAsStage(error: Throwable): Nothing = when (error) {
        is CancellationException -> throw error
        is FlashFailure -> throw error
        is Exception -> throw FlashFailure(stage, error.message ?: "Flash 操作失败")
        else -> throw error
    }
}
