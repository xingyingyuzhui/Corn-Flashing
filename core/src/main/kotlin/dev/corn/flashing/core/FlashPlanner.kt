package dev.corn.flashing.core

object FlashPlanner {
    private data class Geometry(val capacitiesKiB: Set<Long>, val pageSize: Int)
    // These IDs identify F1 device groups, not a complete STM32F103 ordering code.
    private val geometry = mapOf(
        0x412 to Geometry(setOf(16, 32), 1024),
        0x410 to Geometry(setOf(64, 128), 1024),
        0x414 to Geometry(setOf(256, 384, 512), 2048),
        0x430 to Geometry(setOf(768, 1024), 2048),
    )

    /**
     * Build an erase/write plan.
     * Flash capacity comes from [target] (chip-reported). The product [profile]
     * supplies family, page size and reserved ranges; its [ProductProfile.flashSizeBytes]
     * is replaced by the probed size when they disagree but remain legal for the device group.
     */
    fun plan(image: FirmwareImage, profile: ProductProfile, target: TargetInfo): FlashPlan {
        val detected = requireNotNull(geometry[target.deviceId]) { "未知或尚未支持的芯片设备组，禁止擦写" }
        require(target.deviceId in profile.expectedDeviceIds) { "芯片设备组不符合产品配置" }
        require(target.flashSizeBytes % 1024L == 0L && target.flashSizeBytes / 1024 in detected.capacitiesKiB) {
            "芯片报告的 Flash 容量异常"
        }
        require(profile.flashStart == 0x08000000L) { "当前仅支持 F103 内部 Flash" }
        require(profile.pageSize == detected.pageSize) { "擦除页大小不符合芯片设备组" }
        val effective = if (profile.flashSizeBytes == target.flashSizeBytes) profile
        else ProductProfiles.alignedTo(profile, target)
        val flash = AddressRange(effective.flashStart, effective.flashStart + effective.flashSizeBytes)
        effective.reservedRanges.forEach { reserved ->
            require(reserved.start >= flash.start && reserved.endExclusive <= flash.endExclusive) { "保留区超出 Flash 范围" }
        }
        require(image.segments.isNotEmpty()) { "固件没有可烧录数据" }
        val pages = sortedSetOf<Long>()
        var previousEnd = flash.start
        image.segments.sortedBy { it.address }.forEach { segment ->
            require(segment.data.isNotEmpty()) { "固件含空数据区段" }
            require(segment.address >= flash.start && segment.endAddressExclusive <= flash.endExclusive) {
                "固件地址超出允许范围：${segment.address.hexAddress()}"
            }
            require(segment.address >= previousEnd) { "固件数据区段重叠" }
            previousEnd = segment.endAddressExclusive
            val writeRange = AddressRange(segment.address, segment.endAddressExclusive)
            require(effective.reservedRanges.none { it.overlaps(writeRange) }) { "固件覆盖产品保留区" }
            val firstPage = (segment.address - flash.start) / effective.pageSize
            val lastPage = (segment.endAddressExclusive - 1 - flash.start) / effective.pageSize
            for (index in firstPage..lastPage) pages += flash.start + index * effective.pageSize
        }
        pages.forEach { page ->
            val eraseRange = AddressRange(page, page + effective.pageSize)
            require(effective.reservedRanges.none { it.overlaps(eraseRange) }) {
                "擦除页 ${page.hexAddress()} 包含保留数据，当前不支持自动读改写"
            }
        }
        return FlashPlan(image, effective, target, pages.toList())
    }
}
