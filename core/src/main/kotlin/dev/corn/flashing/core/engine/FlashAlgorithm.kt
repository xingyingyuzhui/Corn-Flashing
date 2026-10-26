package dev.corn.flashing.core.engine

import dev.corn.flashing.core.*
import dev.corn.flashing.core.targets.TargetDescriptor

/** An algorithm owns chip-specific registers and sequencing; a probe owns only debug transport. */
interface FlashAlgorithm {
    val id: String
    fun validate(probe: ProbeInfo, target: TargetInfo)
    fun execute(
        probe: DebugProbe,
        plan: FlashPlan,
        pages: List<Long>,
        options: FlashExecuteOptions,
        checkCancelled: () -> Unit,
        onProgress: (FlashProgress) -> Unit,
    )
}

class FlashAlgorithmRegistry(algorithms: List<FlashAlgorithm>) {
    private val byId = algorithms.associateBy { it.id }
    init { require(byId.size == algorithms.size) { "烧录算法ID重复" } }
    val registeredIds: Set<String> get() = byId.keys
    fun resolve(descriptor: TargetDescriptor, probe: ProbeInfo, target: TargetInfo): FlashAlgorithm {
        descriptor.requireProgrammable(target)
        val id = requireNotNull(descriptor.algorithmId) { "${descriptor.family} 目前仅支持识别，烧录算法尚未接入；未执行擦除" }
        val algorithm = requireNotNull(byId[id]) { "烧录算法 $id 未安装；未执行擦除" }
        algorithm.validate(probe, target)
        return algorithm
    }
}
