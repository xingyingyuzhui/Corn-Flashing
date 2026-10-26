package dev.corn.flashing.core

import dev.corn.flashing.core.targets.TargetCatalog

/** Product policy is kept separate from the chip's data-driven memory geometry. */
object ProductProfiles {
    data class DeviceGeometry(val capacitiesKiB: Set<Long>, val pageSize: Int)

    /** Legacy F1 aliases remain stable for existing stored profiles/batch hashes. */
    val GEOMETRY: Map<Int, DeviceGeometry> = TargetCatalog.descriptors
        .filter { it.key.startsWith("stm32f1-") }
        .associate { it.deviceId to DeviceGeometry(it.allowedCapacityKiB, it.geometry.minimumSectorSize) }
    val ALL_DEVICE_IDS: Set<Int> = GEOMETRY.keys

    val F103_OPEN = ProductProfile(
        id = "f103-open", name = "STM32F1 · 探测后自动匹配",
        flashStart = 0x08000000L, flashSizeBytes = 64L * 1024, pageSize = 1024,
        reservedRanges = emptyList(), expectedDeviceIds = ALL_DEVICE_IDS,
    )
    val F103_MEDIUM: ProductProfile get() = F103_OPEN

    /** No chip is selected until probing; placeholder geometry must never authorise an erase. */
    val AUTO_DETECT = ProductProfile(
        id = "auto-detect", name = "自动匹配芯片",
        expectedDeviceIds = TargetCatalog.descriptors.map { it.deviceId }.toSet(),
    )

    fun geometryOf(deviceId: Int): DeviceGeometry? = TargetCatalog.find(deviceId)?.let {
        DeviceGeometry(it.allowedCapacityKiB, it.geometry.minimumSectorSize)
    }
    fun familyName(deviceId: Int): String = TargetCatalog.find(deviceId)?.family ?: "STM32 0x${deviceId.toString(16)}"

    /** Read-only targets can align for display; the planner separately requires an installed algorithm. */
    fun alignedTo(template: ProductProfile, target: TargetInfo): ProductProfile = requireNotNull(TargetCatalog.find(target.deviceId)) {
        "未知或尚未支持的芯片设备组 0x${target.deviceId.toString(16)}"
    }.profileFor(target, template)
}
