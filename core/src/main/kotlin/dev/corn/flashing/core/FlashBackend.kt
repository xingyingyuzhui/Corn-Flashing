package dev.corn.flashing.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

interface FlashBackend {
    val isSimulation: Boolean
    val displayName: String
    val probeInfo: dev.corn.flashing.core.engine.ProbeInfo? get() = null
    val targetVoltageMv: Int get() = 0
    suspend fun probe(): TargetInfo
    suspend fun execute(
        plan: FlashPlan,
        options: FlashExecuteOptions = FlashExecuteOptions(),
        onProgress: (FlashProgress) -> Unit,
    )
}

/** A simulated failure: the run stops at [stage] with [message] instead of succeeding. */
data class DemoFailure(val stage: FlashStage, val message: String)

class FlashFailure(val stage: FlashStage, message: String) : Exception(message)

/** Exercises workflow only: never opens a probe, erases memory or verifies a device. */
class DemoFlashBackend(
    private val stepDelayMillis: Long = 350,
    private val target: TargetInfo = DEFAULT_TARGET,
    private val failure: DemoFailure? = null,
) : FlashBackend {
    override val isSimulation = true
    override val displayName = "模拟烧录器"

    override suspend fun probe(): TargetInfo {
        delay(stepDelayMillis)
        if (failure?.stage == FlashStage.PROBING) throw FlashFailure(FlashStage.PROBING, failure.message)
        return target
    }

    override suspend fun execute(
        plan: FlashPlan,
        options: FlashExecuteOptions,
        onProgress: (FlashProgress) -> Unit,
    ) {
        val steps = buildList {
            add(FlashStage.CONNECTING to "模拟连接探针")
            add(FlashStage.PROBING to "模拟识别芯片")
            add(FlashStage.ERASING to "模拟擦除 ${plan.erasePageAddresses.size} 页")
            add(FlashStage.WRITING to "模拟写入 ${plan.writeBytes} 字节")
            if (options.verifyMode != 2) add(FlashStage.VERIFYING to "模拟校验流程（无硬件校验）")
            if (options.resetAfter) add(FlashStage.RESETTING to "模拟复位")
        }
        steps.forEachIndexed { index, (stage, label) ->
            currentCoroutineContext().ensureActive()
            onProgress(FlashProgress(stage, index.toFloat() / steps.size, label))
            delay(stepDelayMillis)
            if (failure?.stage == stage) throw FlashFailure(stage, failure.message)
        }
        currentCoroutineContext().ensureActive()
        onProgress(FlashProgress(FlashStage.SUCCEEDED, 1f, "模拟流程完成，未操作真实芯片"))
    }

    companion object {
        val DEFAULT_TARGET = TargetInfo(0x410, 0x2003, 64L * 1024, "DEMO-00000001", "STM32F1 中容量（模拟）")
    }
}

/**
 * Simulated boards for batch runs. Each call is a freshly connected board with its own UID.
 * With [injectFaults] some boards fail or reuse a UID that already succeeded, so the
 * failure, pause and duplicate paths can be exercised without hardware.
 */
class SimulatedBoards(
    private val random: kotlin.random.Random,
    private val injectFaults: Boolean,
    counterStart: Long = 0L,
) {
    data class Board(val target: TargetInfo, val failure: DemoFailure?)

    private var counter = counterStart

    fun next(succeededUids: Collection<String>): Board {
        val roll = if (injectFaults) random.nextInt(100) else 100
        if (roll < 4 && succeededUids.isNotEmpty()) {
            return Board(DemoFlashBackend.DEFAULT_TARGET.copy(uid = succeededUids.last()), null)
        }
        counter += 1 + random.nextInt(3)
        val target = DemoFlashBackend.DEFAULT_TARGET.copy(uid = "DEMO-%08X".format(0x5000_0000L + counter))
        val failure = when (roll) {
            in 4..7 -> DemoFailure(FlashStage.VERIFYING, "模拟校验失败：0x08003C00 期望 0x4A，读回 0xFF")
            in 8..9 -> DemoFailure(FlashStage.PROBING, "模拟目标未响应：目标电压 0.00 V")
            else -> null
        }
        return Board(target, failure)
    }
}

/** Intentional hard stop until Android USB/ST-LINK/Flash integration is validated. */
class UnavailableHardwareBackend : FlashBackend {
    override val isSimulation = false
    override val displayName = "ST-LINK（后端待接入）"
    override suspend fun probe(): TargetInfo = unavailable()
    override suspend fun execute(
        plan: FlashPlan,
        options: FlashExecuteOptions,
        onProgress: (FlashProgress) -> Unit,
    ): Unit = unavailable()
    private fun unavailable(): Nothing = throw UnsupportedOperationException("真实 ST-LINK 烧录后端尚未接入，当前不能执行真实擦写")
}
