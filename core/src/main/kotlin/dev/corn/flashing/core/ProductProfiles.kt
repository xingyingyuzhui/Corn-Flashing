package dev.corn.flashing.core

/**
 * Product templates and probe alignment.
 * Capacity / page size / device group come from the probed [TargetInfo], not a fixed 64 KB lock.
 */
object ProductProfiles {
    data class DeviceGeometry(val capacitiesKiB: Set<Long>, val pageSize: Int)

    /** F1 device groups supported for planning (not a complete STM32 ordering table). */
    val GEOMETRY: Map<Int, DeviceGeometry> = mapOf(
        0x412 to DeviceGeometry(setOf(16, 32), 1024),
        0x410 to DeviceGeometry(setOf(64, 128), 1024),
        0x414 to DeviceGeometry(setOf(256, 384, 512), 2048),
        0x430 to DeviceGeometry(setOf(768, 1024), 2048),
    )

    val ALL_DEVICE_IDS: Set<Int> = GEOMETRY.keys

    /**
     * Open template: accepts every known F1 group; capacity/page size are placeholders
     * until [alignedTo] runs after a successful probe.
     */
    val F103_OPEN = ProductProfile(
        id = "f103-open",
        name = "STM32F1 · 探测后自动匹配",
        flashStart = 0x08000000L,
        flashSizeBytes = 64L * 1024,
        pageSize = 1024,
        reservedRanges = emptyList(),
        expectedDeviceIds = ALL_DEVICE_IDS,
    )

    /** @deprecated Use [F103_OPEN]; kept as alias so call sites compile during the rename. */
    val F103_MEDIUM: ProductProfile get() = F103_OPEN

    fun geometryOf(deviceId: Int): DeviceGeometry? = GEOMETRY[deviceId]

    fun familyName(deviceId: Int): String = when (deviceId) {
        0x412 -> "STM32F1 小容量"
        0x410 -> "STM32F1 中容量"
        0x414 -> "STM32F1 大容量"
        0x430 -> "STM32F1 超大容量"
        else -> "STM32 0x${deviceId.toString(16)}"
    }

    /**
     * Pin [template] to the probed chip: device id, Flash size, page size and display name.
     * Reserved ranges are kept; [FlashPlanner] still checks they fit the new capacity.
     */
    fun alignedTo(template: ProductProfile, target: TargetInfo): ProductProfile {
        val geo = requireNotNull(GEOMETRY[target.deviceId]) {
            "未知或尚未支持的芯片设备组 0x${target.deviceId.toString(16)}"
        }
        val kib = target.flashSizeBytes / 1024
        require(target.flashSizeBytes % 1024L == 0L && kib in geo.capacitiesKiB) {
            "芯片报告的 Flash 容量异常：${kib} KiB（设备组 0x${target.deviceId.toString(16)}）"
        }
        return template.copy(
            id = "f103-0x${target.deviceId.toString(16)}-${kib}k",
            name = "${familyName(target.deviceId)} · $kib KB",
            flashSizeBytes = target.flashSizeBytes,
            pageSize = geo.pageSize,
            expectedDeviceIds = setOf(target.deviceId),
        )
    }
}
