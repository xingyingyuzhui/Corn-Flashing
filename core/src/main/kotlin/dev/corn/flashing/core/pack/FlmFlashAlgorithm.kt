package dev.corn.flashing.core.pack

import dev.corn.flashing.core.*
import dev.corn.flashing.core.engine.*
import dev.corn.flashing.core.targets.*
import kotlinx.coroutines.CancellationException

/** Explicitly bound FLM. Importing a pack never creates this binding or enables a chip writer. */
class FlmFlashAlgorithm(
    val model: FlmImage,
    val descriptor: TargetDescriptor,
    private val clockHz: Long = 0,
    private val stackBytes: Int = 1024,
) : FlashAlgorithm {
    override val id = "flm-${model.sha256}"

    override fun validate(probe: ProbeInfo, target: TargetInfo) {
        require(descriptor.algorithmId == id && descriptor.support == TargetSupport.FLASH_ALGORITHM) { "FLM 尚未显式绑定当前芯片；未执行擦除" }
        validateForDescriptor(model, descriptor, target, stackBytes)
        val required = setOf(ProbeCapability.MEMORY_32, ProbeCapability.CORE_REGISTERS, ProbeCapability.HALT_RESUME, ProbeCapability.SOFTWARE_RESET)
        require(probe.capabilities.containsAll(required)) { "探针缺少 FLM 运行所需能力；未执行擦除" }
        require(clockHz in 0..0xFFFF_FFFFL) { "FLM 目标时钟参数无效" }
    }

    override fun execute(probe: DebugProbe, plan: FlashPlan, pages: List<Long>, options: FlashExecuteOptions,
                         checkCancelled: () -> Unit, onProgress: (FlashProgress) -> Unit) {
        validate(probe.probeInfo, plan.target)
        val layout = validateForDescriptor(model, descriptor, plan.target, stackBytes)
        val validated = FlashPlanner.plan(plan.image, plan.profile, plan.target, TargetRegistry(listOf(descriptor)))
        val sectors = descriptor.geometry.sectors(descriptor.flashStart, plan.target.flashSizeBytes)
        val byAddress = sectors.associateBy { it.address }
        require(pages.isNotEmpty() && pages.size == pages.distinct().size && pages.containsAll(validated.erasePageAddresses)) { "FLM 擦除计划为空、重复或未覆盖数据" }
        pages.forEach { address ->
            val sector = requireNotNull(byAddress[address]) { "FLM 擦除地址不是扇区起始地址" }
            require(plan.profile.reservedRanges.none { it.overlaps(sector.range) }) { "FLM 擦除范围涉及保留区" }
        }
        val chunks = programPages(plan)
        chunks.forEach { chunk ->
            val range = AddressRange(chunk.address, chunk.address + chunk.bytes.size)
            require(sectors.filter { it.range.overlaps(range) }.all { it.address in pages }) { "FLM 页补齐超出擦除计划" }
        }
        checkCancelled()
        val runner = FlmRunner(probe, model, layout, checkCancelled)
        var stage = FlashStage.PROBING
        var phase: Int? = null
        var failure: Throwable? = null
        try {
            probe.prepareProgramming()
            runner.prepare() // SRAM readback and zero-write return-breakpoint execution before any FLM Init.
            fun init(function: Int) {
                // Init may change hardware before returning an error. If it returns safely,
                // UnInit still owns the partially initialized phase; uncertain calls stay halted.
                phase = function
                runner.call("Init", model.initOffset, longArrayOf(model.flash.start, clockHz, function.toLong()), INIT_TIMEOUT_MS)
            }
            fun uninit() {
                val current = phase ?: return
                phase = null // Never replay UnInit when its completion is uncertain.
                runner.call("UnInit", model.unInitOffset, longArrayOf(current.toLong()), INIT_TIMEOUT_MS)
            }
            stage = FlashStage.ERASING
            init(1)
            pages.forEachIndexed { index, address ->
                checkCancelled()
                onProgress(FlashProgress(stage, .25f + .20f * index / pages.size, "FLM 擦除 ${address.hexAddress()}"))
                runner.call("EraseSector", model.eraseSectorOffset, longArrayOf(address), model.flash.eraseTimeoutMs)
            }
            uninit()
            stage = FlashStage.WRITING
            init(2)
            chunks.forEachIndexed { index, chunk ->
                checkCancelled()
                runner.uploadPage(chunk.bytes)
                runner.call("ProgramPage", model.programPageOffset,
                    longArrayOf(chunk.address, chunk.bytes.size.toLong(), layout.bufferAddress), model.flash.programTimeoutMs)
                onProgress(FlashProgress(stage, .45f + .30f * (index + 1) / chunks.size, "FLM 写入 ${index + 1}/${chunks.size} 页"))
            }
            uninit()
            if (options.verifyMode != 2) {
                stage = FlashStage.VERIFYING
                onProgress(FlashProgress(stage, .80f, "全量回读校验"))
                plan.image.segments.forEach { segment ->
                    var offset = 0
                    while (offset < segment.data.size) {
                        checkCancelled()
                        val count = minOf(256, segment.data.size - offset)
                        val actual = probe.readMemory(segment.address + offset, count)
                        check(actual.size == count && actual.contentEquals(segment.data.copyOfRange(offset, offset + count))) {
                            "FLM 回读校验失败：${(segment.address + offset).hexAddress()}"
                        }
                        offset += count
                    }
                }
                onProgress(FlashProgress(stage, .90f, "全量回读通过"))
            }
            checkCancelled()
            if (options.resetAfter) {
                stage = FlashStage.RESETTING
                onProgress(FlashProgress(stage, .95f, "软件复位并运行"))
                probe.resetAndRun()
            }
        } catch (error: Throwable) {
            val reported = when (error) {
                is CancellationException -> error
                is FlashFailure -> error
                is Exception -> FlashFailure(stage, error.message ?: "FLM 执行失败").also { it.initCause(error) }
                else -> error
            }
            failure = reported
            throw reported
        } finally {
            val pending = phase
            if (pending != null && runner.safeToCall) {
                try { runner.call("UnInit", model.unInitOffset, longArrayOf(pending.toLong()), INIT_TIMEOUT_MS, observeCancellation = false) }
                catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
        checkCancelled()
        onProgress(FlashProgress(FlashStage.SUCCEEDED, 1f, if (options.verifyMode == 2) "FLM 写入完成（未校验）" else "FLM 烧录与回读校验完成"))
    }

    private data class ProgramChunk(val address: Long, val bytes: ByteArray)

    /** Full programming pages merge sparse HEX records once, including odd records and erased padding. */
    private fun programPages(plan: FlashPlan): List<ProgramChunk> {
        val start = descriptor.flashStart
        val end = start + plan.target.flashSizeBytes
        val pageSize = model.flash.pageSize
        val pages = sortedMapOf<Long, ByteArray>()
        for (segment in plan.image.segments.sortedBy { it.address }) {
            for (index in segment.data.indices) {
                val address = segment.address + index
                val page = start + (address - start) / pageSize * pageSize
                require(page >= start && page + pageSize <= end) { "FLM 页补齐超出Flash范围" }
                val range = AddressRange(page, page + pageSize)
                require(plan.profile.reservedRanges.none { it.overlaps(range) }) { "FLM 页补齐涉及保留区" }
                val bytes = pages.getOrPut(page) { ByteArray(pageSize) { model.flash.erasedByte.toByte() } }
                bytes[(address - page).toInt()] = segment.data[index]
            }
        }
        require(pages.isNotEmpty()) { "FLM 没有可编程页" }
        return pages.map { ProgramChunk(it.key, it.value) }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5000L
        /** Pure compatibility check for import/binding. Never touches a probe or enables an algorithm. */
        fun validateForDescriptor(model: FlmImage, descriptor: TargetDescriptor, target: TargetInfo? = null,
                                  stackBytes: Int = 1024): FlmRamLayout {
            val flash = model.flash
            require(flash.type == 1 && flash.erasedByte in 0..255) { "FLM 只支持已声明的片内Flash算法" }
            require(flash.start == descriptor.flashStart && flash.size > 0 && flash.size % 1024 == 0L &&
                flash.size / 1024 in descriptor.allowedCapacityKiB) { "FLM Flash地址/容量与芯片描述不匹配" }
            if (target != null) {
                descriptor.validate(target)
                require(target.flashSizeBytes == flash.size) { "FLM Flash容量与实测芯片容量不完全一致" }
            }
            require(flash.programTimeoutMs in 1..120_000 && flash.eraseTimeoutMs in 1..120_000) { "FLM 超时参数无效" }
            val expected = descriptor.geometry.sectors(descriptor.flashStart, flash.size)
            val actual = flash.geometry().sectors(flash.start, flash.size)
            require(expected == actual) { "FLM 扇区布局与芯片描述不匹配" }
            require(flash.pageSize in 1..65536) { "FLM 编程页大小无效" }
            require(flash.size % flash.pageSize == 0L) { "FLM Flash容量不能整除编程页" }
            return FlmRamLayout.create(model, descriptor.ramStart, descriptor.ramMinimumBytes, stackBytes)
        }
    }
}
