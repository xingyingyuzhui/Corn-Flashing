package dev.corn.flashing.pack

import dev.corn.flashing.core.*
import dev.corn.flashing.core.engine.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local opt-in. Never persisted; disabling invalidates previously issued drivers. */
class ExperimentalAlgorithmGate {
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private var generation = 0L

    @Synchronized fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        generation++
        _enabled.value = enabled
    }

    @Synchronized fun requireEnabled() {
        check(_enabled.value) { "外部 FLM 实验执行已关闭；未执行外部算法" }
    }

    @Synchronized fun guard(algorithm: FlashAlgorithm): FlashAlgorithm {
        requireEnabled()
        val issuedGeneration = generation
        return object : FlashAlgorithm {
            override val id = algorithm.id
            override fun validate(probe: ProbeInfo, target: TargetInfo) {
                checkGeneration(issuedGeneration)
                algorithm.validate(probe, target)
            }
            override fun execute(probe: DebugProbe, plan: FlashPlan, pages: List<Long>, options: FlashExecuteOptions,
                                 checkCancelled: () -> Unit, onProgress: (FlashProgress) -> Unit) {
                checkGeneration(issuedGeneration)
                algorithm.execute(probe, plan, pages, options, checkCancelled, onProgress)
            }
        }
    }

    @Synchronized private fun checkGeneration(issuedGeneration: Long) {
        requireEnabled()
        check(generation == issuedGeneration) { "外部算法执行权限已变更；请重新创建任务" }
    }
}
