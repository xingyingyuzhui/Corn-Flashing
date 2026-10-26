package dev.corn.flashing.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.corn.flashing.core.FlashBackend
import dev.corn.flashing.core.FlashExecuteOptions
import dev.corn.flashing.core.FlashFailure
import dev.corn.flashing.core.FlashPlan
import dev.corn.flashing.core.FlashProgress
import dev.corn.flashing.core.FlashStage
import dev.corn.flashing.core.TargetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Real ST-LINK/V2 path: probe + STM32F1 page erase / half-word program / readback.
 * Does not unlock RDP or write option bytes.
 */
class StLinkHardwareBackend(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : FlashBackend {
    override val isSimulation = false
    override val displayName = device.productName ?: "ST-LINK/V2"

    @Volatile private var lastTarget: TargetInfo? = null
    @Volatile private var lastVoltageMv: Int = 0

    val voltageMv: Int get() = lastVoltageMv
    val probed: TargetInfo? get() = lastTarget

    override suspend fun probe(): TargetInfo = withContext(Dispatchers.IO) {
        StLinkUsbSession.open(manager, device).use { session ->
            val target = session.identify()
            lastVoltageMv = session.voltageMv
            lastTarget = target
            target
        }
    }

    override suspend fun execute(
        plan: FlashPlan,
        options: FlashExecuteOptions,
        onProgress: (FlashProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(FlashProgress(FlashStage.CONNECTING, 0.05f, "连接 ${displayName}"))
        StLinkUsbSession.open(manager, device).use { session ->
            currentCoroutineContext().ensureActive()
            onProgress(FlashProgress(FlashStage.PROBING, 0.15f, "识别芯片"))
            val live = session.identify()
            lastVoltageMv = session.voltageMv
            lastTarget = live
            require(live.deviceId == plan.target.deviceId && live.flashSizeBytes == plan.target.flashSizeBytes) {
                "目标芯片与规划时不一致，请重新识别后再烧"
            }
            onProgress(
                FlashProgress(
                    FlashStage.PROBING,
                    0.22f,
                    "${live.family} · ${live.flashSizeBytes / 1024} KB · ${live.uid ?: "无 UID"}",
                ),
            )

            val pages = erasePages(plan, options)
            require(pages.isNotEmpty()) { "没有需要擦除的页" }
            val verify = options.verifyMode != 2
            try {
                session.programF1(
                    image = plan.image,
                    pages = pages,
                    pageSize = plan.profile.pageSize,
                    resetAfter = options.resetAfter,
                    verify = verify,
                ) { stage, frac, msg ->
                    onProgress(FlashProgress(stage, frac, msg))
                }
            } catch (error: Exception) {
                if (error is FlashFailure) throw error
                val stage = when {
                    error.message?.contains("校验") == true -> FlashStage.VERIFYING
                    error.message?.contains("擦除") == true -> FlashStage.ERASING
                    error.message?.contains("编程") == true || error.message?.contains("写入") == true ->
                        FlashStage.WRITING
                    error.message?.contains("复位") == true -> FlashStage.RESETTING
                    else -> FlashStage.ERASING
                }
                throw FlashFailure(stage, error.message ?: "擦写失败")
            }
        }
    }

    private fun erasePages(plan: FlashPlan, options: FlashExecuteOptions): List<Long> {
        if (options.eraseMode != 1) return plan.erasePageAddresses
        // Whole-chip page erase within profile capacity (still only Flash range; no option bytes).
        val start = plan.profile.flashStart
        val end = start + plan.profile.flashSizeBytes
        val page = plan.profile.pageSize.toLong()
        val reserved = plan.profile.reservedRanges
        val all = mutableListOf<Long>()
        var addr = start
        while (addr < end) {
            val pageEnd = addr + page
            val overlapsReserved = reserved.any { r ->
                addr < r.endExclusive && pageEnd > r.start
            }
            if (!overlapsReserved) all += addr
            addr = pageEnd
        }
        return all
    }

    companion object {
        fun findAuthorized(manager: UsbManager, candidates: List<UsbProbe>): UsbDevice? {
            val authorizedIds = candidates.filter { it.candidate && it.permissionGranted }.map { it.connectionId }.toSet()
            return manager.deviceList.values.firstOrNull { device ->
                device.deviceId in authorizedIds &&
                    device.vendorId == 0x0483
            }
        }
    }
}
