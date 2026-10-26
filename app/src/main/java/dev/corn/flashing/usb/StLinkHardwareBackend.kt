package dev.corn.flashing.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.corn.flashing.core.FlashBackend
import dev.corn.flashing.core.FlashFailure
import dev.corn.flashing.core.FlashPlan
import dev.corn.flashing.core.FlashProgress
import dev.corn.flashing.core.FlashStage
import dev.corn.flashing.core.TargetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real ST-LINK/V2 path: open the authorized probe, enter SWD, read chip id / Flash size / UID.
 * Erase / write / verify are not implemented yet — [execute] fails clearly after a successful probe
 * so planning can still use the real capacity.
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

    override suspend fun execute(plan: FlashPlan, onProgress: (FlashProgress) -> Unit) {
        onProgress(FlashProgress(FlashStage.CONNECTING, 0.05f, "连接 ${displayName}"))
        onProgress(FlashProgress(FlashStage.PROBING, 0.2f, "识别 ${plan.target.family} · ${plan.target.flashSizeBytes / 1024} KB"))
        // Re-probe to confirm the board is still there before refusing erase.
        val live = probe()
        require(live.deviceId == plan.target.deviceId && live.flashSizeBytes == plan.target.flashSizeBytes) {
            "目标芯片与规划时不一致，请重新识别后再烧"
        }
        onProgress(FlashProgress(FlashStage.ERASING, 0.35f, "探测已通，擦写尚未接入"))
        throw FlashFailure(
            FlashStage.ERASING,
            "探测已通（${live.family} · ${live.flashSizeBytes / 1024} KB · UID ${live.uid}），" +
                "擦除 / 写入 / 回读校验尚未接入。请继续用电脑上的 STM32CubeProgrammer，或等待后续版本。",
        )
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
