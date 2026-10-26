package dev.corn.flashing.core

/**
 * Product templates describe the chip family and erase geometry.
 * Flash capacity is taken from the probed [TargetInfo], not hardcoded to 64 KB.
 */
object ProductProfiles {
    /** Default template: STM32F1 medium-density (device group 0x410), 1 KiB pages. */
    val F103_MEDIUM = ProductProfile(
        id = "f103-medium",
        name = "STM32F103 · 中容量",
        flashStart = 0x08000000L,
        flashSizeBytes = 64L * 1024, // display placeholder until a chip is probed
        pageSize = 1024,
        reservedRanges = emptyList(),
        expectedDeviceIds = setOf(0x410),
    )

    /**
     * Copy [template] with Flash size / name aligned to the probed target.
     * Keeps reserved ranges; caller must ensure they still fit.
     */
    fun alignedTo(template: ProductProfile, target: TargetInfo): ProductProfile {
        val kib = target.flashSizeBytes / 1024
        return template.copy(
            id = "${template.id.substringBeforeLast('-')}-${kib}k",
            name = "${StLinkFamily.name(target.deviceId)} · $kib KB",
            flashSizeBytes = target.flashSizeBytes,
        )
    }
}

/** Small helper so UI / planner do not depend on the stlink package for labels. */
internal object StLinkFamily {
    fun name(deviceId: Int): String = when (deviceId) {
        0x412 -> "STM32F1 小容量"
        0x410 -> "STM32F1 中容量"
        0x414 -> "STM32F1 大容量"
        0x430 -> "STM32F1 超大容量"
        else -> "STM32 0x${deviceId.toString(16)}"
    }
}
