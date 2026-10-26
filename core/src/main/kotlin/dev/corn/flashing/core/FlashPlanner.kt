package dev.corn.flashing.core

object FlashPlanner {
    /**
     * Build an erase/write plan.
     * Always aligns [profile] to [target] so capacity and page size follow the chip,
     * not a hardcoded 64 KB / 0x410-only product lock.
     */
    fun plan(image: FirmwareImage, profile: ProductProfile, target: TargetInfo): FlashPlan {
        val detected = requireNotNull(ProductProfiles.geometryOf(target.deviceId)) {
            "未知或尚未支持的芯片设备组，禁止擦写"
        }
        require(target.deviceId in profile.expectedDeviceIds) { "芯片设备组不符合产品配置" }
        require(target.flashSizeBytes % 1024L == 0L && target.flashSizeBytes / 1024 in detected.capacitiesKiB) {
            "芯片报告的 Flash 容量异常"
        }
        require(profile.flashStart == 0x08000000L) { "当前仅支持 F103 内部 Flash" }

        val effective = ProductProfiles.alignedTo(profile, target)
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
