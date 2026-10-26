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
import dev.corn.flashing.core.engine.*
import dev.corn.flashing.core.targets.TargetCatalog
import dev.corn.flashing.flash.Stm32F1Algorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real ST-LINK/V2 path: probe + STM32F1 page erase / half-word program / readback.
 * The built-in F1 algorithm does not unlock RDP or write option bytes.
 * Experimental external algorithms execute target code and cannot share that guarantee.
 */
class StLinkHardwareBackend(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val targets: dev.corn.flashing.core.targets.TargetRegistry = TargetCatalog.registry,
    extraAlgorithms: List<FlashAlgorithm> = emptyList(),
) : FlashBackend {
    private val algorithms = FlashAlgorithmRegistry(listOf(Stm32F1Algorithm()) + extraAlgorithms)
    override val isSimulation = false
    override val displayName = device.productName ?: "ST-LINK/V2"

    @Volatile private var lastTarget: TargetInfo? = null
    @Volatile private var lastVoltageMv: Int = 0

    @Volatile private var lastProbeInfo: ProbeInfo? = null
    override val probeInfo: ProbeInfo? get() = lastProbeInfo
    override val targetVoltageMv: Int get() = lastVoltageMv
    val voltageMv: Int get() = targetVoltageMv
    val probed: TargetInfo? get() = lastTarget

    override suspend fun probe(): TargetInfo = sessionMutex.withLock { withContext(Dispatchers.IO) {
        StLinkUsbSession.open(manager, device).use { session ->
            val target = session.identify()
            lastProbeInfo = session.probeInfo
            lastVoltageMv = session.voltageMv
            lastTarget = target
            target
        }
    } }

    override suspend fun execute(
        plan: FlashPlan,
        options: FlashExecuteOptions,
        onProgress: (FlashProgress) -> Unit,
    ) = sessionMutex.withLock { withContext(Dispatchers.IO) {
        onProgress(FlashProgress(FlashStage.CONNECTING, 0.05f, "连接 ${displayName}"))
        StLinkUsbSession.open(manager, device).use { session ->
            currentCoroutineContext().ensureActive()
            onProgress(FlashProgress(FlashStage.PROBING, 0.15f, "识别芯片"))
            val live = session.identify()
            lastProbeInfo = session.probeInfo
            lastVoltageMv = session.voltageMv
            lastTarget = live
            require(live.deviceId == plan.target.deviceId && live.flashSizeBytes == plan.target.flashSizeBytes &&
                live.uid == plan.target.uid) {
                "目标芯片与规划时不一致，请重新识别后再烧"
            }
            onProgress(
                FlashProgress(
                    FlashStage.PROBING,
                    0.22f,
                    "${live.family} · ${live.flashSizeBytes / 1024} KB · ${live.uid ?: "无 UID"}",
                ),
            )

            val descriptor = targets.requireProgrammable(live)
            val algorithm = algorithms.resolve(descriptor, session.probeInfo, live)
            val pages = erasePages(plan, options)
            require(pages.isNotEmpty()) { "没有需要擦除的页" }
            val cancellation = currentCoroutineContext()
            try {
                algorithm.execute(session, plan, pages, options,
                    checkCancelled = { cancellation.ensureActive() }, onProgress = onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is FlashFailure) throw error
                throw FlashFailure(FlashStage.PROBING, error.message ?: "烧录预检失败")
            }
        }
    } }

    private fun erasePages(plan: FlashPlan, options: FlashExecuteOptions): List<Long> {
        if (options.eraseMode != 1) return plan.erasePageAddresses
        val descriptor = targets.requireProgrammable(plan.target)
        return descriptor.geometry.sectors(descriptor.flashStart, plan.target.flashSizeBytes)
            .filter { sector -> plan.profile.reservedRanges.none { it.overlaps(sector.range) } }
            .map { it.address }
    }

    companion object {
        // All backend instances share the same physical USB bus/probe access boundary.
        private val sessionMutex = Mutex()
        fun findAuthorized(manager: UsbManager, candidates: List<UsbProbe>): UsbDevice? {
            val authorizedIds = candidates.filter { it.candidate && it.permissionGranted }.map { it.connectionId }.toSet()
            return manager.deviceList.values.firstOrNull { device ->
                device.deviceId in authorizedIds &&
                    device.vendorId == 0x0483
            }
        }
    }
}
