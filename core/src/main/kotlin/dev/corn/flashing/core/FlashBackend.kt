package dev.corn.flashing.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

interface FlashBackend {
    val isSimulation: Boolean
    val displayName: String
    suspend fun probe(): TargetInfo
    suspend fun execute(plan: FlashPlan, onProgress: (FlashProgress) -> Unit)
}

/** Exercises workflow only: never opens a probe, erases memory or verifies a device. */
class DemoFlashBackend(private val stepDelayMillis: Long = 350) : FlashBackend {
    override val isSimulation = true
    override val displayName = "模拟烧录器"

    override suspend fun probe(): TargetInfo {
        delay(stepDelayMillis)
        return TargetInfo(0x410, 0x2003, 64L * 1024, "DEMO-00000001", "STM32F1 中容量（模拟）")
    }

    override suspend fun execute(plan: FlashPlan, onProgress: (FlashProgress) -> Unit) {
        val steps = listOf(
            FlashStage.CONNECTING to "模拟连接探针",
            FlashStage.PROBING to "模拟识别芯片",
            FlashStage.ERASING to "模拟擦除 ${plan.erasePageAddresses.size} 页",
            FlashStage.WRITING to "模拟写入 ${plan.writeBytes} 字节",
            FlashStage.VERIFYING to "模拟校验流程（无硬件校验）",
            FlashStage.RESETTING to "模拟复位",
        )
        steps.forEachIndexed { index, (stage, label) ->
            currentCoroutineContext().ensureActive()
            onProgress(FlashProgress(stage, index.toFloat() / steps.size, label))
            delay(stepDelayMillis)
        }
        currentCoroutineContext().ensureActive()
        onProgress(FlashProgress(FlashStage.SUCCEEDED, 1f, "模拟流程完成，未操作真实芯片"))
    }
}

/** Intentional hard stop until Android USB/ST-LINK/Flash integration is validated. */
class UnavailableHardwareBackend : FlashBackend {
    override val isSimulation = false
    override val displayName = "ST-LINK（后端待接入）"
    override suspend fun probe(): TargetInfo = unavailable()
    override suspend fun execute(plan: FlashPlan, onProgress: (FlashProgress) -> Unit): Unit = unavailable()
    private fun unavailable(): Nothing = throw UnsupportedOperationException("真实 ST-LINK 烧录后端尚未接入，当前不能执行真实擦写")
}
